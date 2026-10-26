package com.moonshare.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protocolo MoonShare.
 *
 * Un solo canal TCP full-duplex entre los dos teléfonos. Ambos lados pueden
 * escribir en la misma conexión (el write-lock lo serializa), así que un
 * intercambio "ida y vuelta" no necesita un segundo socket.
 *
 *   [ int32 length ][ byte type ][ payload ]
 *
 * `length` cuenta `type` + payload. Los tipos 0x10..0x1F llevan binario crudo,
 * el resto llevan UTF-8 (JSON). Nada de HTTP: menos superficie, menos latencia,
 * cero dependencia de que el otro lado tenga un server HTTP compatible.
 */
object MoonProtocol {

    const val VERSION = 1
    const val MAGIC = "MOON"

    object Type {
        const val HELLO: Byte = 1
        const val HELLO_ACK: Byte = 2
        const val OFFER: Byte = 3
        const val ACCEPT: Byte = 4
        const val DECLINE: Byte = 5
        const val BEGIN: Byte = 6
        const val CHUNK: Byte = 7       // binario: [int32 streamId][bytes]
        const val END: Byte = 8
        const val CANCEL: Byte = 9
        const val ERROR: Byte = 10
        const val PING: Byte = 11
        const val PONG: Byte = 12
        const val CLOSE: Byte = 13
    }

    /** Máximo payload de un CHUNK. 512 KiB equilibra syscall overhead vs RAM. */
    const val CHUNK_SIZE = 512 * 1024

    /** Tamaño máximo de un frame de encabezado JSON (defensa ante buffers locos). */
    const val MAX_HEADER_BYTES = 64 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ------------------------------------------------------------------ DTOs
    @Serializable
    data class Hello(
        @SerialName("n") val name: String,
        @SerialName("id") val deviceId: String,
        @SerialName("v") val version: Int = VERSION,
        @SerialName("m") val maxParallel: Int = 1,
    )

    @Serializable
    data class OfferItem(
        @SerialName("id") val id: String,
        @SerialName("n") val name: String,
        @SerialName("s") val size: Long,
        @SerialName("t") val mime: String,
    )

    @Serializable
    data class Offer(
        @SerialName("items") val items: List<OfferItem>,
        @SerialName("total") val totalBytes: Long,
        @SerialName("from") val fromName: String = "",
    )

    @Serializable
    data class Decision(
        @SerialName("ids") val ids: List<String>,
        @SerialName("why") val why: String? = null,
    )

    @Serializable
    data class Begin(
        @SerialName("id") val id: String,
        @SerialName("sid") val streamId: Int,
        @SerialName("n") val name: String,
        @SerialName("s") val size: Long,
        @SerialName("t") val mime: String,
        @SerialName("i") val index: Int,
        @SerialName("of") val total: Int,
    )

    @Serializable
    data class End(
        @SerialName("id") val id: String,
        @SerialName("sid") val streamId: Int,
        @SerialName("s") val bytes: Long,
        @SerialName("h") val sha256: String? = null,
        @SerialName("ok") val ok: Boolean = true,
    )

    @Serializable
    data class ErrorMsg(@SerialName("m") val message: String)

    // ------------------------------------------------------------------ codec
    fun encode(type: Byte, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(5 + payload.size)
        buf.putInt(1 + payload.size)
        buf.put(type)
        buf.put(payload)
        return buf.array()
    }

    fun encode(type: Byte, json: String): ByteArray =
        encode(type, json.toByteArray(Charsets.UTF_8))

    fun encodeChunk(streamId: Int, data: ByteArray, offset: Int, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(5 + 4 + len)
        buf.putInt(1 + 4 + len)
        buf.put(Type.CHUNK)
        buf.putInt(streamId)
        buf.put(data, offset, len)
        return buf.array()
    }

    class Incoming(val type: Byte, val payload: ByteArray) {

        fun json(): String = String(payload, Charsets.UTF_8)

        inline fun <reified T> read(): T? = try {
            MoonProtocol.json.decodeFromString<T>(json())
        } catch (_: Exception) {
            null
        }

        /** Para CHUNK: devuelve [streamId, datos] o null si está mal formado. */
        fun chunk(): Pair<Int, ByteArray>? {
            if (payload.size < 4) return null
            val sid = ByteBuffer.wrap(payload, 0, 4).int
            return sid to payload.copyOfRange(4, payload.size)
        }
    }

    fun readFrame(input: DataInputStream): Incoming {
        val len = try {
            input.readInt()
        } catch (eof: java.io.EOFException) {
            throw EOF("cerrado por el otro lado")
        }
        if (len < 1 || len > MAX_HEADER_BYTES + CHUNK_SIZE + 64) {
            throw Protocol("tamaño de frame inválido: $len")
        }
        val body = ByteArray(len)
        input.readFully(body)
        return Incoming(body[0], body.copyOfRange(1, body.size))
    }

    fun writeFrame(output: DataOutputStream, frame: ByteArray) {
        output.write(frame)
    }

    class Protocol(message: String) : IOException(message)
    class EOF(message: String) : IOException(message)
}
