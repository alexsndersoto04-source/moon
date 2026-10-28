package com.airgesture.transfer.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.airgesture.transfer.media.MediaEntry
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nearby Connections transport with a fully automatic session.
 *
 * ## Why the previous version never delivered a file
 * Both phones advertise **and** discover (that is what "no configuration" needs),
 * so both called `requestConnection` at the same time for the same endpoint.
 * One side then resolved as ALREADY_CONNECTED while the other stayed in a
 * half-open state, and any later `sendPayload` went nowhere.
 *
 * Two rules fix it for good:
 *  1. **Deterministic initiator.** Only the device whose name sorts first opens
 *     the connection. The other one just accepts the incoming request. Exactly
 *     one handshake can ever happen per pair.
 *  2. **ALREADY_CONNECTED means connected.** If a request races anyway, the
 *     status is treated as success instead of tearing the session down.
 *
 * ## Wire format
 * Per file: a BYTES payload with JSON metadata, and only when that payload
 * reports SUCCESS is the FILE payload sent — the ordering the official sample
 * uses, because Nearby does not order payloads of different types. The receiver
 * copies the payload through the URI Nearby hands back (scoped-storage safe) and
 * answers with a BYTES ack so the sender can honestly say "entregado".
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
        /** Human readable trace shown in the UI, newest last. */
        fun onLog(line: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "airgesture-transfer").apply { priority = Thread.NORM_PRIORITY }
    }
    private val connections: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    // Advertise and discover on both service ids so a device running an older
    // build can still pair with this one instead of silently never matching.
    private val serviceIds: List<String> = listOf(SERVICE_ID_V2, SERVICE_ID_V1)
    private val myName: String = buildDeviceName()

    private var running = false
    private var connectedEndpointId: String? = null
    private var connectingEndpointId: String? = null
    private var connectedName: String? = null
    private var announcedName: String? = null
    private var lastPeerName: String? = null

    // Outgoing
    private val sending = AtomicBoolean(false)
    private var queuedEntry: MediaEntry? = null
    private var metaPayloadId: Long = -1L
    @Volatile private var outgoingPayloadId: Long = -1L
    @Volatile private var outgoingName: String? = null
    @Volatile private var outgoingTotal: Long = 0L
    private var outgoingDescriptor: ParcelFileDescriptor? = null

    // Watchdog: a payload that never reports SUCCESS must fail loudly instead
    // of leaving the UI stuck at 3 % forever.
    private var lastProgressMs = 0L
    private val stallWatchdog = object : Runnable {
        override fun run() {
            if (!sending.get()) return
            val silentFor = android.os.SystemClock.elapsedRealtime() - lastProgressMs
            if (silentFor > STALL_MS) {
                val name = outgoingName
                log("El envío se quedó sin respuesta (${silentFor / 1000} s)")
                cancelCurrent()
                notifyFailure(name, "El otro teléfono dejó de responder")
                return
            }
            mainHandler.postDelayed(this, STALL_CHECK_MS)
        }
    }

    // Incoming
    @Volatile private var incomingPayloadId: Long = -1L
    @Volatile private var incomingTotal: Long = 0L
    private var incomingPayload: Payload? = null
    @Volatile private var incomingName: String? = null

    // ------------------------------------------------------------- discovery
    //
    // Both phones advertise **and** discover at the same time, permanently, and
    // neither radio is ever restarted on a timer. Advertising is a broadcast and
    // discovery is a scan; running both means each phone is visible while it
    // looks, so the first result arrives in a second or two instead of after a
    // full role rotation. (Restarting the scan every few seconds is what used to
    // swallow every result — that watchdog is gone for good.)
    //
    // The handshake itself is disambiguated by name, never by luck: the device
    // whose name sorts first calls requestConnection, the other one only
    // accepts. Exactly one dial per pair, every time. If the loser has not been
    // dialled after a short grace period, it dials anyway — so a missed
    // discovery event can delay a connection but never prevent it.
    // -------------------------------------------------------------------------

    @Volatile private var dialToken: String? = null
    private var lastPhaseLogMs = 0L
    private var pendingPeerId: String? = null
    private var pendingPeerName: String? = null
    private var graceRunnable: Runnable? = null

    private fun startRadios() {
        if (!running) return
        if (connectedEndpointId != null || connectingEndpointId != null) return
        startAdvertising()
        startDiscovery()
        logOnce("Buscando el otro teléfono…", SEARCH_LOG_MS)
    }

    private fun stopRadios() {
        cancelGrace()
        runCatching { connections.stopAdvertising() }
        runCatching { connections.stopDiscovery() }
    }

    private fun cancelGrace() {
        graceRunnable?.let { mainHandler.removeCallbacks(it) }
        graceRunnable = null
        pendingPeerId = null
        pendingPeerName = null
    }

    /** Restarts advertising/discovery after a failed attempt, from scratch. */
    private fun restartRadios(delayMs: Long) {
        if (!running) return
        stopRadios()
        mainHandler.postDelayed({ startRadios() }, delayMs)
    }

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        emitState(NearbyState.SEARCHING)
        log("Soy $myName · encendiendo radios")
        startRadios()
    }

    fun stop() {
        running = false
        stopRadios()
        connectedEndpointId = null
        connectingEndpointId = null
        connectedName = null
        queuedEntry = null
        closeOutgoingDescriptor()
        sending.set(false)
        runCatching { connections.stopAllEndpoints() }
        emitState(NearbyState.IDLE)
    }

    val isConnected: Boolean get() = connectedEndpointId != null
    val peerName: String? get() = connectedName ?: lastPeerName
    val isBusy: Boolean get() = sending.get()

    // ----------------------------------------------------------- advertising

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        serviceIds.forEach { id ->
            connections.startAdvertising(myName, id, lifecycleCallback, options)
                .addOnSuccessListener {
                    Log.d(TAG, "advertising on $id")
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "startAdvertising($id) failed", error)
                    val code = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
                    if (code != null &&
                        code != ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING
                    ) {
                        log("Anuncio: ${statusText(code)}")
                    }
                }
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        serviceIds.forEach { id ->
            connections.startDiscovery(id, discoveryCallback, options)
                .addOnSuccessListener {
                    Log.d(TAG, "discovering on $id")
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "startDiscovery($id) failed", error)
                    val code = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
                    if (code != null && code != ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                        log("Búsqueda: ${statusText(code)}")
                    }
                }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!running) return
            if (connectedEndpointId != null || connectingEndpointId != null) return
            if (endpointId == connectedEndpointId || endpointId == connectingEndpointId) return

            val peer = info.endpointName.orEmpty().trim()
            if (peer.isBlank() || peer == myName) return

            lastPeerName = peer
            emitState(NearbyState.PEER_FOUND, peer)
            log("Encontrado: $peer")

            val iDial = myName < peer
            if (iDial) {
                dial(endpointId, peer)
            } else {
                // The other phone sorts first, so it will dial. Give it a
                // moment; if nothing arrives, dial anyway instead of waiting.
                cancelGrace()
                pendingPeerId = endpointId
                pendingPeerName = peer
                val runnable = Runnable {
                    if (connectedEndpointId == null && connectingEndpointId == null) {
                        val id = pendingPeerId
                        val name = pendingPeerName
                        if (id != null) {
                            log("Sin respuesta de $name · tomando la iniciativa")
                            dial(id, name ?: peer)
                        }
                    }
                }
                graceRunnable = runnable
                mainHandler.postDelayed(runnable, DIAL_GRACE_MS)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            if (connectingEndpointId == endpointId && connectedEndpointId == null) {
                connectingEndpointId = null
                restartRadios(RETRY_MS)
            }
        }
    }

    private fun dial(endpointId: String, peerName: String) {
        if (connectingEndpointId != null || connectedEndpointId != null) return
        if (endpointId == dialToken) return
        cancelGrace()
        dialToken = endpointId
        connectingEndpointId = endpointId
        lastPeerName = peerName
        emitState(NearbyState.CONNECTING, peerName)
        log("Conectando con $peerName…")

        connections.requestConnection(myName, endpointId, lifecycleCallback)
            .addOnFailureListener { error ->
                val code = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
                if (code != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    log("No se pudo conectar: ${code?.let { statusText(it) } ?: error.message}")
                }
                if (connectingEndpointId == endpointId) connectingEndpointId = null
                dialToken = null
                if (connectedEndpointId == null) restartRadios(RETRY_MS)
            }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            announcedName = info.endpointName
            cancelGrace()
            if (connectingEndpointId == null) connectingEndpointId = endpointId
            emitState(NearbyState.CONNECTING, info.endpointName)
            connections.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    Log.w(TAG, "acceptConnection failed", error)
                    log("No se pudo aceptar: ${error.message}")
                    if (connectedEndpointId == null) restartRadios(RETRY_MS)
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val status = resolution.status
            // ALREADY_CONNECTED is a success in disguise: the peer dialled first.
            if (status.isSuccess ||
                status.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT
            ) {
                connectedEndpointId = endpointId
                connectingEndpointId = null
                dialToken = null
                connectedName = announcedName?.takeIf { it.isNotBlank() }
                    ?: lastPeerName
                    ?: endpointId.takeLast(4).uppercase()
                lastPeerName = connectedName
                stopRadios()
                emitState(NearbyState.CONNECTED, connectedName)
                log("Conectado a $connectedName")
            } else {
                Log.w(TAG, "connection rejected: ${status.statusCode} ${status.statusMessage}")
                log("Conexión rechazada: ${statusText(status.statusCode)}")
                if (connectingEndpointId == endpointId) connectingEndpointId = null
                if (connectedEndpointId == null) {
                    emitState(NearbyState.SEARCHING)
                    restartRadios(RETRY_MS)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpointId == endpointId) {
                connectedEndpointId = null
                connectedName = null
                queuedEntry = null
                closeOutgoingDescriptor()
                sending.set(false)
                emitState(NearbyState.SEARCHING)
                log("Se perdió la conexión; reintentando")
                restartRadios(0L)
            }
        }
    }

    // -------------------------------------------------------------- sending

    /**
     * Queues [entry] for the connected peer. Returns false when there is no peer
     * yet or a send is already running — the caller tells the user why.
     */
    fun send(entry: MediaEntry): Boolean {
        val endpointId = connectedEndpointId ?: return false
        if (!sending.compareAndSet(false, true)) return false

        queuedEntry = entry
        outgoingName = entry.name
        outgoingTotal = entry.sizeBytes
        lastProgressMs = android.os.SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.postDelayed(stallWatchdog, STALL_CHECK_MS)

        val meta = org.json.JSONObject()
            .put(KEY_NAME, entry.name)
            .put(KEY_MIME, entry.mimeType)
            .put(KEY_SIZE, entry.sizeBytes)

        val metaPayload = Payload.fromBytes(meta.toString().toByteArray(Charsets.UTF_8))
        metaPayloadId = metaPayload.id
        log("Preparando ${entry.name}")

        connections.sendPayload(endpointId, metaPayload)
            .addOnFailureListener { error ->
                log("Error al enviar la cabecera (${error.message})")
                sending.set(false)
                queuedEntry = null
                notifyFailure(entry.name, error.message ?: "Envío rechazado")
            }
        return true
    }

    /**
     * Cancels the payload that is currently in flight. The peer sees a CANCELED
     * update and cleans up its half-written file, so a cancelled send never
     * leaves a truncated file behind.
     */
    fun cancelCurrent(): Boolean {
        val endpointId = connectedEndpointId
        var cancelled = false
        val fileId = outgoingPayloadId
        if (fileId != -1L) {
            cancelled = runCatching { connections.cancelPayload(fileId) }.isSuccess
        }
        val metaId = metaPayloadId
        if (metaId != -1L) {
            cancelled = runCatching { connections.cancelPayload(metaId) }.isSuccess || cancelled
        }
        if (endpointId != null && cancelled) {
            log("Envío cancelado")
        }
        outgoingPayloadId = -1L
        metaPayloadId = -1L
        mainHandler.removeCallbacks(stallWatchdog)
        closeOutgoingDescriptor()
        sending.set(false)
        queuedEntry = null
        return cancelled
    }

    /** Second step: the FILE payload, once the metadata is confirmed. */
    private fun startFileTransfer() {
        val entry = queuedEntry ?: return
        val endpointId = connectedEndpointId
        if (endpointId == null) {
            sending.set(false)
            queuedEntry = null
            notifyFailure(entry.name, "Conexión perdida")
            return
        }

        val descriptor = runCatching {
            appContext.contentResolver.openFileDescriptor(entry.uri, "r")
        }.getOrNull()

        if (descriptor == null) {
            log("No se pudo abrir el archivo")
            sending.set(false)
            queuedEntry = null
            notifyFailure(entry.name, "No se pudo leer el archivo")
            return
        }

        val payload = runCatching { Payload.fromFile(descriptor) }.getOrElse { error ->
            Log.e(TAG, "payload build failed", error)
            runCatching { descriptor.close() }
            sending.set(false)
            queuedEntry = null
            notifyFailure(entry.name, "No se pudo preparar el envío")
            return
        }

        closeOutgoingDescriptor()
        outgoingDescriptor = descriptor
        outgoingPayloadId = payload.id
        log("Enviando ${entry.name}…")

        connections.sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                Log.w(TAG, "sendPayload(file) failed", error)
                log("El envío falló (${error.message})")
                closeOutgoingDescriptor()
                outgoingPayloadId = -1L
                sending.set(false)
                notifyFailure(entry.name, error.message ?: "Envío rechazado")
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
                Payload.Type.BYTES -> handleBytes(payload.asBytes())

                Payload.Type.FILE -> {
                    incomingPayloadId = payload.id
                    incomingPayload = payload
                    val size = runCatching { payload.asFile()?.size }.getOrNull() ?: 0L
                    incomingTotal = size
                    log("Recibiendo archivo (${size / 1024} KB)…")
                }

                Payload.Type.STREAM -> {
                    // Compatibility with builds that stream the header inline.
                    incomingPayloadId = payload.id
                    val stream = runCatching { payload.asStream()?.asInputStream() }.getOrNull()
                    if (stream == null) {
                        notifyFailure(null, "No se pudo abrir el flujo entrante")
                        return
                    }
                    ioExecutor.execute { receiveStream(payload.id, stream) }
                }

                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    lastProgressMs = android.os.SystemClock.elapsedRealtime()
                    if (update.payloadId == outgoingPayloadId) {
                        postProgress(
                            TransferProgress(
                                outgoingName ?: "Archivo",
                                update.bytesTransferred,
                                if (update.totalBytes > 0) update.totalBytes else outgoingTotal,
                                true
                            )
                        )
                    } else if (update.payloadId == incomingPayloadId) {
                        postProgress(
                            TransferProgress(
                                incomingName ?: "Archivo",
                                update.bytesTransferred,
                                if (update.totalBytes > 0) update.totalBytes else incomingTotal,
                                false
                            )
                        )
                    }
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    when (update.payloadId) {
                        metaPayloadId -> {
                            metaPayloadId = -1L
                            startFileTransfer()
                        }

                        outgoingPayloadId -> {
                            mainHandler.removeCallbacks(stallWatchdog)
                            val name = outgoingName ?: "Archivo"
                            val total = maxOf(update.totalBytes, outgoingTotal)
                            outgoingPayloadId = -1L
                            closeOutgoingDescriptor()
                            sending.set(false)
                            postProgress(TransferProgress(name, total, total, true))
                            log("Enviado: $name")
                            mainHandler.post { listener.onFileSent(name) }
                        }

                        incomingPayloadId -> persistIncoming()
                    }
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    if (update.payloadId == outgoingPayloadId) {
                        val name = outgoingName
                        outgoingPayloadId = -1L
                        closeOutgoingDescriptor()
                        sending.set(false)
                        log("Transferencia interrumpida")
                        notifyFailure(name, "La transferencia se interrumpió")
                    } else if (update.payloadId == incomingPayloadId) {
                        clearIncoming()
                        notifyFailure(incomingName, "No se pudo recibir el archivo")
                    }
                }
            }
        }
    }

    private fun handleBytes(bytes: ByteArray?) {
        if (bytes == null) return
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
        if (text == ACK_TOKEN) {
            log("El otro teléfono confirmó la recepción")
            return
        }
        runCatching {
            val json = org.json.JSONObject(text)
            incomingName = json.optString(KEY_NAME, "Archivo").let { sanitize(it) }
            incomingTotal = json.optLong(KEY_SIZE, 0L)
        }
    }

    /**
     * Copies the finished FILE payload into the public Downloads collection.
     * Nearby hands back a content URI with read/write access, which is the only
     * scoped-storage-safe way to touch it.
     */
    private fun persistIncoming() {
        val payload = incomingPayload
        val filePayload = payload?.asFile()
        val uri = runCatching { filePayload?.asUri() }.getOrNull()
        val javaFile: File? = runCatching { filePayload?.asJavaFile() }.getOrNull()
        val name = sanitize(incomingName ?: javaFile?.name ?: "Archivo_recibido")
        val resolver = appContext.contentResolver

        ioExecutor.execute {
            var inserted: Uri? = null
            try {
                val source: InputStream = when {
                    uri != null -> runCatching { resolver.openInputStream(uri) }.getOrNull()
                    javaFile != null -> runCatching { javaFile.inputStream() }.getOrNull()
                    else -> null
                } ?: throw IOException("El archivo recibido no es accesible")

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${android.os.Environment.DIRECTORY_DOWNLOADS}/AirGesture"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("No se pudo crear el archivo de destino")
                inserted = target

                BufferedInputStream(source, BUFFER).use { input ->
                    val output = resolver.openOutputStream(target)
                        ?: throw IOException("No se pudo escribir el archivo")
                    output.use { sink ->
                        val buffer = ByteArray(BUFFER)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            written += read
                        }
                        sink.flush()
                        if (incomingTotal > 0 && written != incomingTotal) {
                            throw IOException("Se recibieron $written de $incomingTotal bytes")
                        }
                    }
                }

                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                runCatching { resolver.update(target, done, null, null) }

                log("Guardado en Descargas/AirGesture: $name")
                val finalUri = target
                mainHandler.post { listener.onFileReceived(name, finalUri) }
                sendAck()
            } catch (error: Exception) {
                Log.e(TAG, "persist failed", error)
                if (inserted != null) runCatching { resolver.delete(inserted, null, null) }
                notifyFailure(name, error.message ?: "No se pudo guardar el archivo")
            } finally {
                runCatching { payload?.close() }
                clearIncoming()
            }
        }
    }

    /** Tells the peer the file really landed on disk. */
    private fun sendAck() {
        val endpointId = connectedEndpointId ?: return
        runCatching {
            connections.sendPayload(
                endpointId,
                Payload.fromBytes(ACK_TOKEN.toByteArray(Charsets.UTF_8))
            )
        }
    }

    /** Legacy inline-stream protocol (build 1.1.0 and earlier). */
    private fun receiveStream(payloadId: Long, rawStream: InputStream) {
        val resolver = appContext.contentResolver
        var inserted: Uri? = null
        try {
            val source = DataInputStream(rawStream)
            if (source.readInt() != MAGIC) throw IOException("Formato no reconocido")
            val version = source.readByte().toInt()
            if (version != VERSION) throw IOException("Versión no soportada")
            val name = sanitize(readString(source))
            val mime = readString(source).ifBlank { "application/octet-stream" }
            val size = source.readLong()
            incomingName = name
            incomingTotal = if (size > 0) size else 0L

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

            var written = 0L
            val output = resolver.openOutputStream(target) ?: throw IOException("Sin destino")
            output.use { sink ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    written += read
                }
                sink.flush()
            }
            if (incomingTotal > 0 && written != incomingTotal) {
                throw IOException("Se recibieron $written de $incomingTotal bytes")
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            runCatching { resolver.update(target, done, null, null) }
            log("Guardado en Descargas/AirGesture: $name")
            val finalUri = target
            mainHandler.post { listener.onFileReceived(name, finalUri) }
        } catch (error: Exception) {
            Log.e(TAG, "stream receive failed", error)
            if (inserted != null) runCatching { resolver.delete(inserted, null, null) }
            val message = if (error is EOFException) "El envío se cortó antes de terminar"
            else error.message ?: "No se pudo recibir el archivo"
            notifyFailure(incomingName, message)
        } finally {
            runCatching { rawStream.close() }
            if (incomingPayloadId == payloadId) clearIncoming()
        }
    }

    // --------------------------------------------------------------- helpers

    private fun clearIncoming() {
        incomingPayloadId = -1L
        incomingPayload = null
        incomingName = null
        incomingTotal = 0L
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length <= 0) return ""
        if (length > MAX_STRING_BYTES) throw IOException("Cabecera corrupta")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun sanitize(raw: String): String {
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        val safe = clean.ifBlank { "Archivo_recibido" }
        if (safe.length <= MAX_NAME_LENGTH) return safe
        val dot = safe.lastIndexOf('.')
        return if (dot > 0 && safe.length - dot <= 10) {
            safe.take(MAX_NAME_LENGTH - (safe.length - dot)) + safe.substring(dot)
        } else {
            safe.take(MAX_NAME_LENGTH)
        }
    }

    /** Human readable reason for the most common Nearby failures. */
    private fun statusText(code: Int): String = when (code) {
        ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING -> "ya estaba anunciando"
        ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> "ya conectado"
        ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING -> "ya estaba buscando"
        ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT -> "el otro teléfono no está conectado"
        ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL -> "llamada fuera de orden"
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION -> "falta permiso de ubicación"
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN -> "falta permiso de escaneo"
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE -> "falta permiso para anunciarse"
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT -> "falta permiso de conexión"
        ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES -> "falta permiso de dispositivos cercanos"
        else -> "código $code"
    }

    private fun buildDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty().ifBlank { "Android" }
        val short = if (model.length > 14) model.take(14) else model
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val suffix = androidId.takeLast(3).uppercase()
        return if (suffix.isNotEmpty()) "$short-$suffix" else short
    }

    private fun emitState(state: NearbyState, detail: String? = null) {
        mainHandler.post { listener.onStateChanged(state, detail) }
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        com.airgesture.transfer.core.Diag.add(line)
        mainHandler.post { listener.onLog(line) }
    }

    /** Some lines are periodic by nature; never repeat them more than once per window. */
    private fun logOnce(line: String, windowMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPhaseLogMs < windowMs) return
        lastPhaseLogMs = now
        log(line)
    }

    private fun postProgress(progress: TransferProgress) {
        mainHandler.post { listener.onTransferProgress(progress) }
    }

    private fun notifyFailure(fileName: String?, reason: String) {
        log("Error: $reason")
        mainHandler.post { listener.onTransferFailed(fileName, reason) }
    }

    companion object {
        private const val TAG = "NearbyTransfer"
        private const val SERVICE_ID_V2 = "com.airgesture.transfer.P2P.v2"
        private const val SERVICE_ID_V1 = "com.airgesture.transfer.P2P"
        private const val KEY_NAME = "n"
        private const val KEY_MIME = "m"
        private const val KEY_SIZE = "s"
        private const val ACK_TOKEN = "AGFT-ACK"
        private const val MAGIC = 0x41474654
        private const val VERSION = 1
        private const val BUFFER = 64 * 1024
        private const val MAX_STRING_BYTES = 4096
        private const val MAX_NAME_LENGTH = 120
        // Both radios stay on permanently; these timers only govern retries and
        // how often the same informational line may be logged.
        private const val RETRY_MS = 900L
        private const val SEARCH_LOG_MS = 9000L
        /** How long the "loser" of the name comparison waits before dialling. */
        private const val DIAL_GRACE_MS = 1500L
        /** A payload with no update for this long is considered dead. */
        private const val STALL_MS = 25_000L
        private const val STALL_CHECK_MS = 5_000L
        // Documented choice for a one-to-one link; the cluster topology adds
        // multi-connect edge cases we do not need between two phones.
        private val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT
    }
}
