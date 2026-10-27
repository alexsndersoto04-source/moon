package com.moonshare.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.moonshare.core.MoonLog
import com.moonshare.core.sound.SfxPlayer
import com.moonshare.data.media.MediaRepository
import com.moonshare.data.model.FileItem
import com.moonshare.data.model.HistoryEntry
import com.moonshare.data.model.TaskState
import com.moonshare.data.model.TransferDirection
import com.moonshare.data.model.TransferSession
import com.moonshare.data.model.TransferTask
import com.moonshare.data.settings.MoonSettings
import com.moonshare.data.settings.SettingsStore
import com.moonshare.net.nearby.NearbyDevice
import com.moonshare.net.nearby.NearbyEvents
import com.moonshare.net.nearby.NearbyTransport
import com.moonshare.nfc.NfcAvailability
import com.moonshare.nfc.PairingToken
import com.moonshare.nfc.PairingTokenHolder
import com.moonshare.util.SaveSink
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class EnginePhase {
    Idle,
    OpeningRadio,
    Hosting,
    Discovering,
    Joining,
    Handshaking,
    Transferring,
    Done,
    Failed,
}

enum class EngineMode { Idle, Sending, Receiving }

/** Estado inmutable que dibuja la interfaz. */
data class EngineState(
    val phase: EnginePhase = EnginePhase.Idle,
    val mode: EngineMode = EngineMode.Idle,
    val statusText: String = "",
    val deviceName: String = "",
    val linkReady: Boolean = false,
    val peerName: String = "",
    val session: TransferSession? = null,
    val tasks: List<TransferTask> = emptyList(),
    val speedBps: Long = 0L,
    val error: String? = null,
    val isHosting: Boolean = false,
    val canAdvertiseNfc: Boolean = false,
) {
    val busy: Boolean
        get() = mode != EngineMode.Idle ||
            phase == EnginePhase.OpeningRadio ||
            phase == EnginePhase.Joining ||
            phase == EnginePhase.Handshaking ||
            phase == EnginePhase.Discovering

    val progress: Float
        get() {
            val total = tasks.sumOf { it.size }.coerceAtLeast(1L)
            return tasks.sumOf { it.transferred.coerceAtMost(it.size) }
                .toFloat() / total.toFloat()
        }

    val finished: Boolean get() = tasks.isNotEmpty() && tasks.all { it.state.isFinished }
}

/** Evento breve para sonido/vibración; ya no representa una esfera ni un adorno. */
data class TransferEvent(val kind: Kind, val itemName: String, val nonce: Long) {
    enum class Kind { FileIn, FileOut, Paired, Failed }
}

data class PendingOffer(
    val id: String,
    val fromName: String,
    val tasks: List<TransferTask>,
) {
    val totalBytes: Long get() = tasks.sumOf { it.size }
}

@Serializable
private data class NearbyFileMeta(
    val payloadId: Long,
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
)

@Serializable
private data class NearbyControl(
    val type: String,
    val fromName: String = "",
    val tapNonce: String = "",
    val files: List<NearbyFileMeta> = emptyList(),
    val acceptedIds: List<String> = emptyList(),
    val clientId: String = "",
    val ok: Boolean = false,
    val error: String? = null,
)

/**
 * Motor de MoonShare usando Nearby Connections como transporte principal.
 *
 * NFC solo identifica el acercamiento y entrega un nonce efímero. Nearby se
 * encarga de descubrir, negociar el medio y transportar archivos; no hay grupo
 * Wi-Fi Direct creado a mano, IP supuesta ni socket propio.
 */
