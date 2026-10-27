package com.airgesture.transfer.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/**
 * MediaPipe Hands wrapper in **VIDEO** running mode.
 *
 * VIDEO mode is deliberately chosen over LIVE_STREAM: it is synchronous, so the
 * camera analyzer thread can reuse a single bitmap and a single graph input
 * buffer with zero queuing. On a Redmi 9C that removes the async pipeline's
 * backlog spikes and keeps the inference cadence predictable (~15-25 FPS at
 * 480p on CPU).
 */
class HandTracker(context: Context) {

    private val landmarker: HandLandmarker? = runCatching {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU) // GPU delegate is unstable on Mali-G35
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(DETECTION_CONFIDENCE)
            .setMinHandPresenceConfidence(PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(TRACKING_CONFIDENCE)
            .build()

        HandLandmarker.createFromOptions(context, options)
    }.onFailure { Log.e(TAG, "HandLandmarker init failed", it) }.getOrNull()

    val isReady: Boolean get() = landmarker != null

    private var lastTimestamp = 0L
    private var consecutiveErrors = 0

    /**
     * Runs one inference synchronously. Must be called from a single background
     * thread. Returns the 21 landmarks of the most confident hand, or null when
     * no hand is present (or the graph rejected the frame).
     */
    fun detect(bitmap: Bitmap, frameTimestampMs: Long): List<NormalizedLandmark>? {
        val engine = landmarker ?: return null
        // MediaPipe requires strictly increasing timestamps in milliseconds.
        val timestamp = maxOf(frameTimestampMs, lastTimestamp + 1L, SystemClock.uptimeMillis())
        lastTimestamp = timestamp

        return runCatching {
            val image = BitmapImageBuilder(bitmap).build()
            val result = engine.detectForVideo(image, timestamp)
            consecutiveErrors = 0
            result.landmarks().firstOrNull()?.takeIf { it.size >= LANDMARK_COUNT }
        }.onFailure {
            consecutiveErrors++
            if (consecutiveErrors == 1 || consecutiveErrors % 30 == 0) {
                Log.w(TAG, "Hand inference failed ($consecutiveErrors consecutive)", it)
            }
        }.getOrNull()
    }

    fun close() {
        runCatching { landmarker?.close() }
    }

    companion object {
        private const val TAG = "HandTracker"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val LANDMARK_COUNT = 21
        private const val DETECTION_CONFIDENCE = 0.55f
        private const val PRESENCE_CONFIDENCE = 0.55f
        private const val TRACKING_CONFIDENCE = 0.55f
    }
}
