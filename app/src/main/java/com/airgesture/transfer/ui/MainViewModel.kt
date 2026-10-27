package com.airgesture.transfer.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airgesture.transfer.R
import com.airgesture.transfer.camera.CameraAnalyzer
import com.airgesture.transfer.camera.FrameConverter
import com.airgesture.transfer.core.AirGestureApp
import com.airgesture.transfer.core.GestureEvent
import com.airgesture.transfer.core.GestureState
import com.airgesture.transfer.media.MediaEntry
import com.airgesture.transfer.media.MediaKind
import com.airgesture.transfer.transfer.NearbyState
import com.airgesture.transfer.transfer.NearbyTransferManager
import com.airgesture.transfer.transfer.TransferProgress
import com.airgesture.transfer.util.Haptics
import com.airgesture.transfer.util.Permissions
import com.airgesture.transfer.vision.GestureInterpreter
import com.airgesture.transfer.vision.HandTracker
import com.airgesture.transfer.vision.OverlayFrame
import com.airgesture.transfer.vision.ThrustSensor
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single owner of the vision pipeline, the media shelf and the P2P session.
 *
 * Threading contract:
 *  - [analyzer] runs on the camera executor thread: pure computation, no views.
 *  - Gesture *events* hop to [viewModelScope] (main) because they mutate state.
 *  - Overlay frames are volatile and conflated, so a slow UI thread can never
 *    build a backlog or stall the camera.
 */
class MainViewModel(application: Application) : AndroidViewModel(application), NearbyTransferManager.Listener {

    private val app = application as AirGestureApp
    private val repository = app.mediaRepository
    private val prefs = app.prefs
    private val haptics = Haptics(application)

    /** Created on the camera thread on first use — never on the main thread. */
    @Volatile
    private var handTracker: HandTracker? = null

    private fun tracker(): HandTracker =
        handTracker ?: HandTracker(getApplication()).also { handTracker = it }

    private val converter = FrameConverter()
    private val interpreter = GestureInterpreter().apply { sensitivity = prefs.sensitivity }
    private val thrustSensor = ThrustSensor(application)
    private val nearby = NearbyTransferManager(application, this)

    val analyzer = CameraAnalyzer(converter, ::tracker, ::onLandmarks)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private val _frames = MutableSharedFlow<OverlayFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames = _frames.asSharedFlow()

    private val _stats = MutableStateFlow(TrackingStats())
    val stats = _stats.asStateFlow()

    private var lastStatsAtMs = 0L
    private var lastLandmarkCount = 0

    private val _events = MutableSharedFlow<UiEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private var loadJob: Job? = null
    private var pendingSend: MediaEntry? = null
    private var sessionActive = false
    private var nearbyStarted = false
    private var initialLoadDone = false
    private var lastGestureState = GestureState.IDLE
    private var lastHandVisible = false

    // ------------------------------------------------------------- lifecycle

    fun startSession() {
        if (!Permissions.essentialsGranted(app)) return

        if (!sessionActive) {
            sessionActive = true
            thrustSensor.start()
        }
        if (!nearbyStarted) {
            nearbyStarted = true
            nearby.start()
        }
        if (!initialLoadDone) {
            initialLoadDone = true
            load(_uiState.value.kind)
        } else if (_uiState.value.items.isEmpty() && Permissions.mediaGranted(app)) {
            load(_uiState.value.kind)
        }
    }

    fun stopSession() {
        if (!sessionActive) return
        sessionActive = false
        thrustSensor.stop()
    }

    override fun onCleared() {
        loadJob?.cancel()
        nearby.stop()
        thrustSensor.release()
        handTracker?.close()
        handTracker = null
        converter.release()
        super.onCleared()
    }

    // --------------------------------------------------------------- gallery

    /**
     * Re-reads user settings that affect running components. Called when the
     * activity resumes, so returning from the settings screen applies instantly.
     */
    fun applyPreferences() {
        interpreter.sensitivity = prefs.sensitivity
    }

    fun selectKind(kind: MediaKind) {
        if (_uiState.value.kind == kind) return
        _uiState.update { it.copy(kind = kind, loading = true, selectedUri = null) }
        load(kind)
    }

    fun selectItem(entry: MediaEntry) {
        _uiState.update { it.copy(selectedUri = entry.key) }
        haptics.tick()
    }