class TransferEngine(
    private val context: Context,
    private val settings: SettingsStore,
    private val media: MediaRepository,
    private val sfx: SfxPlayer,
) : NearbyEvents {

    private var currentSettings: MoonSettings = MoonSettings()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                MoonLog.e(TAG, "excepción no atendida en el motor", e)
            },
    )

    private val transport = NearbyTransport(context, this)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(EngineState(deviceName = myName()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _pulses = MutableSharedFlow<TransferEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pulses = _pulses.asSharedFlow()

    private val _offer = MutableStateFlow<PendingOffer?>(null)
    val pendingOffer: StateFlow<PendingOffer?> = _offer.asStateFlow()

    private val _nearbyDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<NearbyDevice>> = _nearbyDevices.asStateFlow()

    private val tasks = LinkedHashMap<String, TransferTask>()
    private val tasksLock = Any()

    @Volatile
    var nfcHandoffToken: PairingToken? = null

    private var availabilityDesired = false
    private var availabilityNonce: String = ""
    private var discoveringForSend = false

    private var activeEndpointId: String? = null
    private var activePeerName: String = ""
    private var activeTapNonce: String = ""
    private var connectionEstablished = false
    private var finishingSession = false

    private data class PendingSend(
        val items: List<FileItem>,
        val token: PairingToken?,
        val targetEndpointId: String?,
    )

    private var pendingSend: PendingSend? = null
    private var preparedFiles: List<PreparedFile> = emptyList()
    private var sendQueue: List<NearbyFileMeta> = emptyList()
    private var sendIndex: Int = 0
    private var currentSendingPayloadId: Long? = null

    private data class PreparedFile(
        val item: FileItem,
        val meta: NearbyFileMeta,
        val payload: Payload,
        val descriptor: ParcelFileDescriptor,
    )

    private var incomingMetasByPayload = emptyMap<Long, NearbyFileMeta>()
    private var incomingMetasById = emptyMap<String, NearbyFileMeta>()
    private var incomingPayloads = mutableMapOf<Long, Payload>()
    private var acceptedIncomingIds: Set<String> = emptySet()
    private var remoteSessionDone: Boolean = false
    private var completedIncomingIds = mutableSetOf<String>()
    private var consentTimeoutJob: Job? = null

    private val transferStartedAt = mutableMapOf<String, Long>()
    private val transferSamples = mutableMapOf<String, Pair<Long, Long>>()

    init {
        scope.launch {
            settings.settings.collect { s ->
                currentSettings = s
                settings.cache(s)
                sfx.enabled = s.waterSfxEnabled
                sfx.volume = s.waterSfxVolume
                sfx.hapticsEnabled = s.hapticsEnabled
                MoonLog.verbose = s.verboseLogs
                if (!s.nfcBumpEnabled) {
                    PairingTokenHolder.publish(null)
                    _state.update { it.copy(canAdvertiseNfc = false) }
                } else if (availabilityDesired && PairingTokenHolder.current == null && availabilityNonce.isNotBlank()) {
                    publishAvailabilityToken()
                    _state.update { it.copy(canAdvertiseNfc = true) }
                }
            }
        }
    }

    val peerName: String get() = _state.value.peerName.ifBlank { activePeerName.ifBlank { "otro teléfono" } }
    val nfcSupported: Boolean get() = NfcAvailability.has(context)

    // ------------------------------------------------------------ disponibilidad
    fun startReceiving() {
        if (availabilityDesired && (_state.value.isHosting || _state.value.phase == EnginePhase.OpeningRadio)) return
        availabilityDesired = true
        if (availabilityNonce.isBlank()) availabilityNonce = newSessionNonce()
        _state.update {
            it.copy(
                phase = EnginePhase.OpeningRadio,
                error = null,
                statusText = "Activando disponibilidad…",
                canAdvertiseNfc = currentSettings.nfcBumpEnabled,
            )
        }
        publishAvailabilityToken()
        transport.startAdvertising(myName(), availabilityNonce)
    }

    fun stopReceiving() {
        availabilityDesired = false
        availabilityNonce = ""
        PairingTokenHolder.publish(null)
        transport.stopAdvertising()
        if (_state.value.mode == EngineMode.Receiving) {
            transport.disconnect(activeEndpointId)
            clearConnectionLocked()
        }
        _state.update {
            it.copy(
                isHosting = false,
                canAdvertiseNfc = false,
                phase = if (it.mode == EngineMode.Idle) EnginePhase.Idle else it.phase,
                statusText = "",
            )
        }
    }

    private fun publishAvailabilityToken() {
        if (!currentSettings.nfcBumpEnabled || availabilityNonce.isBlank()) return
        PairingTokenHolder.publish(
            PairingToken(
                // Nearby ya no usa una dirección Wi-Fi. Este campo identifica el
                // canal; la identidad real y efímera es el nonce.
                deviceAddress = "nearby",
                deviceName = myName(),
                port = MoonSettings.DEFAULT_PORT,
                nonce = availabilityNonce,
            ),
        )
    }

    // ------------------------------------------------------------ envío
    fun sendToDevice(items: List<FileItem>, token: PairingToken) = send(items, token)

    fun sendToNearbyEndpoint(items: List<FileItem>, endpointId: String) {
        sendInternal(items, token = null, targetEndpointId = endpointId)
    }

    fun send(items: List<FileItem>, viaNfc: PairingToken? = null) {
        sendInternal(items, token = viaNfc ?: lastNfcToken(), targetEndpointId = null)
    }

    private fun sendInternal(items: List<FileItem>, token: PairingToken?, targetEndpointId: String?) {
        if (items.isEmpty()) {
            failWith("Elegí primero qué enviar")
            return
        }
        if (_state.value.mode == EngineMode.Sending) {
            failWith("Ya hay un envío en curso")
            return
        }
        val sized = items.map { if (it.size > 0L) it else media.refreshSize(it) }
        closePreparedFiles()
        preparedFiles = emptyList()
        sendQueue = emptyList()
        sendIndex = 0
        pendingSend = PendingSend(sized, token, targetEndpointId)
        resetTasks(sized, TransferDirection.Send)

        // Nearby puede anunciar y descubrir, pero para conectar con fiabilidad se
        // concentra en el envío. La disponibilidad se reanuda al terminar.
        if (availabilityDesired) transport.stopAdvertising()
        _state.update {
            it.copy(
                phase = EnginePhase.Discovering,
                mode = EngineMode.Sending,
                isHosting = false,
                linkReady = false,
                error = null,
                statusText = "Buscando el teléfono que acabás de acercar…",
                peerName = token?.deviceName.orEmpty(),
            )
        }
        discoveringForSend = true
        transport.startDiscovery()
        tryConnectPendingSend()
    }

    private fun tryConnectPendingSend() {
        val pending = pendingSend ?: return
        if (activeEndpointId != null || _state.value.mode != EngineMode.Sending) return
        val target = when {
            pending.targetEndpointId != null ->
                _nearbyDevices.value.firstOrNull { it.endpointId == pending.targetEndpointId }
            pending.token != null -> _nearbyDevices.value.firstOrNull { device ->
                (pending.token.nonce.isNotBlank() && device.sessionNonce == pending.token.nonce) ||
                    device.deviceName.equals(pending.token.deviceName, ignoreCase = true)
            }
            else -> null
        } ?: return

        activeEndpointId = target.endpointId
        activePeerName = target.deviceName
        activeTapNonce = pending.token?.nonce.orEmpty()
        _state.update {
            it.copy(
                phase = EnginePhase.Joining,
                statusText = "Conectando con ${target.deviceName}…",
                peerName = target.deviceName,
            )
        }
        discoveringForSend = false
        transport.requestConnection(target.endpointId, myName(), activeTapNonce)
    }

    fun prepareToSend() {
        if (_state.value.mode == EngineMode.Sending) return
        discoveringForSend = true
        transport.startDiscovery()
    }

    fun refreshPeers() {
        prepareToSend()
    }

    // ------------------------------------------------------------ consentimiento y cancelación
    fun respondToOffer(accept: Boolean) {
        val pending = _offer.value ?: return
        val endpoint = activeEndpointId ?: return
        consentTimeoutJob?.cancel()
        _offer.value = null
        if (!accept) {
            pending.tasks.forEach { mutate(it.itemId) { task -> task.copy(state = TaskState.Rejected) } }
            transport.sendBytes(endpoint, encode(NearbyControl(type = "DECLINE")))
            _state.update { it.copy(statusText = "Rechazaste la recepción", phase = EnginePhase.Hosting) }
            scope.launch {
                delay(350)
                transport.disconnect(endpoint)
            }
            return
        }
        acceptedIncomingIds = pending.tasks.map { it.itemId }.toSet()
        _state.update { it.copy(phase = EnginePhase.Transferring, statusText = "Recibiendo de ${pending.fromName}") }
        transport.sendBytes(
            endpoint,
            encode(NearbyControl(type = "ACCEPT", acceptedIds = acceptedIncomingIds.toList())),
        )
    }

    fun cancelAll() {
        consentTimeoutJob?.cancel()
        val endpoint = activeEndpointId
        val wasSending = _state.value.mode == EngineMode.Sending
        synchronized(tasksLock) {
            tasks.values.filter { !it.state.isFinished }.forEach {
                tasks[it.itemId] = it.copy(state = TaskState.Canceled)
            }
        }
        publishTasks()
        closePreparedFiles()
        pendingSend = null
        transport.disconnect(endpoint)
        transport.stopDiscovery()
        discoveringForSend = false
        clearConnectionLocked()
        _state.update {
            it.copy(
                mode = EngineMode.Idle,
                phase = if (availabilityDesired) EnginePhase.Hosting else EnginePhase.Idle,
                isHosting = availabilityDesired && !wasSending,
                statusText = "",
                speedBps = 0L,
            )
        }
        if (availabilityDesired) resumeAvailability()
    }

    fun failWith(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun clearError() = _state.update {
        it.copy(
            error = null,
            phase = when {
                it.phase != EnginePhase.Failed -> it.phase
                it.isHosting || availabilityDesired -> EnginePhase.Hosting
                else -> EnginePhase.Idle
            },
            mode = if (it.phase == EnginePhase.Failed) EngineMode.Idle else it.mode,
            statusText = if (it.phase == EnginePhase.Failed && availabilityDesired) "Disponible" else it.statusText,
        )
    }

    fun notifyPaired() {
        scope.launch { pulse(TransferEvent.Kind.Paired, "", 0) }
    }

    fun shutdown() {
        availabilityDesired = false
        transport.stopAll()
        closePreparedFiles()
    }

    // ------------------------------------------------------------ NearbyEvents
    override fun onAdvertisingStarted() {
        _state.update {
            it.copy(
                phase = if (it.mode == EngineMode.Idle) EnginePhase.Hosting else it.phase,
                mode = it.mode,
                isHosting = true,
                canAdvertiseNfc = currentSettings.nfcBumpEnabled,
                statusText = "Disponible: acercá el otro teléfono",
                error = null,
            )
        }
        publishAvailabilityToken()
    }

    override fun onDiscoveryStarted() {
        if (_state.value.mode == EngineMode.Sending) {
            _state.update { it.copy(phase = EnginePhase.Discovering, statusText = "Acercá los dos teléfonos…") }
        }
    }

    override fun onEndpointFound(device: NearbyDevice) {
        _nearbyDevices.update { devices ->
            (devices.filterNot { it.endpointId == device.endpointId } + device)
                .sortedBy { it.deviceName.lowercase() }
        }
        tryConnectPendingSend()
    }

    override fun onEndpointLost(endpointId: String) {
        _nearbyDevices.update { devices -> devices.filterNot { it.endpointId == endpointId } }
    }

    override fun onConnectionInitiated(endpointId: String, peerName: String, tapNonce: String, incoming: Boolean) {
        if (activeEndpointId != null && activeEndpointId != endpointId) {
            transport.rejectConnection(endpointId)
            return
        }
        // Si este teléfono estaba buscando para enviar y el otro también inició,
        // se conserva la conexión saliente ya elegida; la entrante duplicada no.
        if (!incoming && _state.value.mode != EngineMode.Sending) {
            transport.rejectConnection(endpointId)
            return
        }
        activeEndpointId = endpointId
        activePeerName = peerName
        activeTapNonce = tapNonce
        connectionEstablished = false
        _state.update {
            it.copy(
                peerName = peerName,
                phase = EnginePhase.Handshaking,
                mode = if (incoming && it.mode != EngineMode.Sending) EngineMode.Receiving else it.mode,
                statusText = "Conectando con $peerName…",
                error = null,
            )
        }
        // Aceptar la conexión Nearby no acepta archivos: eso se decide después,
        // automáticamente si viene del toque NFC o con la oferta visible si no.
        transport.acceptConnection(endpointId)
    }

    override fun onConnectionEstablished(endpointId: String) {
        if (endpointId != activeEndpointId) return
        connectionEstablished = true
        _state.update { it.copy(linkReady = true, statusText = "Conectado con $activePeerName") }
        if (_state.value.mode == EngineMode.Sending) {
            prepareAndSendOffer(endpointId)
        } else {
            // Concentrar el enlace en la transferencia y reanudar al terminar.
            transport.stopAdvertising()
            _state.update { it.copy(isHosting = false, statusText = "Esperando archivos de $activePeerName") }
        }
    }

    override fun onConnectionRejected(endpointId: String) {
        if (endpointId != activeEndpointId) return
        if (_state.value.mode == EngineMode.Sending) {
            failAll("El otro teléfono no completó la conexión.")
        }
        clearConnectionLocked()
    }

    override fun onDisconnected(endpointId: String) {
        if (endpointId != activeEndpointId) return
        val wasFinishing = finishingSession
        val allReceivedDone = acceptedIncomingIds.isNotEmpty() &&
            completedIncomingIds.containsAll(acceptedIncomingIds)
        val allSentDone = synchronized(tasksLock) {
            tasks.isNotEmpty() && tasks.values.all { it.state == TaskState.Done }
        }
        clearConnectionLocked()
        when {
            wasFinishing || allReceivedDone || allSentDone -> finishSession()
            _state.value.mode != EngineMode.Idle && !finishingSession ->
                failAll("Se perdió la conexión con el otro teléfono.")
            availabilityDesired -> {
                _state.update { it.copy(phase = EnginePhase.Hosting, isHosting = true, statusText = "Disponible") }
                resumeAvailability()
            }
        }
    }

    override fun onPayloadReceived(endpointId: String, payload: Payload) {
        if (endpointId != activeEndpointId) return
        when (payload.type) {
            Payload.Type.BYTES -> handleControl(payload.asBytes()?.let { String(it, Charsets.UTF_8) }.orEmpty())
            Payload.Type.FILE -> {
                val meta = incomingMetasByPayload[payload.id]
                if (meta == null) {
                    MoonLog.w(TAG, "archivo Nearby sin manifiesto payload=${payload.id}")
                    return
                }
                incomingPayloads[payload.id] = payload
                transferStartedAt[meta.id] = System.nanoTime()
            }
            else -> Unit
        }
    }

    override fun onPayloadUpdate(endpointId: String, update: PayloadTransferUpdate) {
        if (endpointId != activeEndpointId) return
        val meta = incomingMetasByPayload[update.payloadId]
            ?: preparedFiles.firstOrNull { it.meta.payloadId == update.payloadId }?.meta
            ?: return
        when (update.status) {
            PayloadTransferUpdate.Status.SUCCESS -> {
                if (_state.value.mode == EngineMode.Receiving) {
                    val payload = incomingPayloads.remove(update.payloadId) ?: return
                    persistReceivedFile(meta, payload)
                } else {
                    mutate(meta.id) { it.copy(transferred = it.size, state = TaskState.Streaming) }
                }
            }
            PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                if (_state.value.mode == EngineMode.Receiving) {
                    incomingPayloads.remove(update.payloadId)
                    mutate(meta.id) { it.copy(state = TaskState.Failed, error = "La transferencia del archivo se interrumpió") }
                    sendResult(meta.id, ok = false, error = "La transferencia se interrumpió")
                } else if (currentSendingPayloadId == update.payloadId) {
                    mutate(meta.id) { it.copy(state = TaskState.Failed, error = "No se pudo enviar el archivo") }
                    closePrepared(update.payloadId)
                    currentSendingPayloadId = null
                    sendNextFile()
                }
            }
            PayloadTransferUpdate.Status.IN_PROGRESS -> {
                val transferred = update.bytesTransferred.coerceAtLeast(0L)
                val now = System.nanoTime()
                val speed = speedFor(meta.id, transferred, now)
                mutate(meta.id) { it.copy(transferred = transferred, state = TaskState.Streaming) }
                _state.update { it.copy(speedBps = speed, phase = EnginePhase.Transferring) }
            }
            else -> Unit
        }
    }

    override fun onOperationFailed(operation: String, message: String) {
        MoonLog.w(TAG, "$operation falló: $message")
        when {
            _state.value.mode == EngineMode.Sending && !connectionEstablished ->
                failAll("No se pudo $operation: $message")
            operation.contains("disponibilidad") && availabilityDesired ->
                _state.update {
                    it.copy(
                        phase = EnginePhase.Failed,
                        isHosting = false,
                        error = "No se pudo $operation: $message",
                        statusText = "",
                    )
                }
            _state.value.mode != EngineMode.Idle ->
                failAll("No se pudo $operation: $message")
            else -> _state.update { it.copy(error = "No se pudo $operation: $message") }
        }
    }

    // ------------------------------------------------------------ flujo de control
    private fun prepareAndSendOffer(endpointId: String) {
        val pending = pendingSend ?: return
        _state.update { it.copy(phase = EnginePhase.Handshaking, statusText = "Preparando archivos…") }
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                pending.items.mapNotNull { item -> prepareFile(item) }
            }
            if (prepared.isEmpty()) {
                failAll("No se pudo abrir ninguno de los archivos elegidos. Revisá que sigan en el teléfono.")
                transport.disconnect(endpointId)
                return@launch
            }
            preparedFiles = prepared
            sendQueue = prepared.map { it.meta }
            sendIndex = 0
            // Ajusta tareas a tamaños reales leídos (también habilita archivos de 0 B).
            synchronized(tasksLock) {
                prepared.forEach { p ->
                    tasks[p.meta.id] = tasks[p.meta.id]?.copy(size = p.meta.size, name = p.meta.name, mime = p.meta.mime)
                        ?: TransferTask(p.meta.id, p.meta.name, p.meta.size, p.meta.mime, p.item.uri)
                }
            }
            publishTasks()
            _state.update { it.copy(phase = EnginePhase.Handshaking, statusText = "Esperando que $activePeerName reciba…") }
            transport.sendBytes(
                endpointId,
                encode(
                    NearbyControl(
                        type = "OFFER",
                        fromName = myName(),
                        tapNonce = pending.token?.nonce.orEmpty().ifBlank { activeTapNonce },
                        files = sendQueue,
                    ),
                ),
            )
        }
    }

    private fun prepareFile(item: FileItem): PreparedFile? = runCatching {
        val uri = Uri.parse(item.uri)
        val digest = MessageDigest.getInstance("SHA-256")
        var actualSize = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                actualSize += read
            }
        } ?: error("sin acceso al archivo")
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("sin descriptor del archivo")
        val payload = Payload.fromFile(descriptor)
        val actualItem = item.copy(size = actualSize)
        PreparedFile(
            item = actualItem,
            meta = NearbyFileMeta(
                payloadId = payload.id,
                id = item.id,
                name = item.name,
                size = actualSize,
                mime = item.mime,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            ),
            payload = payload,
            descriptor = descriptor,
        )
    }.onFailure {
        MoonLog.e(TAG, "no se pudo preparar ${item.name}", it)
        mutate(item.id) { task -> task.copy(state = TaskState.Failed, error = "No se pudo abrir el archivo") }
    }.getOrNull()

    private fun handleControl(text: String) {
        val control = runCatching { json.decodeFromString<NearbyControl>(text) }.getOrNull() ?: return
        when (control.type) {
            "OFFER" -> handleOffer(control)
            "ACCEPT" -> {
                if (_state.value.mode != EngineMode.Sending) return
                acceptedIncomingIds = control.acceptedIds.toSet() // reutilizado como conjunto remoto aceptado
                sendQueue = sendQueue.filter { it.id in acceptedIncomingIds }
                sendIndex = 0
                if (sendQueue.isEmpty()) {
                    handleRemoteDecline()
                } else {
                    preparedFiles.filterNot { it.meta.id in acceptedIncomingIds }.forEach { closePrepared(it.meta.payloadId) }
                    preparedFiles = preparedFiles.filter { it.meta.id in acceptedIncomingIds }
                    _state.update { it.copy(phase = EnginePhase.Transferring, statusText = "Enviando a $activePeerName") }
                    sendNextFile()
                }
            }
            "DECLINE" -> handleRemoteDecline()
            "RESULT" -> {
                if (_state.value.mode != EngineMode.Sending) return
                val meta = preparedFiles.firstOrNull { it.meta.id == control.clientId }?.meta ?: return
                closePrepared(meta.payloadId)
                if (control.ok) {
                    mutate(meta.id) { it.copy(state = TaskState.Done, transferred = it.size) }
                    val task = synchronized(tasksLock) { tasks[meta.id] }
                    scope.launch {
                        pulse(TransferEvent.Kind.FileOut, meta.name, meta.size)
                        if (currentSettings.historyEnabled && task != null) {
                            settings.appendHistory(
                                HistoryEntry(
                                    timestampMs = System.currentTimeMillis(),
                                    direction = "Send",
                                    name = meta.name,
                                    size = task.size,
                                    peer = activePeerName,
                                    ok = true,
                                ),
                            )
                        }
                    }
                } else {
                    mutate(meta.id) {
                        it.copy(state = TaskState.Failed, error = control.error ?: "El receptor no pudo guardar el archivo")
                    }
                }
                currentSendingPayloadId = null
                sendNextFile()
            }
            "DONE" -> {
                remoteSessionDone = true
                if (acceptedIncomingIds.isNotEmpty() && completedIncomingIds.containsAll(acceptedIncomingIds)) {
                    finishSession()
                }
            }
        }
    }

    private fun handleRemoteDecline() {
        synchronized(tasksLock) {
            tasks.values.filter { !it.state.isFinished }.forEach {
                tasks[it.itemId] = it.copy(state = TaskState.Rejected)
            }
        }
        publishTasks()
        closePreparedFiles()
        _state.update {
            it.copy(phase = EnginePhase.Done, statusText = "El otro teléfono no aceptó los archivos")
        }
        val endpoint = activeEndpointId
        scope.launch {
            delay(300)
            transport.disconnect(endpoint)
            finishSession()
        }
    }

    private fun handleOffer(control: NearbyControl) {
        val tasksOffer = control.files.map { meta ->
            TransferTask(
                itemId = meta.id,
                name = meta.name,
                size = meta.size,
                mime = meta.mime,
                uri = "",
                state = TaskState.AwaitingConsent,
            )
        }
        synchronized(tasksLock) {
            tasks.clear()
            tasksOffer.forEach { tasks[it.itemId] = it }
        }
        publishTasks()
        incomingMetasByPayload = control.files.associateBy { it.payloadId }
        incomingMetasById = control.files.associateBy { it.id }
        completedIncomingIds.clear()
        remoteSessionDone = false
        incomingPayloads.clear()
        acceptedIncomingIds = emptySet()

        _state.update {
            it.copy(
                mode = EngineMode.Receiving,
                phase = EnginePhase.Transferring,
                peerName = control.fromName.ifBlank { activePeerName },
                statusText = "${control.fromName.ifBlank { activePeerName }} quiere enviarte archivos",
                error = null,
            )
        }

        val tapConsent = PairingTokenHolder.hasFreshTapConsent(activeTapNonce)
            .also { if (it) MoonLog.i(TAG, "el toque NFC autorizó esta recepción") }
        if (tapConsent || currentSettings.autoAccept) {
            acceptedIncomingIds = control.files.map { it.id }.toSet()
            transport.sendBytes(
                activeEndpointId ?: return,
                encode(NearbyControl(type = "ACCEPT", acceptedIds = acceptedIncomingIds.toList())),
            )
            _state.update { it.copy(statusText = "Recibiendo de ${control.fromName.ifBlank { activePeerName }}") }
            return
        }

        _offer.value = PendingOffer(UUID.randomUUID().toString().take(8), control.fromName.ifBlank { activePeerName }, tasksOffer)
        consentTimeoutJob?.cancel()
        consentTimeoutJob = scope.launch {
            delay(currentSettings.receiveTimeoutSec * 1000L)
            if (_offer.value != null) respondToOffer(false)
        }
    }

    private fun sendNextFile() {
        val endpoint = activeEndpointId ?: return
        if (sendIndex >= sendQueue.size) {
            transport.sendBytes(endpoint, encode(NearbyControl(type = "DONE")))
            finishSession()
            scope.launch {
                delay(500)
                transport.disconnect(endpoint)
                clearConnectionLocked()
                resumeAvailability()
            }
            return
        }
        val meta = sendQueue[sendIndex++]
        val prepared = preparedFiles.firstOrNull { it.meta.id == meta.id } ?: run {
            sendNextFile()
            return
        }
        currentSendingPayloadId = meta.payloadId
        transferStartedAt[meta.id] = System.nanoTime()
        mutate(meta.id) { it.copy(state = TaskState.Streaming) }
        transport.sendPayload(endpoint, prepared.payload)
    }

    private fun persistReceivedFile(meta: NearbyFileMeta, payload: Payload) {
        scope.launch {
            val endpoint = activeEndpointId
            val result = withContext(Dispatchers.IO) { savePayload(meta, payload) }
            if (result == null) {
                completedIncomingIds += meta.id
                mutate(meta.id) { it.copy(state = TaskState.Done, transferred = it.size) }
                scope.launch { pulse(TransferEvent.Kind.FileIn, meta.name, meta.size) }
                if (currentSettings.historyEnabled) {
                    settings.appendHistory(
                        HistoryEntry(
                            timestampMs = System.currentTimeMillis(),
                            direction = "Receive",
                            name = meta.name,
                            size = meta.size,
                            peer = activePeerName,
                            ok = true,
                        ),
                    )
                }
                sendResult(meta.id, ok = true, error = null)
            } else {
                mutate(meta.id) { it.copy(state = TaskState.Failed, error = result) }
                sendResult(meta.id, ok = false, error = result)
            }
            if (acceptedIncomingIds.isNotEmpty() && completedIncomingIds.containsAll(acceptedIncomingIds)) {
                if (remoteSessionDone || endpoint == null) finishSession()
            }
        }
    }

    /** Copia el Payload.File a su destino final verificando tamaño y SHA-256. */
    private fun savePayload(meta: NearbyFileMeta, payload: Payload): String? {
        val filePayload = payload.asFile() ?: return "Nearby no entregó el contenido del archivo"
        val input = runCatching {
            filePayload.asParcelFileDescriptor()?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                ?: filePayload.asJavaFile()?.inputStream()
        }.getOrNull() ?: return "No se pudo leer el archivo recibido"

        val opened = SaveSink.open(context, meta.name, meta.mime, meta.size, currentSettings.saveToGallery)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        return try {
            input.use { source ->
                SaveSink.streamFor(context, opened).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != meta.size) {
                SaveSink.discard(context, opened)
                "Archivo incompleto: llegaron $written de ${meta.size} bytes"
            } else if (!actualHash.equals(meta.sha256, ignoreCase = true)) {
                SaveSink.discard(context, opened)
                "El archivo llegó dañado: la verificación de integridad no coincide"
            } else {
                SaveSink.publish(context, opened, written)
                null
            }
        } catch (e: Exception) {
            runCatching { SaveSink.discard(context, opened) }
            e.message ?: "No se pudo guardar el archivo"
        }
    }

    private fun sendResult(clientId: String, ok: Boolean, error: String?) {
        val endpoint = activeEndpointId ?: return
        transport.sendBytes(
            endpoint,
            encode(NearbyControl(type = "RESULT", clientId = clientId, ok = ok, error = error)),
        )
    }

    // ------------------------------------------------------------ tareas/utilidades
    private fun resetTasks(items: List<FileItem>, direction: TransferDirection) {
        synchronized(tasksLock) {
            tasks.clear()
            items.forEach { item ->
                tasks[item.id] = TransferTask(
                    itemId = item.id,
                    name = item.name,
                    size = item.size,
                    mime = item.mime,
                    uri = item.uri,
                    state = if (direction == TransferDirection.Send) TaskState.Queued else TaskState.AwaitingConsent,
                )
            }
        }
        publishTasks()
    }

    private fun publishTasks() {
        val snapshot = synchronized(tasksLock) { tasks.values.toList() }
        _state.update { state ->
            state.copy(tasks = snapshot, session = state.session?.copy(tasks = snapshot) ?: state.session)
        }
    }

    private fun mutate(id: String, block: (TransferTask) -> TransferTask) {
        synchronized(tasksLock) {
            val task = tasks[id] ?: return
            tasks[id] = block(task)
        }
        publishTasks()
    }

    private fun failAll(reason: String) {
        consentTimeoutJob?.cancel()
        synchronized(tasksLock) {
            tasks.values.filter { !it.state.isFinished }.forEach {
                tasks[it.itemId] = it.copy(state = TaskState.Failed, error = reason)
            }
        }
        publishTasks()
        closePreparedFiles()
        pendingSend = null
        _state.update {
            it.copy(phase = EnginePhase.Failed, mode = EngineMode.Idle, statusText = "", error = reason, speedBps = 0L)
        }
        scope.launch { pulse(TransferEvent.Kind.Failed, "", 0) }
    }

    private fun finishSession() {
        if (finishingSession && _state.value.phase == EnginePhase.Done) return
        finishingSession = true
        val direction = _state.value.mode
        synchronized(tasksLock) {
            tasks.values.filter { !it.state.isFinished }.forEach {
                tasks[it.itemId] = it.copy(state = TaskState.Canceled, error = "no completado")
            }
        }
        publishTasks()
        val snapshot = synchronized(tasksLock) { tasks.values.toList() }
        _state.update {
            it.copy(
                phase = EnginePhase.Done,
                mode = direction,
                statusText = if (direction == EngineMode.Sending) "Enviado" else "Recibido",
                session = TransferSession(
                    id = UUID.randomUUID().toString().take(8),
                    direction = if (direction == EngineMode.Sending) TransferDirection.Send else TransferDirection.Receive,
                    peerName = it.peerName,
                    startedAtMs = System.currentTimeMillis(),
                    tasks = snapshot,
                    finished = true,
                ),
                speedBps = 0L,
            )
        }
        scope.launch {
            delay(3200)
            if (_state.value.phase == EnginePhase.Done) {
                finishingSession = false
                clearConnectionLocked()
                _state.update {
                    it.copy(
                        mode = EngineMode.Idle,
                        phase = if (availabilityDesired) EnginePhase.Hosting else EnginePhase.Idle,
                        isHosting = availabilityDesired,
                        statusText = if (availabilityDesired) "Disponible" else "",
                    )
                }
                resumeAvailability()
            }
        }
    }

    private fun clearConnectionLocked() {
        activeEndpointId = null
        activePeerName = ""
        activeTapNonce = ""
        connectionEstablished = false
        consentTimeoutJob?.cancel()
        _offer.value = null
        incomingPayloads.clear()
        remoteSessionDone = false
        _state.update { it.copy(linkReady = false) }
    }

    private fun resumeAvailability() {
        if (!availabilityDesired) return
        if (availabilityNonce.isBlank()) availabilityNonce = newSessionNonce()
        publishAvailabilityToken()
        transport.startAdvertising(myName(), availabilityNonce)
    }

    private fun closePrepared(payloadId: Long) {
        val prepared = preparedFiles.firstOrNull { it.meta.payloadId == payloadId } ?: return
        runCatching { prepared.descriptor.close() }
    }

    private fun closePreparedFiles() {
        preparedFiles.forEach { runCatching { it.descriptor.close() } }
    }

    private fun lastNfcToken(): PairingToken? =
        nfcHandoffToken?.takeIf { PairingToken.isFresh(it) }

    private suspend fun pulse(kind: TransferEvent.Kind, name: String, size: Long) {
        _pulses.emit(TransferEvent(kind, name, System.nanoTime()))
        when (kind) {
            TransferEvent.Kind.FileIn -> { sfx.onFileReceived(); sfx.tick() }
            TransferEvent.Kind.FileOut -> {
                if (!currentSettings.soundOnReceiveOnly) sfx.onFileSent()
                sfx.tick()
            }
            TransferEvent.Kind.Paired -> sfx.onPaired()
            TransferEvent.Kind.Failed -> Unit
        }
    }

    private fun speedFor(id: String, transferred: Long, nowNano: Long): Long {
        val previous = transferSamples[id]
        transferSamples[id] = transferred to nowNano
        if (previous == null) return 0L
        val bytes = transferred - previous.first
        val seconds = (nowNano - previous.second).coerceAtLeast(1L) / 1_000_000_000.0
        return if (bytes <= 0 || seconds <= 0.0) 0L else (bytes / seconds).toLong()
    }

    private fun encode(control: NearbyControl): String = json.encodeToString(control)

    private fun newSessionNonce(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun myName(): String {
        val nick = runCatching { currentSettings.deviceNickname.trim() }.getOrDefault("")
        if (nick.isNotEmpty()) return nick
        return runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        }.getOrDefault("Moon")
    }

    private companion object {
        const val TAG = "Engine"
        const val DEFAULT_BUFFER_SIZE = 128 * 1024
    }
}
