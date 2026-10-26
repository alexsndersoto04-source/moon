package com.moonshare.transfer

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import com.moonshare.core.MoonLog
import com.moonshare.core.sound.SfxPlayer
import com.moonshare.data.media.MediaRepository
import com.moonshare.data.model.FileItem
import com.moonshare.data.model.TransferDirection
import com.moonshare.data.model.TransferSession
import com.moonshare.data.model.TransferTask
import com.moonshare.data.model.TaskState
import com.moonshare.data.settings.MoonSettings
import com.moonshare.data.settings.SettingsStore
import com.moonshare.net.EndpointListener
import com.moonshare.net.ReceiveEndpoint
import com.moonshare.net.SendEndpoint
import com.moonshare.nfc.PairingToken
import com.moonshare.nfc.PairingTokenHolder
import com.moonshare.net.wifi.WifiDirectManager
import com.moonshare.nfc.NfcAvailability
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

/** Lo que la UI pinta. Inmutable a propósito. */
data class EngineState(
    val phase: EnginePhase = EnginePhase.Idle,
    val mode: EngineMode = EngineMode.Idle,
    val statusText: String = "",
    val deviceName: String = "",
    val peers: List<WifiP2pDevice> = emptyList(),
    val linkReady: Boolean = false,
    val peerName: String = "",
    val session: TransferSession? = null,
    val tasks: List<TransferTask> = emptyList(),
    val speedBps: Long = 0L,
    val error: String? = null,
    val isHosting: Boolean = false,
    val canAdvertiseNfc: Boolean = false,
) {
    val busy: Boolean get() = mode != EngineMode.Idle || phase == EnginePhase.Joining || phase == EnginePhase.Handshaking
    val progress: Float
        get() {
            val total = tasks.sumOf { it.size }.coerceAtLeast(1L)
            return tasks.sumOf { it.transferred.coerceAtMost(it.size) }
                .toFloat() / total.toFloat()
        }
    val finished: Boolean get() = tasks.isNotEmpty() && tasks.all { it.state.isFinished }
}

/** Evento puntual: la UI lo usa para disparar la esfera y el pulso háptico. */
data class OrbPulse(val kind: Kind, val itemName: String, val nonce: Long) {
    enum class Kind { FileIn, FileOut, Paired, Failed }
}

/** Oferta entrante esperando que el usuario diga sí o no. */
data class PendingOffer(
    val id: String,
    val fromName: String,
    val tasks: List<TransferTask>,
) {
    val totalBytes: Long get() = tasks.sumOf { it.size }
}

/**
 * Núcleo de MoonShare. dueño del ciclo de vida del enlace, de la cola y del
 * feedback (sonido/haptics). Vive en el proceso (no en la Activity) porque la
 * transferencia tiene que sobrevivir a que el usuario minimice la app: en MIUI,
 * si el dueño del socket es una Activity, te la matan a los 30 segundos.
 */
