package com.moonshare.nfc

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation

/** Chequeo de hardware/estado de NFC en un solo lugar (muchos Redmi no traen NFC). */
object NfcAvailability {

    fun has(context: Context): Boolean =
        runCatching { NfcAdapter.getDefaultAdapter(context) != null }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean =
        runCatching { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true }.getOrDefault(false)

    /**
     * Si la ROM soporta HCE (host card emulation). Sin esto el "chocar teléfonos"
     * no se puede hacer y la app cae al pareo por lista de dispositivos.
     */
    fun canEmulateCards(context: Context): Boolean {
        if (!has(context)) return false
        // NFC_SERVICE devuelve NfcManager, NO NfcAdapter: el cast directo es lo que
        // dice Lint (ServiceCast) y en ROMs que devuelven el manager puro revienta con
        // ClassCastException. Se pide el adapter por la vía correcta, con fallback.
        // NFC_SERVICE devuelve NfcManager. El cast directo a NfcAdapter que estaba aca
        // era ClassCastException en ROMs estrictas y ademas Lint lo marca como error:
        // se usa la via publica, y `defaultAdapter` puede venir null si el usuario
        // apag6 la NFC (eso no es un fallo, es el estado real del equipo).
        val adapter = runCatching {
            (context.getSystemService(Context.NFC_SERVICE) as? android.nfc.NfcManager)?.defaultAdapter
        }.getOrNull() ?: return false
        return runCatching {
            CardEmulation.getInstance(adapter).isDefaultServiceForCategory(
                android.content.ComponentName(context, MoonCardService::class.java),
                CardEmulation.CATEGORY_OTHER,
            )
        }.getOrDefault(false)
    }

    sealed class Status {
        data object Ready : Status()
        data object Off : Status()
        data object NoHardware : Status()

        companion object {
            fun of(context: Context): Status = when {
                !has(context) -> NoHardware
                !isEnabled(context) -> Off
                else -> Ready
            }
        }
    }

    fun describe(context: Context): String = when (Status.of(context)) {
        Status.Ready -> "NFC listo: acercá los dos teléfonos"
        Status.Off -> "NFC apagado — encendela en Ajustes rápidos"
        Status.NoHardware -> "Este teléfono no tiene NFC: se puede parear igual desde la lista"
    }
}
