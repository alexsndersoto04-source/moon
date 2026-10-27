package com.moonshare

import com.moonshare.net.MoonProtocol
import com.moonshare.nfc.PairingToken
import com.moonshare.nfc.PairingTokenHolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.serialization.encodeToString
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
    fun elProtocoloConservaNonceDeToqueYHashDelArchivo() {
        val hello = MoonProtocol.Hello(
            name = "Emisor",
            deviceId = "device-1",
            tapNonce = "nonce-nfc",
        )
        val end = MoonProtocol.End(
            id = "file-1",
            streamId = 2,
            bytes = 1234L,
            sha256 = "abc123",
            ok = true,
        )
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        MoonProtocol.writeFrame(
            output,
            MoonProtocol.encode(MoonProtocol.Type.HELLO, MoonProtocol.json.encodeToString(hello)),
        )
        MoonProtocol.writeFrame(
            output,
            MoonProtocol.encode(MoonProtocol.Type.END, MoonProtocol.json.encodeToString(end)),
        )
        output.flush()

        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val helloFrame = MoonProtocol.readFrame(input)
        val endFrame = MoonProtocol.readFrame(input)

        assertEquals(MoonProtocol.Type.HELLO, helloFrame.type)
        assertEquals("nonce-nfc", helloFrame.read<MoonProtocol.Hello>()?.tapNonce)
        assertEquals(MoonProtocol.Type.END, endFrame.type)
        assertEquals("abc123", endFrame.read<MoonProtocol.End>()?.sha256)
        assertEquals(1234L, endFrame.read<MoonProtocol.End>()?.bytes)
    }

    @Test
    fun losChunksConservanStreamYContenido() {
        val payload = "MoonShare".toByteArray()
        val frameBytes = MoonProtocol.encodeChunk(streamId = 7, data = payload, offset = 0, len = payload.size)
        val input = DataInputStream(ByteArrayInputStream(frameBytes))

        val frame = MoonProtocol.readFrame(input)
        val chunk = frame.chunk()

        assertEquals(MoonProtocol.Type.CHUNK, frame.type)
        assertEquals(7, chunk?.first)
        assertEquals("MoonShare", chunk?.second?.let { String(it) })
    }
}