class TransferEngine(
    private val context: Context,
    private val settings: SettingsStore,
    private val media: MediaRepository,
    private val sfx: SfxPlayer,
) {

    /**
     * Sin [CoroutineExceptionHandler] una excepción dentro de un `scope.launch`
     * va derecha al uncaught handler del proceso y la app se cierra: en la práctica
     * era "pantalla negra y se sale". El motor maneja red y MediaStore, que en ROMs
     * raras tiran de todo, así que acá se loggea, se reporta y SEGUIME andando.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { t, e ->
                MoonLog.e(TAG, "excepción no atendida en $t", e)
            },
    )
    private val wfd = WifiDirectManager(context)

    private val _state = MutableStateFlow(EngineState(deviceName = myName()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _pulses = MutableSharedFlow<OrbPulse>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pulses = _pulses.asSharedFlow()

    private val _offer = MutableStateFlow<PendingOffer?>(null)
    val pendingOffer: StateFlow<PendingOffer?> = _offer.asStateFlow()

    private var receiveEndpoint: ReceiveEndpoint? = null
    private var sendEndpoint: SendEndpoint? = null
    private var hostJob: Job? = null
    private var lastPeerAddress: String? = null
    private var currentSettings: MoonSettings = MoonSettings()

    private val tasks = LinkedHashMap<String, TransferTask>()
    private val tasksLock = Any()
    private val consentGate = MutableStateFlow<List<String>?>(null)

    init {
        scope.launch {
            settings.settings.collect { s ->
                currentSettings = s
                settings.cache(s)
                sfx.enabled = s.waterSfxEnabled
                sfx.volume = s.waterSfxVolume
                sfx.hapticsEnabled = s.hapticsEnabled
                MoonLog.verbose = s.verboseLogs
            }
        }
        scope.launch {
            wfd.events.collect { msg -> _state.update { it.copy(error = msg, phase = EnginePhase.Failed) } }
        }
    }

    val peerName: String get() = _state.value.peerName.ifBlank { "otro teléfono" }

    // ============================================================ recibir
    /**
     * Pone el teléfono disponible: levanta el grupo Wi-Fi Direct, publica el token
     * por NFC y abre el puerto. Desde acá puede recibir sin que el usuario toque nada
     * cuando el otro pega su teléfono.
     */
    fun startReceiving() {
        if (_state.value.isHosting) return
        MoonLog.i(TAG, "abriendo modo recepción")
        _state.update { it.copy(phase = EnginePhase.OpeningRadio, error = null, statusText = "Prendiendo Wi-Fi Direct…") }
        hostJob?.cancel()
        hostJob = scope.launch {
            if (!wfd.open()) {
                _state.update { it.copy(phase = EnginePhase.Failed, statusText = "") }
                return@launch
            }
            val ready = withContext(Dispatchers.IO) {
                var group: android.net.wifi.p2p.WifiP2pGroup? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                wfd.startGroup(currentSettings.prefer5GHz) { g ->
                    group = g
                    latch.countDown()
                }
                latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
                group
            }
            if (ready == null) {
                _state.update {
                    it.copy(
                        phase = EnginePhase.Failed,
                        statusText = "",
                        error = "No se pudo crear la red Wi-Fi Direct. Revisá que el Wi-Fi esté encendido.",
                    )
                }
                return@launch
            }
            val endpoint = ReceiveEndpoint(
                context = context,
                port = currentSettings.listenPort,
                saveToGallery = currentSettings.saveToGallery,
                listener = receiveListener,
            )
            receiveEndpoint = endpoint
            endpoint.start()

            publishToken(ready)

            _state.update {
                it.copy(
                    phase = EnginePhase.Hosting,
                    mode = EngineMode.Idle,
                    isHosting = true,
                    canAdvertiseNfc = true,
                    linkReady = true,
                    statusText = "Esperando el acercamiento",
                    error = null,
                )
            }
            // si el otro lado ya estaba discovered, refrescamos para mostrarlo
            wfd.discover()
        }
    }

    fun stopReceiving() {
        hostJob?.cancel()
        hostJob = null
        receiveEndpoint?.stop()
        receiveEndpoint = null
        PairingTokenHolder.publish(null)
        wfd.removeGroup(silent = true)
        _state.update {
            it.copy(isHosting = false, canAdvertiseNfc = false, linkReady = false, phase = EnginePhase.Idle, statusText = "")
        }
    }

    /** El token que un teléfono cercano lee al pegar el suyo contra este. */
    private fun publishToken(group: android.net.wifi.p2p.WifiP2pGroup) {
        val me = wfd.thisDevice.value
        val address = me?.deviceAddress ?: group.owner?.deviceAddress ?: UNKNOWN_ADDRESS
        val token = PairingToken(
            deviceAddress = address,
            deviceName = myName(),
            port = currentSettings.listenPort,
        )
        PairingTokenHolder.publish(token)
        MoonLog.i(TAG, "token NFC listo addr=$address port=${token.port}")
    }

    // ============================================================ enviar
    /**
     * Manda [items] al teléfono destino. [viaNfc] viene del golpe; si es null se usa
     * el par ya escolhido en la lista de dispositivos.
     */
    fun send(items: List<FileItem>, viaNfc: PairingToken? = null) {
        if (items.isEmpty()) return
        // Los ítems de SAF/DocumentFile a veces reportan size=0: sin el tamaño real
        // el progreso del receptor no tendría denominador.
        val sized = items.map { if (it.size > 0L) it else media.refreshSize(it) }
        if (_state.value.mode == EngineMode.Sending) {
            _state.update { it.copy(error = "Ya hay un envío en curso") }
            return
        }
        val token = viaNfc ?: lastNfcToken()
        val address = token?.deviceAddress ?: pickManualAddress()
        if (address.isNullOrBlank()) {
            _state.update {
                it.copy(
                    phase = EnginePhase.Discovering,
                    error = null,
                    statusText = "Buscando teléfonos cerca… acercá el otro para parear",
                )
            }
            ensureRadioOpen()
            wfd.discover()
            return
        }
        launchSend(sized, address, token?.deviceName.orEmpty(), token?.port ?: currentSettings.listenPort)
    }

    private fun launchSend(items: List<FileItem>, address: String, nameHint: String, port: Int) {
        _state.update {
            it.copy(
                phase = EnginePhase.Joining, mode = EngineMode.Sending, error = null,
                statusText = "Uniendo al grupo de ${nameHint.ifBlank { "el otro teléfono" }}…",
            )
        }
        resetTasks(items, TransferDirection.Send)
        ensureRadioOpen()
        scope.launch {
            // El listener de connect() dispara apenas cuando la *solicitud* se acepta;
            // el enlace real llega después por broadcast. La única fuente de verdad es
            // awaitLink(), así que el callback solo se usa para adelantar el error.
            var joinError: String? = null
            withContext(Dispatchers.IO) {
                wfd.join(address) { _, err -> joinError = err }
            }
            joinError?.let { msg ->
                MoonLog.w(TAG, "join: $msg (igual esperamos el enlace)")
            }
            val link = wfd.awaitLink(35_000)
            if (link == null) {
                failAll("No se formó el enlace Wi-Fi Direct. Aceptá el diálogo del sistema si apareció.")
                return@launch
            }
            lastPeerAddress = address
            _state.update { it.copy(phase = EnginePhase.Handshaking, statusText = "Saludando…") }

            val host = "192.168.49.1" // siempre: el que recibe es el Group Owner
            val endpoint = SendEndpoint(
                context = context,
                host = host,
                port = port,
                deviceName = myName(),
                deviceId = deviceId(),
                listener = sendListener,
            ).also { it.speedLimitKbps = currentSettings.speedLimitKbps }
            sendEndpoint = endpoint

            val failure = withContext(Dispatchers.IO) { endpoint.send(items) }
            sendEndpoint = null
            if (failure != null && failure != "cancelado") {
                failAll(failure)
            } else {
                finishSession()
            }
            cleanupAfterSend()
        }
    }

    private fun cleanupAfterSend() {
        if (!currentSettings.persistGroupAfterSend) {
            wfd.cancelConnect()
        }
        _state.update {
            it.copy(
                phase = EnginePhase.Idle, mode = EngineMode.Idle,
                statusText = "", linkReady = false,
            )
        }
    }

    private fun ensureRadioOpen() {
        if (wfd.thisDevice.value == null) wfd.open()
    }

    private fun lastNfcToken(): PairingToken? =
        nfcHandoffToken?.takeIf { PairingToken.isFresh(it) }

    /** Token que entregó [com.moonshare.nfc.NfcHandoff] hace menos de 5 minutos. */
    @Volatile
    var nfcHandoffToken: PairingToken? = null

    private fun pickManualAddress(): String? {
        val connected = wfd.peers.value.firstOrNull { it.status == WifiP2pDevice.CONNECTED }
        val chosen = _state.value.peers.firstOrNull { it.deviceAddress == lastPeerAddress }
            ?: connected
            ?: wfd.peers.value.firstOrNull()
        return chosen?.deviceAddress
    }

    // ============================================================ cola
    private fun resetTasks(items: List<FileItem>, dir: TransferDirection) {
        synchronized(tasksLock) {
            tasks.clear()
            items.forEach { item ->
                tasks[item.id] = TransferTask(
                    itemId = item.id, name = item.name, size = item.size,
                    mime = item.mime, uri = item.uri,
                    state = if (dir == TransferDirection.Send) TaskState.Queued else TaskState.AwaitingConsent,
                )
            }
        }
        publishTasks()
    }

    private fun publishTasks() {
        val snapshot = synchronized(tasksLock) { tasks.values.toList() }
        _state.update { st ->
            st.copy(
                tasks = snapshot,
                session = st.session?.copy(tasks = snapshot) ?: st.session,
            )
        }
    }

    private fun mutate(id: String, block: (TransferTask) -> TransferTask) {
        synchronized(tasksLock) {
            val t = tasks[id] ?: return
            tasks[id] = block(t)
        }
        publishTasks()
    }

    private fun failAll(reason: String) {
        synchronized(tasksLock) {
            tasks.values.filter { !it.state.isFinished }.forEach {
                tasks[it.itemId] = it.copy(state = TaskState.Failed, error = reason)
            }
        }
        publishTasks()
        _state.update { it.copy(phase = EnginePhase.Failed, mode = EngineMode.Idle, statusText = "", error = reason) }
        scope.launch { pulse(OrbPulse.Kind.Failed, "", 0) }
    }

    private fun finishSession() {
        synchronized(tasksLock) {
            tasks.values.filter { !it.state.isFinished }.forEach {
                tasks[it.itemId] = it.copy(state = TaskState.Canceled, error = "no enviado")
            }
        }
        publishTasks()
        val snapshot = synchronized(tasksLock) { tasks.values.toList() }
        val dir = if (_state.value.mode == EngineMode.Sending) TransferDirection.Send else TransferDirection.Receive
        _state.update {
            it.copy(
                phase = EnginePhase.Done,
                statusText = if (dir == TransferDirection.Send) "Enviado" else "Recibido",
                session = TransferSession(
                    id = UUID.randomUUID().toString().take(8),
                    direction = dir,
                    peerName = it.peerName,
                    startedAtMs = System.currentTimeMillis(),
                    tasks = snapshot,
                    finished = true,
                ),
            )
        }
        scope.launch { delay(2500); if (_state.value.phase == EnginePhase.Done) setIdle() }
    }

    private fun setIdle() {
        if (_state.value.mode == EngineMode.Idle && _state.value.phase == EnginePhase.Done) {
            _state.update { it.copy(phase = if (it.isHosting) EnginePhase.Hosting else EnginePhase.Idle, statusText = "") }
        }
    }

    private suspend fun pulse(kind: OrbPulse.Kind, name: String, size: Long) {
        _pulses.emit(OrbPulse(kind, name, System.nanoTime()))
        when (kind) {
            OrbPulse.Kind.FileIn -> { sfx.onFileReceived(); sfx.tick() }
            OrbPulse.Kind.FileOut -> {
                if (!currentSettings.soundOnReceiveOnly) sfx.onFileSent()
                sfx.tick()
            }
            OrbPulse.Kind.Paired -> { sfx.onPaired() }
            OrbPulse.Kind.Failed -> Unit
        }
    }

    /** Se llama desde la UI cuando alguien pega su teléfono y pareamos con éxito. */
    fun notifyPaired() {
        scope.launch { pulse(OrbPulse.Kind.Paired, "", 0) }
    }

    // ============================================================ consent
    fun respondToOffer(accept: Boolean) {
        val pending = _offer.value ?: return
        val ids = if (accept) pending.tasks.map { it.itemId } else emptyList()
        consentGate.value = ids
        _offer.value = null
        if (!accept) {
            pending.tasks.forEach { mutate(it.itemId) { t -> t.copy(state = TaskState.Rejected) } }
        }
    }

    fun cancelAll() {
        sendEndpoint?.cancel()
        consentGate.value = emptyList()
        _offer.value = null
        synchronized(tasksLock) {
            tasks.values.filter { !it.state.isFinished }.forEach {
                tasks[it.itemId] = it.copy(state = TaskState.Canceled)
            }
        }
        publishTasks()
        _state.update { it.copy(mode = EngineMode.Idle, phase = EnginePhase.Idle, statusText = "") }
    }

    /** Aviso corto para la UI cuando el usuario hizo algo que no se puede cumplir. */
    fun failWith(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun refreshPeers() {
        ensureRadioOpen()
        wfd.discover()
        _state.update { it.copy(peers = wfd.peers.value) }
    }

    val discoveredPeers: StateFlow<List<WifiP2pDevice>> get() = wfd.peers
    val nfcSupported: Boolean get() = NfcAvailability.has(context)

    // ============================================================ listeners
    private val receiveListener = object : EndpointListener {
        override fun onPeerIdentified(name: String, deviceId: String) {
            _state.update { it.copy(peerName = name, mode = EngineMode.Receiving, phase = EnginePhase.Transferring) }
        }

        override fun onTaskQueued(task: TransferTask) {
            synchronized(tasksLock) { tasks[task.itemId] = task }
            publishTasks()
        }

        override fun onProgress(itemId: String, transferred: Long, total: Long, bytesPerSec: Long) {
            mutate(itemId) { it.copy(transferred = transferred, state = TaskState.Streaming) }
            _state.update { it.copy(speedBps = bytesPerSec) }
        }

        override fun onFileFinished(itemId: String, ok: Boolean, error: String?) {
            mutate(itemId) {
                it.copy(
                    state = if (ok) TaskState.Done else TaskState.Failed,
                    error = error,
                    transferred = if (ok) it.size else it.transferred,
                )
            }
            val name = synchronized(tasksLock) { tasks[itemId]?.name } ?: ""
            scope.launch {
                pulse(if (ok) OrbPulse.Kind.FileIn else OrbPulse.Kind.Failed, name, 0)
                if (ok) {
                    val t = synchronized(tasksLock) { tasks[itemId] }
                    if (currentSettings.historyEnabled) {
                        settings.appendHistory(
                            com.moonshare.data.model.HistoryEntry(
                                timestampMs = System.currentTimeMillis(),
                                direction = "Receive", name = name,
                                size = t?.size ?: 0L, peer = peerName, ok = ok,
                            )
                        )
                    }
                }
            }
        }

        override fun onSessionClosed(error: String?) {
            if (error != null) failAll(error) else finishSession()
        }

        override suspend fun awaitConsent(
            fromName: String,
            tasks: List<TransferTask>,
            timeoutMs: Long,
        ): List<String> {
            tasks.forEach { t -> onTaskQueued(t.copy(state = TaskState.AwaitingConsent)) }
            if (currentSettings.autoAccept) {
                _state.update { it.copy(phase = EnginePhase.Transferring, statusText = "Recibiendo de $fromName") }
                return tasks.map { it.itemId }
            }
            consentGate.value = null
            _offer.value = PendingOffer(UUID.randomUUID().toString().take(6), fromName, tasks)
            _state.update { it.copy(phase = EnginePhase.Transferring, statusText = "$fromName quiere enviarte archivos") }
            val decision = withContext(Dispatchers.Default) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    consentGate.value?.let { return@withContext it }
                    delay(120)
                }
                emptyList()
            }
            _offer.value = null
            return decision
        }

        override fun consentTimeoutMs(): Long = currentSettings.receiveTimeoutSec * 1000L
    }

    private val sendListener = object : EndpointListener {
        override fun onPeerIdentified(name: String, deviceId: String) {
            _state.update { it.copy(peerName = name) }
        }

        override fun onProgress(itemId: String, transferred: Long, total: Long, bytesPerSec: Long) {
            mutate(itemId) { it.copy(transferred = transferred, state = TaskState.Streaming) }
            _state.update { it.copy(speedBps = bytesPerSec) }
        }

        override fun onFileFinished(itemId: String, ok: Boolean, error: String?) {
            mutate(itemId) {
                it.copy(
                    state = when {
                        ok -> TaskState.Done
                        error == "rejected" -> TaskState.Rejected
                        else -> TaskState.Failed
                    },
                    error = error,
                    transferred = if (ok) it.size else it.transferred,
                )
            }
            val name = synchronized(tasksLock) { tasks[itemId]?.name } ?: ""
            scope.launch {
                pulse(if (ok) OrbPulse.Kind.FileOut else OrbPulse.Kind.Failed, name, 0)
                if (ok && currentSettings.historyEnabled) {
                    settings.appendHistory(
                        com.moonshare.data.model.HistoryEntry(
                            timestampMs = System.currentTimeMillis(),
                            direction = "Send", name = name,
                            size = 0L, peer = peerName, ok = true,
                        )
                    )
                }
            }
        }

        override fun onSessionClosed(error: String?) = Unit
    }

    // ------------------------------------------------------------------ utils
    private fun myName(): String {
        val nick = currentSettings.deviceNickname.trim()
        if (nick.isNotEmpty()) return nick
        return runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        }.getOrDefault("Moon")
    }

    private fun deviceId(): String = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "moonshare"
    }.getOrDefault("moonshare")

    fun shutdown() {
        stopReceiving()
        sendEndpoint?.cancel()
        wfd.close()
    }

    companion object {
        private const val TAG = "Engine"
        private const val UNKNOWN_ADDRESS = "02:00:00:00:00:00"
    }
}

