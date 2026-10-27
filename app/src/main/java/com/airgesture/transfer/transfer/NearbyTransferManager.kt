package com.airgesture.transfer.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import org.json.JSONObject
import java.io.File

/**
 * Fully automatic Nearby Connections session over P2P_CLUSTER.
 *
 * No pairing screen, no device list, no user confirmation: the app advertises
 * *and* discovers at the same time, auto-accepts the first peer that answers,
 * and resumes advertising after a drop. Wi-Fi Direct carries the bytes; BLE is
 * only used for the handshake, so transfers work with no router, no internet
 * and no mobile data.
 *
 * Protocol (per file):
 *   1. BYTES payload with JSON metadata (name, MIME, size).
 *   2. Once that metadata payload reports SUCCESS, the FILE payload is sent.
 * Sending the header first avoids the documented race where payloads of
 * different types are not guaranteed to arrive in call order.
 *
 * The outgoing descriptor is a [ParcelFileDescriptor] obtained from the
 * ContentResolver, which keeps memory flat for multi-gigabyte videos.
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
    private val serviceId: String = SERVICE_ID
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connections: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    private var running = false
    private var connectedEndpointId: String? = null
    private var connectingEndpointId: String? = null
    private var connectedName: String? = null
    private var announcedName: String? = null

    // Outgoing state
    private var queuedEntry: MediaEntry? = null
    private var metaPayloadId: Long = -1L
    private var outgoingPayloadId: Long = -1L
    private var outgoingFileName: String? = null
    private var outgoingTotal: Long = 0L
    private var outgoingDescriptor: ParcelFileDescriptor? = null

    // Incoming state
    private var incomingPayloadId: Long = -1L
    private var incomingMeta: IncomingMeta? = null
    private var incomingUri: Uri? = null
    private var incomingFile: File? = null

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
        queuedEntry = null
        closeOutgoingDescriptor()
        runCatching { connections.stopAdvertising() }
        runCatching { connections.stopDiscovery() }
        runCatching { connections.stopAllEndpoints() }
        emitState(NearbyState.IDLE)
    }

    val isConnected: Boolean get() = connectedEndpointId != null
    val peerName: String? get() = connectedName

    // ----------------------------------------------------------- advertising

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connections.startAdvertising(deviceName(), serviceId, connectionLifecycleCallback, options)
            .addOnFailureListener { error ->
                Log.w(TAG, "startAdvertising failed", error)
                emitState(NearbyState.ERROR, error.message)
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connections.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnFailureListener { error ->
                Log.w(TAG, "startDiscovery failed", error)
                emitState(NearbyState.ERROR, error.message)
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!running) return
            if (connectedEndpointId != null || connectingEndpointId != null) return
            connectingEndpointId = endpointId
            emitState(NearbyState.PEER_FOUND, info.endpointName)
            connections.requestConnection(deviceName(), endpointId, connectionLifecycleCallback)
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

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
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
                // Stop the radio chatter: the link is what matters now.
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
                queuedEntry = null
                closeOutgoingDescriptor()
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
     * Starts the two-step send. Returns false when there is no peer yet (the
     * caller keeps the payload queued) or when a send is already in flight.
     */
    fun send(entry: MediaEntry): Boolean {
        val endpointId = connectedEndpointId ?: return false
        if (queuedEntry != null || outgoingPayloadId != -1L) return false

        queuedEntry = entry
        val meta = JSONObject()
            .put(KEY_NAME, entry.name)
            .put(KEY_MIME, entry.mimeType)
            .put(KEY_SIZE, entry.sizeBytes)

        val metaPayload = Payload.fromBytes(meta.toString().toByteArray(Charsets.UTF_8))
        metaPayloadId = metaPayload.id

        connections.sendPayload(endpointId, metaPayload)
            .addOnFailureListener { error ->
                Log.w(TAG, "metadata send failed", error)
                queuedEntry = null
                notifyFailure(entry.name, error.message ?: "Envío interrumpido")
            }
        return true
    }

    /**
     * Second step: stream the bytes from the ContentResolver. Called once the
     * metadata payload reports SUCCESS, which guarantees the receiver already
     * knows the real file name and MIME type.
     */
    private fun startFileTransfer() {
        val entry = queuedEntry ?: return
        val endpointId = connectedEndpointId
        if (endpointId == null) {
            queuedEntry = null
            notifyFailure(entry.name, "Conexión perdida")
            return
        }

        val descriptor: ParcelFileDescriptor? = runCatching {
            appContext.contentResolver.openFileDescriptor(entry.uri, "r")
        }.getOrNull()

        if (descriptor == null) {
            queuedEntry = null
            notifyFailure(entry.name, "No se pudo leer el archivo")
            return
        }

        val payload = runCatching { Payload.fromFile(descriptor) }.getOrElse { error ->
            Log.e(TAG, "Unable to build payload for ${entry.uri}", error)
            runCatching { descriptor.close() }
            queuedEntry = null
            notifyFailure(entry.name, error.message ?: "No se pudo leer el archivo")
            return
        }

        closeOutgoingDescriptor()
        outgoingDescriptor = descriptor
        outgoingPayloadId = payload.id
        outgoingFileName = entry.name
        outgoingTotal = entry.sizeBytes
        queuedEntry = null

        connections.sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                Log.w(TAG, "file send failed", error)
                closeOutgoingDescriptor()
                outgoingPayloadId = -1L
                outgoingFileName = null
                notifyFailure(entry.name, error.message ?: "Envío interrumpido")
            }
    }

    private fun closeOutgoingDescriptor() {
        runCatching { outgoingDescriptor?.close() }
        outgoingDescriptor = null
    }

    // ------------------------------------------------------------- receiving

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val meta = parseMeta(payload.asBytes() ?: return)
                    if (meta != null) incomingMeta = meta
                }

                Payload.Type.FILE -> {
                    incomingPayloadId = payload.id
                    val filePayload = payload.asFile()
                    incomingUri = runCatching { filePayload?.asUri() }.getOrNull()
                    incomingFile = runCatching { filePayload?.asJavaFile() }.getOrNull()

                    if (incomingMeta == null) {
                        val size: Long = filePayload?.let { file ->
                            runCatching { file.size }.getOrDefault(0L)
                        } ?: 0L
                        incomingMeta = IncomingMeta(
                            name = incomingFile?.name ?: "Archivo recibido",
                            mime = "application/octet-stream",
                            size = size
                        )
                    }
                    val meta = incomingMeta
                    if (meta != null) {
                        postProgress(TransferProgress(meta.name, 0L, meta.size, outgoing = false))
                    }
                }

                else -> Unit // STREAM payloads are not used by this app.
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (update.payloadId == outgoingPayloadId) {
                        postProgress(
                            TransferProgress(
                                fileName = outgoingFileName ?: "Archivo",
                                bytesTransferred = update.bytesTransferred,
                                totalBytes = if (update.totalBytes > 0) update.totalBytes else outgoingTotal,
                                outgoing = true
                            )
                        )
                    } else if (update.payloadId == incomingPayloadId) {
                        val meta = incomingMeta ?: return
                        postProgress(
                            TransferProgress(
                                fileName = meta.name,
                                bytesTransferred = update.bytesTransferred,
                                totalBytes = if (update.totalBytes > 0) update.totalBytes else meta.size,
                                outgoing = false
                            )
                        )
                    }
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (update.payloadId == metaPayloadId) {
                        // Header delivered: now stream the file itself.
                        metaPayloadId = -1L
                        startFileTransfer()
                    } else if (update.payloadId == outgoingPayloadId) {
                        val name = outgoingFileName ?: "Archivo"
                        val total = if (update.totalBytes > 0) update.totalBytes else outgoingTotal
                        outgoingPayloadId = -1L
                        outgoingFileName = null
                        closeOutgoingDescriptor()
                        postProgress(TransferProgress(name, total, total, outgoing = true))
                        mainHandler.post { listener.onFileSent(name) }
                    } else if (update.payloadId == incomingPayloadId) {
                        persistIncomingPayload()
                    }
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    val wasOutgoing = update.payloadId == outgoingPayloadId
                    val name = if (wasOutgoing) outgoingFileName else incomingMeta?.name
                    if (wasOutgoing) {
                        outgoingPayloadId = -1L
                        outgoingFileName = null
                        closeOutgoingDescriptor()
                    } else {
                        incomingPayloadId = -1L
                        incomingMeta = null
                        incomingUri = null
                        incomingFile = null
                    }
                    notifyFailure(name, "Transferencia interrumpida")
                }
            }
        }
    }

    /**
     * Copies the received payload into the public Downloads collection so the
     * file survives process death and appears in any gallery / file manager.
     * Scoped storage forbids touching Nearby's temp path, so everything goes
     * through the ContentResolver URI it hands us.
     */
    private fun persistIncomingPayload() {
        val meta = incomingMeta ?: return
        val resolver = appContext.contentResolver
        val fileName = safeFileName(meta.name)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, meta.mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/AirGesture"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val target = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()

        if (target == null) {
            clearIncoming()
            notifyFailure(fileName, "No se pudo guardar el archivo")
            return
        }

        val copied = runCatching {
            val input = openIncomingSource(resolver)
            if (input == null) {
                false
            } else {
                input.use { source ->
                    val output = resolver.openOutputStream(target)
                    if (output == null) {
                        false
                    } else {
                        output.use { sink -> source.copyTo(sink, COPY_BUFFER) }
                        true
                    }
                }
            }
        }.getOrDefault(false)

        if (!copied) {
            runCatching { resolver.delete(target, null, null) }
            clearIncoming()
            notifyFailure(fileName, "No se pudo guardar el archivo")
            return
        }

        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        runCatching { resolver.update(target, done, null, null) }

        clearIncoming()
        mainHandler.post { listener.onFileReceived(fileName, target) }
    }

    private fun openIncomingSource(resolver: android.content.ContentResolver): java.io.InputStream? {
        val uri = incomingUri
        if (uri != null) {
            val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            if (stream != null) return stream
        }
        val file = incomingFile ?: return null
        return runCatching { file.inputStream() }.getOrNull()
    }

    private fun clearIncoming() {
        incomingPayloadId = -1L
        incomingMeta = null
        incomingUri = null
        incomingFile = null
    }

    // --------------------------------------------------------------- helpers

    private fun parseMeta(bytes: ByteArray): IncomingMeta? = runCatching {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        IncomingMeta(
            name = json.optString(KEY_NAME, "Archivo"),
            mime = json.optString(KEY_MIME, "application/octet-stream"),
            size = json.optLong(KEY_SIZE, 0L)
        )
    }.getOrNull()

    private fun safeFileName(original: String): String =
        original.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Archivo" }

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

    private fun peerLabel(endpointId: String): String =
        connectedName ?: endpointId.takeLast(4).uppercase()

    private fun emitState(state: NearbyState, detail: String? = null) {
        mainHandler.post { listener.onStateChanged(state, detail) }
    }

    private fun postProgress(progress: TransferProgress) {
        mainHandler.post { listener.onTransferProgress(progress) }
    }

    private fun notifyFailure(fileName: String?, reason: String) {
        mainHandler.post { listener.onTransferFailed(fileName, reason) }
    }

    private data class IncomingMeta(val name: String, val mime: String, val size: Long)

    companion object {
        private const val TAG = "NearbyTransfer"
        private const val SERVICE_ID = "com.airgesture.transfer.P2P"
        private const val KEY_NAME = "n"
        private const val KEY_MIME = "m"
        private const val KEY_SIZE = "s"
        private const val COPY_BUFFER = 64 * 1024
        private val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
