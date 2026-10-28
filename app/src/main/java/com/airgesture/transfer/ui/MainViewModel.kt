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
import com.airgesture.transfer.vision.GestureCalibration
import com.airgesture.transfer.vision.GestureInterpreter
import com.airgesture.transfer.vision.HandTracker
import com.airgesture.transfer.vision.OverlayFrame
import com.airgesture.transfer.vision.ThrustSensor
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val interpreter = GestureInterpreter().apply {
        sensitivity = prefs.sensitivity
        profile = prefs.loadProfile()
    }
    private val calibration = GestureCalibration()
    private var calibrationJob: Job? = null
    private var lastCapturedAttempt = 0
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
    /** Files waiting for the link, oldest first. Nothing is ever dropped. */
    private val queue = ArrayDeque<MediaEntry>()
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

    /**
     * Called when the camera screen goes away. Only the accelerometer is stopped:
     * the Nearby session stays alive so an in-flight transfer can finish and so
     * the peer link survives a trip to the Android settings (e.g. to switch
     * location on). It is torn down in onCleared.
     */
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
        if (interpreter.calibration == null) interpreter.profile = prefs.loadProfile()
    }

    val isCalibrated: Boolean get() = prefs.calibrated
    val shouldOfferCalibration: Boolean get() = !prefs.calibrationAsked

    fun markCalibrationOffered() {
        prefs.calibrationAsked = true
    }

    /** Starts the guided calibration; the engine stops emitting transfer events. */
    fun startCalibration() {
        calibration.start()
        lastCapturedAttempt = 0
        interpreter.calibration = calibration
        publishCalibration(calibration.current)
        // The panel is driven by its own ticker, not by the camera thread: it
        // keeps updating even when the hand leaves the frame.
        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = calibration.current
                if (snapshot.attempt > lastCapturedAttempt && snapshot.phase != GestureCalibration.Phase.PUSHING) {
                    lastCapturedAttempt = snapshot.attempt
                    haptics.confirm()
                }
                publishCalibration(snapshot)
                delay(CALIBRATION_TICK_MS)
            }
        }
    }

    fun cancelCalibration() {
        stopCalibrationEngine()
    }

    private fun stopCalibrationEngine() {
        calibrationJob?.cancel()
        calibrationJob = null
        calibration.cancel()
        interpreter.calibration = null
        _uiState.update { it.copy(calibration = null) }
    }

    /** The overlay's "listo" button: persist the measured profile. */
    fun finishCalibration() {
        val profile = calibration.current.profile
        if (profile != null && profile.calibrated) {
            prefs.saveProfile(profile)
            interpreter.profile = profile
            _events.tryEmit(UiEvent.Message(app.getString(R.string.calibrated_ok)))
        }
        stopCalibrationEngine()
    }

    private fun publishCalibration(state: GestureCalibration.State) {
        val hint = when (state.phase) {
            GestureCalibration.Phase.WAITING -> app.getString(R.string.cal_hint_ready)
            GestureCalibration.Phase.PUSHING -> app.getString(R.string.cal_hint_push)
            GestureCalibration.Phase.SETTLING -> app.getString(R.string.cal_hint_settle)
            GestureCalibration.Phase.DONE -> app.getString(R.string.cal_hint_done)
            GestureCalibration.Phase.IDLE -> app.getString(R.string.cal_hint_ready)
        }
        val handHint = if (state.handVisible || state.phase == GestureCalibration.Phase.DONE) {
            hint
        } else {
            app.getString(R.string.cal_hint_no_hand)
        }
        _uiState.update {
            it.copy(
                calibration = CalibrationUi(
                    attempt = state.attempt,
                    total = state.total,
                    progress = state.progress,
                    livePush = state.livePush,
                    finished = state.phase == GestureCalibration.Phase.DONE,
                    handVisible = state.handVisible,
                    hint = handHint
                )
            )
        }
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
                fist = reading.fistStrength,
                pushZone = reading.pushZone
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
        if (_uiState.value.calibration != null) return
        if (_uiState.value.items.isEmpty()) {
            // Never stay silent: an empty shelf with a working gesture looks
            // exactly like a broken gesture.
            if (event != GestureEvent.HAND_OPENED && event != GestureEvent.NONE) {
                _events.tryEmit(UiEvent.Message(app.getString(R.string.no_files_gesture)))
            }
            return
        }
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

    /** Explicit "send now" button: no gesture involved at all. */
    fun sendSelectedNow() {
        val state = _uiState.value
        val entry = state.items.firstOrNull { it.key == state.selectedUri }
            ?: state.items.firstOrNull()
        if (entry == null) {
            _events.tryEmit(UiEvent.Message(app.getString(R.string.select_file_first)))
            return
        }
        sendItem(entry)
    }

    /**
     * Fail-safe used by the long-press on a file card. Sends now when the link
     * is up; otherwise the file waits in the queue and leaves by itself.
     */
    fun sendItem(entry: MediaEntry) {
        _uiState.update { it.copy(selectedUri = entry.key) }
        enqueue(entry)
    }

    /** Adds to the queue and starts sending when the channel is free. */
    private fun enqueue(entry: MediaEntry) {
        if (queue.any { it.key == entry.key } || _uiState.value.transfer?.fileName == entry.name) {
            _events.tryEmit(UiEvent.Message(app.getString(R.string.already_queued)))
            return
        }
        queue.addLast(entry)
        publishQueue()
        if (!nearby.isConnected) {
            _events.tryEmit(UiEvent.Message(app.getString(R.string.queued_waiting)))
            return
        }
        pump()
    }

    /** Sends the next queued file when nothing is in flight. */
    private fun pump() {
        if (_uiState.value.transfer != null) return
        if (!nearby.isConnected) return
        val next = queue.removeFirstOrNull() ?: return
        publishQueue()
        dispatch(next)
    }

    private fun publishQueue() {
        _uiState.update { it.copy(queuedCount = queue.size) }
    }

    /** The user pressed Cancelar: stop the payload, drop what was waiting. */
    fun cancelTransfer() {
        val cancelled = nearby.cancelCurrent()
        queue.clear()
        publishQueue()
        _uiState.update { it.copy(transfer = null) }
        haptics.tick()
        _events.tryEmit(
            UiEvent.Message(app.getString(if (cancelled) R.string.transfer_cancelled else R.string.nothing_to_cancel))
        )
    }

    private fun sendSelected() {
        if (_uiState.value.calibration != null) return
        val state = _uiState.value
        val entry = state.items.firstOrNull { it.key == state.selectedUri }
            ?: state.items.firstOrNull()
            ?: return
        enqueue(entry)
    }

    /** The card the effect should show: whatever is leaving the phone. */
    fun selectedEntry(): MediaEntry? {
        val state = _uiState.value
        return state.items.firstOrNull { it.key == state.selectedUri }
            ?: state.items.firstOrNull()
    }

    private fun dispatch(entry: MediaEntry) {
        if (nearby.isBusy) {
            // Put it back at the head: the current send is about to finish.
            queue.addFirst(entry)
            publishQueue()
            _events.tryEmit(UiEvent.Message(app.getString(R.string.transfer_in_progress)))
            return
        }
        if (!nearby.send(entry)) {
            // Never drop the user's file: it goes back to the head of the queue.
            queue.addFirst(entry)
            publishQueue()
            _events.tryEmit(UiEvent.Message(app.getString(R.string.transfer_not_started)))
            return
        }
        haptics.confirm()
        _events.tryEmit(UiEvent.Handoff(entry.name))
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
        // Connected: anything the user queued leaves now, in order.
        if (state == NearbyState.CONNECTED) pump()
    }

    override fun onTransferProgress(progress: TransferProgress) {
        _uiState.update { it.copy(transfer = progress) }
    }

    override fun onFileSent(fileName: String) {
        _uiState.update { it.copy(transfer = null) }
        haptics.confirm()
        _events.tryEmit(UiEvent.Sent(fileName))
        pump()
    }

    override fun onFileReceived(fileName: String, uri: Uri) {
        _uiState.update { it.copy(transfer = null) }
        haptics.tick()
        _events.tryEmit(UiEvent.Received(fileName))
    }

    override fun onLog(line: String) {
        _uiState.update { state ->
            val next = (state.logLines + line).takeLast(MAX_LOG_LINES)
            state.copy(logLines = next)
        }
    }

    override fun onTransferFailed(fileName: String?, reason: String) {
        _uiState.update { it.copy(transfer = null) }
        _events.tryEmit(UiEvent.Message(reason))
    }

    companion object {
        private const val CALIBRATION_TICK_MS = 90L
        private const val STATS_INTERVAL_MS = 500L
        private const val MAX_LOG_LINES = 3
    }
}
