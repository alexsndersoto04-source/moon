package com.airgesture.transfer.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.airgesture.transfer.media.MediaEntry
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nearby Connections transport, fully automatic and with an in-band protocol.
 *
 * **Why a custom framing instead of FILE payloads:** FILE payloads hand the
 * receiver a scoped-storage temp file whose name and MIME must arrive in a
 * *separate* BYTES payload. Nearby does not guarantee ordering between payload
 * types, and when the metadata is missing or arrives late the file is silently
 * dropped — which is exactly the "it says sent but nothing arrives" failure.
 *
 * **Protocol (one STREAM payload per file, self-describing):**
 * ```
 * int32  magic  'A' 'G' 'F' 'T'
 * uint8  version (1)
 * int32  nameLength   + UTF-8 name
 * int32  mimeLength   + UTF-8 mime
 * int64  size in bytes (0 = unknown, until EOF)
 * ...    raw file bytes
 * ```
 * Both ends write/read through their own buffered streams on a dedicated I/O
 * thread, so a 200 MB video never touches the heap.
 *
 * The handshake/connection layer keeps working the way that already connected
 * in the field: both devices advertise *and* discover, and the first peer is
 * auto-accepted without any confirmation UI.
 */
class NearbyTransferManager(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStateChanged(state: NearbyState, detail: String? = null)
        fun onTransferProgress(progress: TransferProgress)
        fun onFileSent(fileName: String)
        fun onFileReceived(fileName: String, uri: Uri)
        fun onTransferFailed(fileName: String?, reason: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "airgesture-transfer").apply { priority = Thread.NORM_PRIORITY }
    }
    private val connections: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val serviceId: String = SERVICE_ID

    private var running = false
    private var connectedEndpointId: String? = null
    private var connectingEndpointId: String? = null
    private var connectedName: String? = null
    private var announcedName: String? = null

    // Outgoing
    private val sending = AtomicBoolean(false)
    @Volatile private var outgoingPayloadId: Long = -1L
    @Volatile private var outgoingName: String? = null
    @Volatile private var outgoingTotal: Long = 0L
    private var outgoingStream: OutputStream? = null

    // Incoming
    @Volatile private var incomingPayloadId: Long = -1L
    @Volatile private var incomingTotal: Long = 0L
    private var incomingStream: InputStream? = null

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        startAdvertising()
        startDiscovery()
        emitState(NearbyState.SEARCHING)
    }

    fun stop() {
        running = false
        connectedEndpointId = null
        connectingEndpointId = null
        connectedName = null
        closeStreams()
        sending.set(false)
        runCatching { connections.stopAdvertising() }
        runCatching { connections.stopDiscovery() }
        runCatching { connections.stopAllEndpoints() }
        emitState(NearbyState.IDLE)
    }

    val isConnected: Boolean get() = connectedEndpointId != null
    val peerName: String? get() = connectedName
    val isBusy: Boolean get() = sending.get()

    // ----------------------------------------------------------- advertising

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connections.startAdvertising(deviceName(), serviceId, lifecycleCallback, options)
            .addOnFailureListener { error ->
                Log.w(TAG, "startAdvertising failed", error)
                emitState(NearbyState.ERROR, error.message)
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connections.startDiscovery(serviceId, discoveryCallback, options)
            .addOnFailureListener { error ->
                Log.w(TAG, "startDiscovery failed", error)
                emitState(NearbyState.ERROR, error.message)
            }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!running) return
            if (connectedEndpointId != null || connectingEndpointId != null) return
            connectingEndpointId = endpointId
            emitState(NearbyState.PEER_FOUND, info.endpointName)
            connections.requestConnection(deviceName(), endpointId, lifecycleCallback)
                .addOnFailureListener { error ->
                    Log.w(TAG, "requestConnection failed", error)
                    if (connectingEndpointId == endpointId) connectingEndpointId = null
                }
        }

        override fun onEndpointLost(endpointId: String) {
            if (connectingEndpointId == endpointId && connectedEndpointId == null) {
                connectingEndpointId = null
                if (running) emitState(NearbyState.SEARCHING)
            }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Invisible pairing: accept immediately, as advertiser or as seeker.
            announcedName = info.endpointName
            emitState(NearbyState.CONNECTING, info.endpointName)
            connections.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    Log.w(TAG, "acceptConnection failed", error)
                    connectingEndpointId = null
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val status = resolution.status
            if (status.isSuccess) {
                connectedEndpointId = endpointId
                connectingEndpointId = null
                connectedName = announcedName?.takeIf { it.isNotBlank() } ?: peerLabel(endpointId)
                // The link is what matters now: stop advertising to save radios.
                runCatching { connections.stopAdvertising() }
                runCatching { connections.stopDiscovery() }
                emitState(NearbyState.CONNECTED, connectedName)
            } else {
                Log.w(TAG, "connection rejected: ${status.statusCode} ${status.statusMessage}")
                if (connectingEndpointId == endpointId) connectingEndpointId = null
                if (connectedEndpointId == null) {
                    emitState(NearbyState.SEARCHING)
                    if (running) startDiscovery()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpointId == endpointId) {
                connectedEndpointId = null
                connectedName = null
                closeStreams()
                sending.set(false)
                emitState(NearbyState.SEARCHING)
                if (running) {
                    startAdvertising()
                    startDiscovery()
                }
            }
        }
    }

    // -------------------------------------------------------------- sending

    /**
     * Streams [entry] to the connected peer. Returns false when there is no
     * peer yet or a transfer is already in flight — never silently "succeeds".
     */
    fun send(entry: MediaEntry): Boolean {
        val endpointId = connectedEndpointId ?: return false
        if (!sending.compareAndSet(false, true)) return false

        outgoingName = entry.name

        ioExecutor.execute {
            var input: InputStream? = null
            try {
                input = appContext.contentResolver.openInputStream(entry.uri)
                    ?: throw IOException("No se pudo abrir el archivo")

                val size = entry.sizeBytes.takeIf { it > 0 } ?: 0L
                val payload = Payload.fromStream()
                outgoingPayloadId = payload.id
                outgoingTotal = size

                emitProgress(entry.name, 0L, size, outgoing = true)

                connections.sendPayload(endpointId, payload)
                    .addOnFailureListener { error ->
                        Log.w(TAG, "sendPayload(stream) failed", error)
                        fail(entry.name, error.message ?: "Envío rechazado")
                    }

                val rawStream = payload.asStream()?.asOutputStream()
                    ?: throw IOException("El canal de envío no está disponible")
                outgoingStream = rawStream

                BufferedOutputStream(rawStream, BUFFER).use { buffered ->
                    DataOutputStream(buffered).use { data ->
                        data.writeInt(MAGIC)
                        data.writeByte(VERSION)
                        writeString(data, entry.name)
                        writeString(data, entry.mimeType)
                        data.writeLong(size)
                        data.flush()

                        val copy = ByteArray(BUFFER)
                        var sent = 0L
                        while (true) {
                            val read = input.read(copy)
                            if (read <= 0) break
                            data.write(copy, 0, read)
                            sent += read
                        }
                        data.flush()
                        if (size > 0L && sent != size) {
                            Log.w(TAG, "declared $size bytes but streamed $sent")
                        }
                    }
                }
                // Closing the stream is what tells Nearby the payload is complete.
            } catch (error: Exception) {
                Log.e(TAG, "send failed", error)
                fail(entry.name, friendly(error))
            } finally {
                runCatching { input?.close() }
                outgoingStream = null
            }
        }
        return true
    }

    // ------------------------------------------------------------- receiving

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.STREAM -> {
                    incomingPayloadId = payload.id
                    val stream = runCatching { payload.asStream()?.asInputStream() }.getOrNull()
                    if (stream == null) {
                        notifyFailure(null, "No se pudo abrir el flujo entrante")
                        return
                    }
                    incomingStream = stream
                    ioExecutor.execute { receiveStream(payload.id, stream) }
                }

                Payload.Type.FILE -> receiveLegacyFile(payload)

                Payload.Type.BYTES -> Unit // unused by the current protocol
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (update.payloadId == outgoingPayloadId) {
                        val total = if (update.totalBytes > 0) update.totalBytes else outgoingTotal
                        emitProgress(outgoingName ?: "Archivo", update.bytesTransferred, total, true)
                    } else if (update.payloadId == incomingPayloadId) {
                        emitProgress(
                            incomingDisplayName ?: "Archivo",
                            update.bytesTransferred,
                            incomingTotal,
                            outgoing = false
                        )
                    }
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (update.payloadId == outgoingPayloadId) {
                        val name = outgoingName ?: "Archivo"
                        val total = maxOf(update.totalBytes, outgoingTotal)
                        outgoingPayloadId = -1L
                        outgoingTotal = 0L
                        sending.set(false)
                        emitProgress(name, total, total, outgoing = true)
                        mainHandler.post { listener.onFileSent(name) }
                    }
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    if (update.payloadId == outgoingPayloadId) {
                        val name = outgoingName
                        outgoingPayloadId = -1L
                        sending.set(false)
                        fail(name, "La transferencia se interrumpió")
                    }
                }
            }
        }
    }

    @Volatile private var incomingDisplayName: String? = null

    /** Reads the framing header, then copies the payload into Downloads. */
    private fun receiveStream(payloadId: Long, rawStream: InputStream) {
        var inserted: Uri? = null
        val resolver = appContext.contentResolver
        try {
            val source = runCatching { DataInputStream(rawStream) }.getOrNull()
                ?: throw IOException("Flujo entrante inválido")

            val magic = source.readInt()
            if (magic != MAGIC) throw IOException("Formato no reconocido")
            val version = source.readByte().toInt()
            if (version != VERSION) throw IOException("Versión no soportada ($version)")

            val name = sanitize(readString(source))
            val mime = readString(source).ifBlank { "application/octet-stream" }
            val size = source.readLong()
            incomingTotal = if (size > 0) size else 0L
            incomingDisplayName = name

            emitProgress(name, 0L, incomingTotal, outgoing = false)

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${android.os.Environment.DIRECTORY_DOWNLOADS}/AirGesture"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("No se pudo crear el archivo de destino")
            inserted = target

            val output = resolver.openOutputStream(target)
                ?: throw IOException("No se pudo escribir el archivo de destino")

            var written = 0L
            output.use { sink ->
                val copy = ByteArray(BUFFER)
                while (true) {
                    val read = source.read(copy)
                    if (read <= 0) break
                    sink.write(copy, 0, read)
                    written += read
                }
                sink.flush()
            }

            if (incomingTotal > 0 && written != incomingTotal) {
                throw IOException("Recibidos $written de $incomingTotal bytes")
            }

            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            runCatching { resolver.update(target, done, null, null) }

            emitProgress(name, written, written, outgoing = false)
            val finalName = name
            val finalUri = target
            mainHandler.post { listener.onFileReceived(finalName, finalUri) }
        } catch (error: Exception) {
            Log.e(TAG, "receive failed", error)
            if (inserted != null) runCatching { resolver.delete(inserted, null, null) }
            val message = when (error) {
                is EOFException -> "El envío se cortó antes de terminar"
                else -> friendly(error)
            }
            notifyFailure(incomingDisplayName, message)
        } finally {
            runCatching { rawStream.close() }
            if (incomingPayloadId == payloadId) incomingPayloadId = -1L
            incomingStream = null
            incomingDisplayName = null
            incomingTotal = 0L
        }
    }

    /**
     * Compatibility path: an older build sends FILE payloads. Keep accepting
     * them so mixed versions can still talk to each other.
     */
    private fun receiveLegacyFile(payload: Payload) {
        incomingPayloadId = payload.id
        val filePayload = payload.asFile() ?: return
        val uri = runCatching { filePayload.asUri() }.getOrNull()
        val javaFile: File? = runCatching { filePayload.asJavaFile() }.getOrNull()
        val name = javaFile?.name ?: "Archivo recibido"
        incomingDisplayName = name

        ioExecutor.execute {
            val resolver = appContext.contentResolver
            var inserted: Uri? = null
            try {
                val source: InputStream? = when {
                    uri != null -> runCatching { resolver.openInputStream(uri) }.getOrNull()
                    javaFile != null -> runCatching { javaFile.inputStream() }.getOrNull()
                    else -> null
                } ?: throw IOException("No se pudo leer el archivo temporal")

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sanitize(name))
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${android.os.Environment.DIRECTORY_DOWNLOADS}/AirGesture"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("No se pudo crear el archivo de destino")
                inserted = target
                source.use { input ->
                    resolver.openOutputStream(target)?.use { output ->
                        input.copyTo(output, BUFFER)
                        output.flush()
                    } ?: throw IOException("No se pudo escribir el archivo")
                }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                runCatching { resolver.update(target, done, null, null) }
                mainHandler.post { listener.onFileReceived(sanitize(name), target) }
            } catch (error: Exception) {
                Log.e(TAG, "legacy receive failed", error)
                if (inserted != null) runCatching { resolver.delete(inserted, null, null) }
                notifyFailure(name, friendly(error))
            } finally {
                incomingPayloadId = -1L
                incomingDisplayName = null
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length <= 0) return ""
        if (length > MAX_STRING_BYTES) throw IOException("Cabecera corrupta")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        val safe = clean.ifBlank { "Archivo_recibido" }
        return if (safe.length > MAX_NAME_LENGTH) {
            val dot = safe.lastIndexOf('.')
            if (dot > 0 && safe.length - dot <= 10) {
                safe.take(MAX_NAME_LENGTH - (safe.length - dot)) + safe.substring(dot)
            } else {
                safe.take(MAX_NAME_LENGTH)
            }
        } else {
            safe
        }
    }

    private fun friendly(error: Throwable): String = when (error) {
        is IllegalArgumentException -> "Archivo no válido"
        is SecurityException -> "Sin permiso para leer el archivo"
        is java.io.FileNotFoundException -> "El archivo ya no está disponible"
        else -> error.message ?: "Fallo de transferencia"
    }

    private fun closeStreams() {
        runCatching { outgoingStream?.close() }
        runCatching { incomingStream?.close() }
        outgoingStream = null
        incomingStream = null
    }

    private fun fail(fileName: String?, reason: String) {
        sending.set(false)
        notifyFailure(fileName, reason)
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty().ifBlank { "Android" }
        val short = if (model.length > 16) model.take(16) else model
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                appContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull().orEmpty()
        val suffix = androidId.takeLast(2).uppercase()
        return if (suffix.isNotEmpty()) "$short·$suffix" else short
    }

    private fun peerLabel(endpointId: String): String = endpointId.takeLast(4).uppercase()

    private fun emitState(state: NearbyState, detail: String? = null) {
        mainHandler.post { listener.onStateChanged(state, detail) }
    }

    private fun emitProgress(fileName: String, bytes: Long, total: Long, outgoing: Boolean) {
        mainHandler.post {
            listener.onTransferProgress(
                TransferProgress(
                    fileName = fileName,
                    bytesTransferred = bytes,
                    totalBytes = total,
                    outgoing = outgoing
                )
            )
        }
    }

    private fun notifyFailure(fileName: String?, reason: String) {
        mainHandler.post { listener.onTransferFailed(fileName, reason) }
    }

    companion object {
        private const val TAG = "NearbyTransfer"
        private const val SERVICE_ID = "com.airgesture.transfer.P2P"
        private const val MAGIC = 0x41474654 // 'AGFT'
        private const val VERSION = 1
        private const val BUFFER = 64 * 1024
        private const val MAX_STRING_BYTES = 4096
        private const val MAX_NAME_LENGTH = 120
        private val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
