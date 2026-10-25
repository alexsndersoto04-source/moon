package com.moonshare.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import com.moonshare.core.MoonLog

/**
 * Lado lector del golpe: se activa SOLO en el teléfono que va a enviar.
 *
 * Leé un tag NDEF emulado por el otro teléfono ([MoonCardService]) y de ahí salte
 * directo a `WifiP2pManager.connect()`. Con `SKIP_NDEF_CACHE` a propósito: si no,
 * Android puede devolver un NDEF cached de un encuentro anterior y te parearía con
 * el teléfono equivocado.
 */
class NfcHandoff(private val activity: Activity) {

    private val host: Activity = activity

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val hasNfc: Boolean get() = adapter != null
    val isNfcOn: Boolean get() = adapter?.isEnabled == true

    private var active = false

    /** true si este lector se creó para [candidate] (evita reinicializaciones en onResume). */
    fun hosts(candidate: Activity): Boolean = host === candidate

    fun start(onToken: (PairingToken) -> Unit, onError: (String) -> Unit): Boolean {
        val a = adapter ?: run {
            onError("Este teléfono no tiene NFC")
            return false
        }
        if (!a.isEnabled) {
            onError("NFC está apagado")
            return false
        }
        if (active) return true
        active = true
        runCatching {
            a.enableReaderMode(
                activity,
                { tag ->
                    val token = readTag(tag)
                    if (token == null) {
                        MoonLog.d(TAG, "tag leído pero sin token MoonShare")
                    } else {
                        onToken(token)
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CACHE,
                null,
            )
        }.onFailure {
            active = false
            onError("No se pudo activar el lector NFC: ${it.message}")
            return false
        }
        return true
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { adapter?.disableReaderMode(activity) }
            .onFailure { MoonLog.w(TAG, "disableReaderMode: ${it.message}") }
    }

    /** Ruta alternativa: el sistema nos abrió con un NDEF_DISCOVERED en el intent. */
    fun tokenFromIntent(intent: Intent?): PairingToken? {
        if (intent == null) return null
        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            return null
        }
        @Suppress("DEPRECATION")
        val raw: Array<Any>? = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val message = raw?.firstOrNull() as? NdefMessage ?: return null
        val record = message.records.firstOrNull { it.uri != null } ?: return null
        return PairingToken.parse(record.uri)
    }

    private fun readTag(tag: Tag): PairingToken? {
        // 1) Camino feliz: el stack de Android ya reconoció el tag NDEF y nos da el mensaje.
        runCatching {
            Ndef.get(tag)?.let { ndef ->
                if (!ndef.isConnected) ndef.connect()
                val msg = runCatching { ndef.cachedNdefMessage ?: ndef.readNdefMessage() }.getOrNull()
                msg?.records?.firstNotNullOfOrNull { rec ->
                    rec.uri?.let { uri -> PairingToken.parse(uri)?.let { if (PairingToken.isFresh(it)) it else null } }
                }?.let { return it }
            }
        }
        // 2) Fallback: leemos el tag Tipo-4 a mano por ISO-DEP (HCE a veces no poplea
        //    la caché NDEF en algunos ROMs, y ahí el camino 1 devuelve null).
        return runCatching { readType4Manually(tag) }.getOrNull()
    }

    private fun readType4Manually(tag: Tag): PairingToken? {
        val iso = IsoDep.get(tag) ?: return null
        val timeout = if (iso.timeout < 5000) 5000 else iso.timeout
        iso.timeout = timeout
        iso.connect()
        iso.use {
            // SELECT NDEF Tag Application
            val select = buildSelect(MoonCardService.NDEF_AID)
            val r1 = it.transceive(select)
            if (!isOk(r1)) return null

            // SELECT FILE NDEF (E104)
            val selFile = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04.toByte(), 0x00)
            val r2 = runCatching { it.transceive(selFile) }.getOrNull()
            if (r2 != null && !isOk(r2)) return null

            // READ BINARY: primero 2 bytes = longitud, después el mensaje
            val lenResp = it.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02))
            if (!isOk(lenResp) || lenResp.size < 4) return null
            val ndefLen = ((lenResp[lenResp.size - 4].toInt() and 0xFF) shl 8) or
                (lenResp[lenResp.size - 3].toInt() and 0xFF)
            if (ndefLen <= 0 || ndefLen > 4096) return null

            val out = ByteArray(ndefLen)
            var done = 0
            while (done < ndefLen) {
                val want = minOf(MAX_READ, ndefLen - done)
                val off = NDEF_OFFSET + done
                val cmd = byteArrayOf(
                    0x00, 0xB0.toByte(),
                    ((off shr 8) and 0x7F).toByte(), (off and 0xFF).toByte(),
                    want.toByte(),
                )
                val resp = it.transceive(cmd)
                if (!isOk(resp) || resp.size < 3) break
                val chunk = resp.copyOfRange(0, resp.size - 2)
                if (chunk.isEmpty()) break
                System.arraycopy(chunk, 0, out, done, minOf(chunk.size, ndefLen - done))
                done += chunk.size
            }
            if (done < ndefLen) return null
            val msg = NdefMessage(out)
            return msg.records.firstNotNullOfOrNull { rec ->
                rec.uri?.let { uri ->
                    PairingToken.parse(uri)?.takeIf { PairingToken.isFresh(it) }
                }
            }
        }
    }

    private fun buildSelect(aid: ByteArray): ByteArray {
        val out = ByteArray(5 + aid.size + 1)
        out[0] = 0x00; out[1] = 0xA4.toByte(); out[2] = 0x04; out[3] = 0x00
        out[4] = aid.size.toByte()
        System.arraycopy(aid, 0, out, 5, aid.size)
        out[out.size - 1] = 0x00 // Ne
        return out
    }

    private fun isOk(resp: ByteArray): Boolean =
        resp.size >= 2 &&
            resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00

    private inline fun IsoDep.use(block: (IsoDep) -> PairingToken?): PairingToken? {
        try {
            return block(this)
        } finally {
            runCatching { close() }
        }
    }

    private companion object {
        const val TAG = "NFC"
        const val MAX_READ = 240

        /** El archivo NDEF de un tag Tipo-4 arranca con 2 bytes de longitud. */
        const val NDEF_OFFSET = 2
    }
}
