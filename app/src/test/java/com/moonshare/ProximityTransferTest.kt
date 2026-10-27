package com.moonshare

import com.moonshare.net.nearby.NearbyTransport
import com.moonshare.nfc.PairingToken
import com.moonshare.nfc.PairingTokenHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regresiones del flujo "elegir, acercar y que pase solo". Son pruebas de la
 * lógica que sostiene esa promesa, no una simulación de una transferencia:
 * el enlace NFC/Wi-Fi Direct real solo puede validarse con dos teléfonos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProximityTransferTest {

    @Test
    fun elTokenServidoPorNfcSeRenuevaSinCambiarSuIdentidad() {
        val original = PairingToken(
            deviceAddress = "02:00:00:00:00:01",
            deviceName = "Teléfono receptor",
            port = 41795,
            issuedAt = 1_000L,
            nonce = "nonce-de-esta-disponibilidad",
        )

        val refreshed = original.refreshed(now = 999_999L)

        assertEquals(999_999L, refreshed.issuedAt)
        assertEquals(original.deviceAddress, refreshed.deviceAddress)
        assertEquals(original.nonce, refreshed.nonce)
        assertTrue(PairingToken.isFresh(refreshed.copy(issuedAt = System.currentTimeMillis())))
    }

    @Test
    fun soloElToqueRecienteConElNonceCorrectoAutorizaSinPreguntar() {
        val token = PairingToken(
            deviceAddress = "02:00:00:00:00:02",
            deviceName = "Receptor",
            nonce = "secreto-efimero",
        )
        PairingTokenHolder.publish(token)
        try {
            assertFalse(PairingTokenHolder.hasFreshTapConsent(token.nonce))

            PairingTokenHolder.markServed()
            val now = System.currentTimeMillis()
            assertTrue(PairingTokenHolder.hasFreshTapConsent(token.nonce, now + 1_000L))
            assertFalse(PairingTokenHolder.hasFreshTapConsent("otro-nonce", now + 1_000L))
            assertFalse(PairingTokenHolder.hasFreshTapConsent(token.nonce, now + 120_000L))
            assertFalse(PairingTokenHolder.hasFreshTapConsent("", now))
        } finally {
            PairingTokenHolder.publish(null)
        }
    }

    @Test
    fun laIdentidadNearbyConservaNombreYNonceDelToque() {
        val encoded = NearbyTransport.encodeEndpointInfo("Teléfono de Ana", "nonce-nfc")
        val (name, nonce) = NearbyTransport.decodeEndpointInfo(encoded)

        assertEquals("Teléfono de Ana", name)
        assertEquals("nonce-nfc", nonce)
    }

}