    private fun load(kind: MediaKind) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val items = repository.load(kind)
            if (_uiState.value.kind != kind) return@launch
            _uiState.update { state ->
                state.copy(
                    items = items,
                    loading = false,
                    selectedUri = if (items.any { it.key == state.selectedUri }) {
                        state.selectedUri
                    } else {
                        items.firstOrNull()?.key
                    }
                )
            }
            if (items.isEmpty() && !Permissions.mediaGranted(app)) {
                _events.tryEmit(UiEvent.Message(app.getString(R.string.no_media_permission)))
            }
        }
    }

    // ---------------------------------------------------------------- vision

    /** Called on the camera analyzer thread — must stay allocation-light. */
    private fun onLandmarks(landmarks: List<NormalizedLandmark>?, timestampMs: Long) {
        val reading = interpreter.update(
            landmarks = landmarks,
            forwardAccel = thrustSensor.forwardAccel,
            nowMs = SystemClock.elapsedRealtime()
        )

        _frames.tryEmit(
            OverlayFrame(
                landmarks = landmarks,
                state = reading.state,
                thrust = reading.thrustStrength,
                fist = reading.fistStrength
            )
        )

        lastLandmarkCount = landmarks?.size ?: 0
        publishStats(reading)

        if (reading.state != lastGestureState || reading.handVisible != lastHandVisible) {
            lastGestureState = reading.state
            lastHandVisible = reading.handVisible
            _uiState.update { it.copy(gestureState = reading.state, handVisible = reading.handVisible) }
        }

        if (reading.event != GestureEvent.NONE) {
            val event = reading.event
            viewModelScope.launch { handleGesture(event) }
        }
    }

    private suspend fun handleGesture(event: GestureEvent) {
        when (event) {
            GestureEvent.FIST_CLOSED -> {
                val state = _uiState.value
                val entry = state.items.firstOrNull { it.key == state.selectedUri }
                    ?: state.items.firstOrNull()
                    ?: return
                _uiState.update { it.copy(selectedUri = entry.key) }
                haptics.tick()
            }

            GestureEvent.SWIPE_NEXT -> moveSelection(1)
            GestureEvent.SWIPE_PREV -> moveSelection(-1)
            GestureEvent.THROW -> sendSelected()
            GestureEvent.HAND_OPENED, GestureEvent.NONE -> Unit
        }
    }

    private fun moveSelection(delta: Int) {
        val state = _uiState.value
        if (state.items.size < 2) return
        val current = state.items.indexOfFirst { it.key == state.selectedUri }.coerceAtLeast(0)
        val next = ((current + delta) % state.items.size + state.items.size) % state.items.size
        _uiState.update { it.copy(selectedUri = state.items[next].key) }
        haptics.tick()
    }

    private fun publishStats(reading: com.airgesture.transfer.vision.GestureInterpreter.Reading) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsAtMs < STATS_INTERVAL_MS) return
        lastStatsAtMs = now
        _stats.value = TrackingStats(
            fps = analyzer.fps,
            inferenceMs = analyzer.inferenceMs,
            frames = analyzer.frames,
            handFrames = analyzer.handFrames,
            conversionFailures = analyzer.conversionFailures,
            conversionMode = analyzer.conversionMode,
            handVisible = reading.handVisible,
            landmarks = lastLandmarkCount,
            closure = reading.fistStrength,
            push = reading.thrustStrength,
            modelReady = analyzer.modelReady,
            modelError = analyzer.modelError
        )
    }

    // -------------------------------------------------------------- transfer

    /** Fail-safe used by the long-press on a file card. */
    fun sendItem(entry: MediaEntry) {
        _uiState.update { it.copy(selectedUri = entry.key) }
        if (nearby.isConnected) {
            dispatch(entry)
        } else {
            pendingSend = entry
            _events.tryEmit(UiEvent.Message(app.getString(R.string.waiting_peer)))
        }
    }

    private fun sendSelected() {
        if (_uiState.value.transfer != null) return
        val state = _uiState.value
        val entry = state.items.firstOrNull { it.key == state.selectedUri }
            ?: state.items.firstOrNull()
            ?: return

        if (nearby.isConnected) {
            dispatch(entry)
        } else {
            pendingSend = entry
            _events.tryEmit(UiEvent.Message(app.getString(R.string.waiting_peer)))
        }
    }

    private fun dispatch(entry: MediaEntry) {
        if (nearby.isBusy) {
            _events.tryEmit(UiEvent.Message(app.getString(R.string.transfer_in_progress)))
            return
        }
        if (!nearby.send(entry)) {
            _events.tryEmit(UiEvent.Message(app.getString(R.string.transfer_not_started)))
            return
        }
        haptics.confirm()
        _uiState.update {
            it.copy(
                transfer = TransferProgress(
                    fileName = entry.name,
                    bytesTransferred = 0L,
                    totalBytes = entry.sizeBytes,
                    outgoing = true
                )
            )
        }
    }

    override fun onStateChanged(state: NearbyState, detail: String?) {
        _uiState.update {
            it.copy(
                nearbyState = state,
                peerName = nearby.peerName ?: detail?.takeIf { state == NearbyState.CONNECTED }
            )
        }
        if (state == NearbyState.CONNECTED) {
            val queued = pendingSend
            if (queued != null) {
                pendingSend = null
                dispatch(queued)
            }
        }
    }

    override fun onTransferProgress(progress: TransferProgress) {
        _uiState.update { it.copy(transfer = progress) }
    }

    override fun onFileSent(fileName: String) {
        pendingSend = null
        _uiState.update { it.copy(transfer = null) }
        haptics.confirm()
        _events.tryEmit(UiEvent.Sent(fileName))
    }

    override fun onFileReceived(fileName: String, uri: Uri) {
        _uiState.update { it.copy(transfer = null) }
        haptics.tick()
        _events.tryEmit(UiEvent.Received(fileName))
    }

    override fun onTransferFailed(fileName: String?, reason: String) {
        pendingSend = null
        _uiState.update { it.copy(transfer = null) }
        _events.tryEmit(UiEvent.Message(reason))
    }

    companion object {
        private const val STATS_INTERVAL_MS = 500L
    }
}
