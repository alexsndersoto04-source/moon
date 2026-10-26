package com.moonshare.net

import android.content.Context
import android.net.Uri
import com.moonshare.core.MoonLog
import com.moonshare.data.model.FileItem
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.encodeToString

/**
 * Lado emisor. Abre un socket contra el Group Owner (192.168.49.1 siempre, porque
 * el receptor es quien levanta el grupo), negocia qué va a pasar y vomita los bytes.
 */
class SendEndpoint(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val deviceName: String,
    private val deviceId: String,
    private val listener: EndpointListener,
) {

    @Volatile
    var canceled = false
        private set

    private var socket: Socket? = null

    fun cancel() {
        canceled = true
        runCatching { socket?.close() } // despierta al write bloqueado
    }

    /** @return null si fue todo OK, o el motivo del fallo. */
    fun send(items: List<FileItem>): String? {
        if (items.isEmpty()) return "nada para enviar"
        val s = try {
            Socket().apply {
                tcpNoDelay = true
                sendBufferSize = 1 shl 20
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            MoonLog.e(TAG, "no se pudo conectar a $host:$port", e)
            return "No se pudo conectar al otro teléfono (${e.message})"
        }
        socket = s

        try {
            s.tcpNoDelay = true
            val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            val output = DataOutputStream(s.getOutputStream().buffered(1 shl 16))

            // 1) Hello
            output.write(
                MoonProtocol.encode(
                    MoonProtocol.Type.HELLO,
                    MoonProtocol.json.encodeToString(MoonProtocol.Hello(deviceName, deviceId)
                    )
                )
            )
            output.flush()
            val ack = MoonProtocol.readFrame(input)
            if (ack.type != MoonProtocol.Type.HELLO_ACK) {
                return "El otro teléfono no habla MoonShare"
            }
            ack.read<MoonProtocol.Hello>()?.let { listener.onPeerIdentified(it.name, it.deviceId) }

            // 2) Oferta
            val offer = MoonProtocol.Offer(
                items = items.map { MoonProtocol.OfferItem(it.id, it.name, it.size, it.mime) },
                totalBytes = items.sumOf { it.size },
                fromName = deviceName,
            )
            output.write(
                MoonProtocol.encode(
                    MoonProtocol.Type.OFFER,
                    MoonProtocol.json.encodeToString(offer)
                )
            )
            output.flush()

            // 3) Decisión del receptor
            val decision = MoonProtocol.readFrame(input)
            if (decision.type == MoonProtocol.Type.DECLINE) {
                items.forEach { listener.onFileFinished(it.id, ok = false, error = "rejected") }
                return null // no es un error de red: el usuario del otro lado dijo que no
            }
            if (decision.type != MoonProtocol.Type.ACCEPT) {
                return "Respuesta inesperada del receptor"
            }
            val accepted = decision.read<MoonProtocol.Decision>()?.ids?.toSet() ?: emptySet()
            if (accepted.isEmpty()) {
                items.forEach { listener.onFileFinished(it.id, ok = false, error = "rejected") }
                return null
            }
            val queue = items.filter { it.id in accepted }

            // 4) Uno por uno
            var streamId = 1
            queue.forEachIndexed { index, item ->
                if (canceled) return@forEachIndexed
                streamId++
                sendOne(input, output, item, streamId, index + 1, queue.size)
            }

            runCatching {
                output.write(MoonProtocol.encode(MoonProtocol.Type.CLOSE, ByteArray(0)))
                output.flush()
            }
            return if (canceled) "cancelado" else null
        } catch (e: Exception) {
            MoonLog.e(TAG, "envío interrumpido", e)
            return e.message ?: "se cortó la conexión"
        } finally {
            runCatching { s.close() }
            socket = null
            listener.onSessionClosed()
        }
    }

    private fun sendOne(
        input: DataInputStream,
        output: DataOutputStream,
        item: FileItem,
        streamId: Int,
        index: Int,
        total: Int,
    ) {
        val begin = MoonProtocol.Begin(
            id = item.id,
            streamId = streamId,
            name = item.name,
            size = item.size,
            mime = item.mime,
            index = index,
            total = total,
        )
        output.write(
            MoonProtocol.encode(
                MoonProtocol.Type.BEGIN,
                MoonProtocol.json.encodeToString(begin)
            )
        )
        output.flush()

        var sent = 0L
        val startedAt = System.nanoTime()
        var lastEmit = 0L
        val buf = ByteArray(MoonProtocol.CHUNK_SIZE)

        try {
            context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { src ->
                while (true) {
                    if (canceled) break
                    val n = src.read(buf)
                    if (n <= 0) break
                    output.write(MoonProtocol.encodeChunk(streamId, buf, 0, n))
                    sent += n
                    pace(sent, startedAt)

                    val now = System.nanoTime()
                    if (now - lastEmit > 60_000_000) {
                        lastEmit = now
                        val elapsed = (now - startedAt).coerceAtLeast(1) / 1_000_000_000.0
                        listener.onProgress(item.id, sent, item.size, (sent / elapsed).toLong())
                    }
                }
            } ?: throw IllegalStateException("no se pudo abrir ${item.uri}")

            if (canceled) {
                output.write(
                    MoonProtocol.encode(
                        MoonProtocol.Type.CANCEL,
                        MoonProtocol.json.encodeToString(MoonProtocol.End(item.id, streamId, sent, ok = false))
                    )
                )
                output.flush()
                listener.onFileFinished(item.id, ok = false, error = "cancelado")
                return
            }

            output.write(
                MoonProtocol.encode(
                    MoonProtocol.Type.END,
                    MoonProtocol.json.encodeToString(MoonProtocol.End(item.id, streamId, sent)
                    )
                )
            )
            output.flush()

            // El receptor confirma que lo guardó: sin esto marcaríamos "ok" antes de tiempo.
            val confirm = try {
                MoonProtocol.readFrame(input)
            } catch (e: Exception) {
                listener.onFileFinished(item.id, ok = false, error = "sin confirmación")
                return
            }
            if (confirm.type == MoonProtocol.Type.END) {
                listener.onFileFinished(item.id, ok = true)
            } else {
                listener.onFileFinished(item.id, ok = false, error = "el receptor rechazó el archivo")
            }
        } catch (e: Exception) {
            MoonLog.e(TAG, "leyendo ${item.name}", e)
            listener.onFileFinished(item.id, ok = false, error = e.message ?: "IO")
        }
    }

    /**
     * Límite de velocidad (Ajustes → Avanzado). Se hace a nivel de escritura, que
     * es donde se puede frenar sin descoser el stream. Con 0 no se aplica.
     */
    private fun pace(sent: Long, startedAt: Long) {
        val limitKbps = speedLimitKbps
        if (limitKbps <= 0) return
        val bytesPerSec = limitKbps * 125L          // kilobits -> bytes
        val targetMs = sent * 1000 / bytesPerSec
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val wait = targetMs - elapsedMs
        // se duerme poco y seguido: un sleep largo congelaría la UI de progreso
        if (wait > 2) runCatching { Thread.sleep(wait.coerceAtMost(60)) }
    }

    /** Se inyecta desde el engine para que el endpoint no dependa de DataStore. */
    var speedLimitKbps: Int = 0

    private companion object {
        const val TAG = "Send"
        const val CONNECT_TIMEOUT_MS = 12_000

        /**
         * Tiene que superar el timeout de consentimiento del receptor (default 120 s)
         * o el emisor se rinde mientras el otro todavía está decidiendo.
         */
        const val READ_TIMEOUT_MS = 150_000
    }
}
