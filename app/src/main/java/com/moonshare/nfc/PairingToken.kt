package com.moonshare.nfc

import android.net.Uri
import com.moonshare.data.settings.MoonSettings

/**
 * El token que viaja en el golpe de NFC.
 *
 * Ojo con el tamaño: una tarjeta HCE por NFC-A lleva ~1 KB y el payload útil por
 * debajo de eso, así que el token es compacto a propósito. No viaja ningún archivo
 * por NFC (imposible: serían 100 KB/s como mucho); solo viajan 4 campos que
 * convierten un pareo de 6 toques en 0 toques.
 */
data class PairingToken(
    val version: Int = V,
    val deviceAddress: String,
    val deviceName: String,
    val port: Int = MoonSettings.DEFAULT_PORT,
    val issuedAt: Long = System.currentTimeMillis(),
    val nonce: String = "",
) {
    /** Copia con marca temporal nueva: un token anunciado durante horas no debe vencer mientras el receptor sigue disponible. */
    fun refreshed(now: Long = System.currentTimeMillis()): PairingToken = copy(issuedAt = now)

    fun toUriString(): String = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendQueryParameter("v", version.toString())
        .appendQueryParameter("a", deviceAddress)
        .appendQueryParameter("n", deviceName)
        .appendQueryParameter("p", port.toString())
        .appendQueryParameter("t", issuedAt.toString())
        .apply { if (nonce.isNotEmpty()) appendQueryParameter("k", nonce) }
        .build()
        .toString()

    companion object {
        const val V = 1
        const val SCHEME = "moonshare"
        const val HOST = "pair"

        /** Tiempo de vida del token: más viejo que esto, se ignora (evita pareos fantasma). */
        const val MAX_AGE_MS = 5 * 60 * 1000L

        fun parse(uri: String): PairingToken? = parse(Uri.parse(uri))

        fun parse(uri: Uri?): PairingToken? {
            if (uri == null) return null
            if (uri.scheme != SCHEME || uri.host != HOST) return null
            val addr = uri.getQueryParameter("a")?.takeIf { it.isNotBlank() } ?: return null
            val version = uri.getQueryParameter("v")?.toIntOrNull() ?: 0
            if (version < 1) return null
            return PairingToken(
                version = version,
                deviceAddress = addr,
                deviceName = uri.getQueryParameter("n").orEmpty(),
                port = uri.getQueryParameter("p")?.toIntOrNull() ?: MoonSettings.DEFAULT_PORT,
                issuedAt = uri.getQueryParameter("t")?.toLongOrNull() ?: 0L,
                nonce = uri.getQueryParameter("k").orEmpty(),
            )
        }

        fun isFresh(token: PairingToken): Boolean {
            if (token.issuedAt <= 0L) return true
            return System.currentTimeMillis() - token.issuedAt <= MAX_AGE_MS
        }
    }
}
