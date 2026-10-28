package com.airgesture.transfer.ui

import com.airgesture.transfer.core.GestureState
import com.airgesture.transfer.media.MediaEntry
import com.airgesture.transfer.media.MediaKind
import com.airgesture.transfer.transfer.NearbyState
import com.airgesture.transfer.transfer.TransferProgress

/** Everything the single screen needs, in one immutable snapshot. */
data class MainUiState(
    val kind: MediaKind = MediaKind.IMAGES,
    val items: List<MediaEntry> = emptyList(),
    val loading: Boolean = true,
    val selectedUri: String? = null,
    val gestureState: GestureState = GestureState.IDLE,
    val handVisible: Boolean = false,
    val nearbyState: NearbyState = NearbyState.IDLE,
    val peerName: String? = null,
    val transfer: TransferProgress? = null,
    /** Last few transport log lines, oldest first. Shown in the UI on purpose. */
    val logLines: List<String> = emptyList(),
    /** Files waiting for the link. */
    val queuedCount: Int = 0,
    /** Non-null while the guided calibration overlay is visible. */
    val calibration: CalibrationUi? = null
)

/** Minimal projection of the calibration state for the overlay. */
data class CalibrationUi(
    val attempt: Int,
    val total: Int,
    val progress: Float,
    val livePush: Float,
    val finished: Boolean,
    val handVisible: Boolean,
    val hint: String
)

/**
 * Live numbers for the diagnostics overlay (long-press the title to show).
 * Updated at most twice per second so it never competes with the camera thread.
 */
data class TrackingStats(
    val fps: Int = 0,
    val inferenceMs: Long = 0L,
    val frames: Long = 0L,
    val handFrames: Long = 0L,
    val conversionFailures: Long = 0L,
    val conversionMode: String = "—",
    val handVisible: Boolean = false,
    val landmarks: Int = 0,
    val closure: Float = 0f,
    val push: Float = 0f,
    val modelReady: Boolean = false,
    val modelError: String? = null
)

/** One-shot notifications for the status line. */
sealed interface UiEvent {
    data class Sent(val name: String) : UiEvent
    data class Received(val name: String) : UiEvent
    data class Message(val text: String) : UiEvent
    /** A file is leaving the phone: the moment the hand-off effect belongs to. */
    data class Handoff(val name: String) : UiEvent
}
