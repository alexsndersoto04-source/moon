package com.moonshare.net

import android.content.Context
import com.moonshare.core.MoonLog
import com.moonshare.data.model.TransferTask
import com.moonshare.util.SaveSink
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/**
 * Lado receptor. Sabe a un [ServerSocket] en la interfaz Wi-Fi Direct y escribe lo
 * que entra directo contra [SaveSink], de a un archivo por vez.
 *
 * Se mantiene deliberadamente simple: un socket a la vez. Dos sesiones simultáneas
 * de entrada no aportan nada y sí pelean por la misma antena.
 */
class ReceiveEndpoint(
    private val context: Context,
    private val port: Int,
    private val saveToGallery: Boolean,
    private val listener: EndpointListener,
) {

    private var server: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        worker = Thread({ acceptLoop() }, "MoonShare-recv").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        worker?.interrupt()
        worker = null
    }

    private fun acceptLoop() {
        val srv = try {
            // reuse: si la app se reinicia rápido, el puerto queda en TIME_WAIT
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
        } catch (e: Exception) {
            MoonLog.e(TAG, "no se pudo escuchar en :$port", e)
            listener.onSessionClosed("No se pudo abrir el puerto $port")
            running = false
            return
        }
        server = srv
        MoonLog.i(TAG, "escuchando en :$port")

        while (running) {
            val socket = try {
                srv.accept()
            } catch (e: Exception) {
                if (running) MoonLog.w(TAG, "accept: ${e.message}")
                break
            }
            try {
                handle(socket)
            } catch (e: Exception) {
                if (e is MoonProtocol.EOF) {
                    MoonLog.i(TAG, "el emisor cerró la conexión")
                } else {
                    MoonLog.e(TAG, "sesión caída", e)
                    listener.onSessionClosed(e.message ?: "error de red")
                }
            } finally {
                runCatching { socket.close() }
            }
        }
        runCatching { srv.close() }
        running = false
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.receiveBufferSize = 1 shl 20
        val input = DataInputStream(socket.getInputStream().buffered(1 shl 16))
        val output = DataOutputStream(socket.getOutputStream().buffered(1 shl 14))

        // 1) Handshake
        val hello = MoonProtocol.readFrame(input).read<MoonProtocol.Hello>()
            ?: error("HELLO inválido")
        listener.onPeerIdentified(hello.name, hello.deviceId)
        output.write(
            MoonProtocol.encode(
                MoonProtocol.Type.HELLO_ACK,
                MoonProtocol.json.encodeToString(MoonProtocol.Hello(name = deviceName(), deviceId = deviceId())
                )
            )
        )
        output.flush()

        // 2) Oferta de archivos
        val offerFrame = MoonProtocol.readFrame(input)
        if (offerFrame.type != MoonProtocol.Type.OFFER) {
            error("esperaba OFFER, llegó ${offerFrame.type}")
        }
        val offer = offerFrame.read<MoonProtocol.Offer>() ?: error("OFFER inválida")
        val tasks = offer.items.map { item ->
            TransferTask(
                itemId = item.id,
                name = item.name,
                size = item.size,
                mime = item.mime,
                uri = "", // lo recibe por el stream, no le sirve una uri de otro teléfono
            )
        }
        tasks.forEach { listener.onTaskQueued(it) }

        // 3) Consentimiento del usuario (o auto-accept)
        val accepted: List<String> = runBlocking {
            listener.awaitConsent(
                fromName = offer.fromName.ifBlank { hello.name },
                tasks = tasks,
                timeoutMs = listener.consentTimeoutMs(),
            )
        }
        val acceptedSet = accepted.toSet()
        if (acceptedSet.isEmpty()) {
            output.write(
                MoonProtocol.encode(
                    MoonProtocol.Type.DECLINE,
                    MoonProtocol.json.encodeToString(MoonProtocol.Decision(emptyList(), "rechazado por el usuario"),
                    )
                )
            )
            output.flush()
            tasks.forEach { listener.onFileFinished(it.itemId, ok = false, error = "rejected") }
            listener.onSessionClosed()
            return
        }
        output.write(
            MoonProtocol.encode(
                MoonProtocol.Type.ACCEPT,
                MoonProtocol.json.encodeToString(MoonProtocol.Decision(accepted)
                )
            )
        )
        output.flush()

        // 4) Stream de cada archivo aceptado
        var receivedCount = 0
        val total = acceptedSet.size
        while (running) {
            val frame = try {
                MoonProtocol.readFrame(input)
            } catch (eof: MoonProtocol.EOF) {
                break
            } catch (timeout: SocketTimeoutException) {
                MoonLog.w(TAG, "timeout esperando BEGIN")
                break
            } catch (e: Exception) {
                MoonLog.w(TAG, "leyendo: ${e.message}")
                break
            }

            when (frame.type) {
                MoonProtocol.Type.BEGIN -> {
                    val begin = frame.read<MoonProtocol.Begin>() ?: continue
                    if (begin.id !in acceptedSet) {
                        // El emisor insiste con un ítem no aceptado: se lo decimos y seguimos.
                        drainToChunkEnd(input)
                        continue
                    }
                    receivedCount = receiveOne(input, output, begin, receivedCount, total)
                }

                MoonProtocol.Type.CLOSE -> break
                MoonProtocol.Type.PING -> {
                    output.write(MoonProtocol.encode(MoonProtocol.Type.PONG, ByteArray(0)))
                    output.flush()
                }

                else -> MoonLog.d(TAG, "frame ignorado type=${frame.type}")
            }
        }

        runCatching {
            output.write(MoonProtocol.encode(MoonProtocol.Type.CLOSE, ByteArray(0)))
            output.flush()
        }
        listener.onSessionClosed()
    }

    private fun receiveOne(
        input: DataInputStream,
        output: DataOutputStream,
        begin: MoonProtocol.Begin,
        already: Int,
        total: Int,
    ): Int {
        MoonLog.i(TAG, "entrando ${begin.name} (${MoonLog.human(begin.size)}) [$already/$total]")
        val opened = SaveSink.open(context, begin.name, begin.mime, begin.size, saveToGallery)
        var written = 0L
        var ok = true
        var error: String? = null
        val startedAt = System.nanoTime()
        var lastEmit = 0L

        try {
            SaveSink.streamFor(context, opened).use { raw ->
                val out = raw.buffered(1 shl 16)
                while (true) {
                    val frame = MoonProtocol.readFrame(input)
                    when (frame.type) {
                        MoonProtocol.Type.CHUNK -> {
                            val (_, data) = frame.chunk() ?: continue
                            out.write(data)
                            written += data.size
                            val now = System.nanoTime()
                            if (now - lastEmit > 60_000_000) { // ~16 Hz, no más
                                lastEmit = now
                                val elapsed = (now - startedAt).coerceAtLeast(1) / 1_000_000_000.0
                                listener.onProgress(
                                    begin.id, written, begin.size,
                                    (written / elapsed).toLong(),
                                )
                            }
                        }

                        MoonProtocol.Type.END -> {
                            out.flush()
                            val end = frame.read<MoonProtocol.End>()
                            if (end != null && end.bytes in 1 until written) {
                                MoonLog.w(TAG, "tamaño declarado ${end.bytes} < recibido $written")
                            }
                            SaveSink.publish(context, opened, written)
                            listener.onFileFinished(begin.id, ok = true)
                            ack(output, begin, written)
                            return already + 1
                        }

                        MoonProtocol.Type.CANCEL -> {
                            ok = false
                            error = "cancelado por el emisor"
                            out.flush()
                            SaveSink.discard(context, opened)
                            ack(output, begin, written)
                            listener.onFileFinished(begin.id, ok = false, error = error)
                            return already + 1
                        }

                        MoonProtocol.Type.CLOSE -> throw MoonProtocol.EOF("cerrado")
                        else -> Unit
                    }
                }
            }
            // el stream se cortó sin END: el archivo queda incompleto
            ok = false
            error = "flujo cortado"
        } catch (e: Exception) {
            ok = false
            error = e.message ?: "fallo de escritura"
            SaveSink.discard(context, opened)
            MoonLog.e(TAG, "escribiendo ${begin.name}", e)
            listener.onFileFinished(begin.id, ok = false, error = error)
        }
        runCatching { ack(output, begin, written) }
        return already + (if (ok) 1 else 0)
    }

    private fun ack(output: DataOutputStream, begin: MoonProtocol.Begin, written: Long) {
        output.write(
            MoonProtocol.encode(
                MoonProtocol.Type.END,
                MoonProtocol.json.encodeToString(MoonProtocol.End(id = begin.id, streamId = begin.streamId, bytes = written)
                )
            )
        )
        output.flush()
    }

    /** Descarta los CHUNK de un ítem no aceptado hasta su END, para no desincronizar el stream. */
    private fun drainToChunkEnd(input: DataInputStream) {
        while (true) {
            val f = try {
                MoonProtocol.readFrame(input)
            } catch (e: Exception) {
                return
            }
            if (f.type == MoonProtocol.Type.END || f.type == MoonProtocol.Type.CLOSE) return
        }
    }

    private fun deviceName(): String = runCatching {
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL
    }.getOrDefault("Moon")

    private fun deviceId(): String = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "moon"
    }.getOrDefault("moon")

    private companion object {
        const val TAG = "Recv"
    }
}
