package com.moonshare.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.moonshare.core.MoonLog

/**
 * El teléfono que va a RECIBIR se disfraza de tag NDEF (NFC Forum Type 4) con la
 * emulación HCE de Android. El otro teléfono, al acercar, lo lee como un tag
 * cualquiera y obtiene la dirección Wi-Fi Direct del receptor.
 *
 * Resultado: ni lista de dispositivos, ni "¿aceitás conectar con…?", ni PIN.
 * Un golpe. Y como usamos la AID estándar de NDEF (D2760000850101) no hace falta
 * que el lector tenga MoonShare instalada para que el sistema la enrute.
 */
class MoonCardService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val apdu = Apdu.parse(commandApdu) ?: return SW_WRONG_LENGTH
        val payload = apdu.data

        when {
            apdu.isSelectByAid() -> {
                selected = if (apdu.dataStartsWith(NDEF_AID)) FILE_NDEF else FILE_NONE
                MoonLog.d(TAG, "select AID → ${if (selected == FILE_NDEF) "ok" else "no-nuestra"}")
                return if (selected == FILE_NDEF) SW_OK else SW_NOT_FOUND
            }

            apdu.isSelectFile() -> {
                selected = when {
                    payload == null || payload.size < 2 -> FILE_NONE
                    payload[0] == 0xE1.toByte() && payload[1] == 0x03.toByte() -> FILE_CC
                    payload[0] == 0xE1.toByte() && payload[1] == 0x04.toByte() -> FILE_NDEF
                    else -> FILE_NONE
                }
                return if (selected == FILE_NONE) SW_NOT_FOUND else SW_OK
            }

            apdu.ins == INS_READ -> {
                val source = when (selected) {
                    FILE_CC -> CC_FILE
                    else -> ndefFile()
                }
                // READ BINARY usa un offset de 15 bits (P1[0..6] << 8 | P2)
                val offset = ((apdu.p1 and 0x7F) shl 8) or (apdu.p2 and 0xFF)
                val want = if (apdu.ne <= 0 || apdu.ne > MAX_RESP) MAX_RESP else apdu.ne
                if (offset >= source.size) return SW_END_OF_FILE
                val end = minOf(source.size, offset + want)
                return source.copyOfRange(offset, end) + SW_OK
            }

            // UPDATE BINARY: el tag es de solo lectura; se responde OK sin guardar,
            // así un lector que intenta escribir no queda colgado en un error.
            apdu.ins == INS_UPDATE -> SW_OK

            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private var selected = FILE_NDEF

    /** [NLEN][NDEF message] — el formato del archivo NDEF de un tag Type 4. */
    private fun ndefFile(): ByteArray {
        val token = PairingTokenHolder.current ?: return EMPTY
        return try {
            val message = NdefMessage(arrayOf(NdefRecord.createUri(token.toUriString())))
            val body = message.toByteArray()
            byteArrayOf(
                ((body.size shr 8) and 0xFF).toByte(),
                (body.size and 0xFF).toByte(),
            ) + body
        } catch (e: Exception) {
            MoonLog.w(TAG, "NDEF inválido, sirvo vacío", e)
            EMPTY
        }
    }

    override fun onDeactivated(reason: Int) {
        MoonLog.d(TAG, "tag desactivado reason=$reason")
    }

    companion object {
        private const val TAG = "HCE"
        private const val INS_READ = 0xB0    // READ BINARY (ISO/IEC 7816-4)
        private const val INS_UPDATE = 0xD6  // UPDATE BINARY
        private const val MAX_RESP = 244

        private const val FILE_NONE = 0
        private const val FILE_CC = 1
        private const val FILE_NDEF = 2

        val NDEF_AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
        )

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_END_OF_FILE = byteArrayOf(0x6A.toByte(), 0x86.toByte())

        private val EMPTY = byteArrayOf(0x00, 0x00)

        /** Capability Container canónico (15 bytes) del Type 4 Tag, mapping v2.0. */
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,                                     // longitud del CC
            0x20,                                             // versión de mapeo 2.0
            0x00, 0x2B.toByte(),                              // MLe
            0x00, 0x21.toByte(),                              // MLc
            0x01, 0xFF.toByte(), 0x00,                        // TLV del archivo NDEF
            0x00, 0x00, 0x00, 0x00,
        )
    }
}

/**
 * APDU mínimo: [CLA][INS][P1][P2][Lc][data][Ne]. Suficiente para SELECT/READ.
 * Escrito a mano en vez de usar clases de `android.nfc` porque esas son de solo
 * lectura para el lado del lector.
 */
class Apdu private constructor(
    val cla: Int, val ins: Int, val p1: Int, val p2: Int,
    val data: ByteArray?, val ne: Int,
) {
    fun isSelectByAid() = cla == 0 && ins == INS_SELECT && p1 == 0x04
    fun isSelectFile() = cla == 0 && ins == INS_SELECT && (p1 == 0x00 || p1 == 0x0C)
    fun dataStartsWith(prefix: ByteArray): Boolean {
        val d = data ?: return false
        if (d.size < prefix.size) return false
        for (i in prefix.indices) if (d[i] != prefix[i]) return false
        return true
    }

    companion object {
        private const val INS_SELECT = 0xA4

        fun parse(b: ByteArray): Apdu? {
            if (b.size < 4) return null
            // Cabecera fija de 4 bytes; el resto depende de Lc/Ne.
            val cla = b[0].toInt() and 0xFF
            val ins = b[1].toInt() and 0xFF
            val p1 = b[2].toInt() and 0xFF
            val p2 = b[3].toInt() and 0xFF
            var lc = 0
            var offset = 4
            var ne = 0
            if (b.size > 4) {
                if (b.size == 5) {
                    ne = b[4].toInt() and 0xFF
                } else {
                    lc = b[4].toInt() and 0xFF
                    if (lc == 0 && b.size >= 6) { // extended length
                        lc = ((b[5].toInt() and 0xFF) shl 8) or (b[6].toInt() and 0xFF)
                        offset = 7
                    } else {
                        offset = 5
                    }
                    if (offset + lc > b.size) return null
                    ne = if (b.size > offset + lc) b[b.size - 1].toInt() and 0xFF else 0
                }
            }
            val data = if (lc > 0) b.copyOfRange(offset, offset + lc) else null
            return Apdu(cla, ins, p1, p2, data, ne)
        }
    }
}

/**
 * Puente entre el [com.moonshare.transfer.TransferEngine] (que decide cuándo se está
 * "abierto a recibir") y el servicio HCE, que el sistema instancia por su cuenta
 * y no se puede inyectar. Volátil: el lector puede picar en cualquier momento.
 */
object PairingTokenHolder {

    @Volatile
    var current: PairingToken? = null
        private set

    val isAdvertising: Boolean get() = current != null

    fun publish(token: PairingToken?) {
        current = token
        MoonLog.d("HCE", if (token == null) "dejo de anunciar" else "anunciando ${token.deviceAddress}")
    }
}
