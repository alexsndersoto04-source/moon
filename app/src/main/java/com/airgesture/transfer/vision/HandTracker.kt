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
 * MediaPipe Hands wrapper in **VIDEO** running mode (synchronous, so the camera
 * thread can reuse one bitmap with no async queue).
 *
 * CPU delegate on purpose: the GPU delegate needs an EGL context and has a
 * history of hard crashes on Mali/PowerVR budget SoCs. A slower, always-working
 * detector beats a fast one that kills the process.
 */
class HandTracker(context: Context) {

    private val landmarker: HandLandmarker? = runCatching {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
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
    val modelError: String? get() = if (landmarker == null) "modelo no cargado" else null

    /** Duration of the last inference, milliseconds (shown in diagnostics). */
    @Volatile
    var lastInferenceMs: Long = 0L
        private set

    private var lastTimestamp = 0L
    private var consecutiveErrors = 0

    /**
     * One synchronous inference. Call from a single background thread.
     * Returns the 21 landmarks of the best hand, or null when none is present.
     */
    fun detect(bitmap: Bitmap, frameTimestampMs: Long): List<NormalizedLandmark>? {
        val engine = landmarker ?: return null

        val timestamp = maxOf(frameTimestampMs, lastTimestamp + 1L, SystemClock.uptimeMillis())
        lastTimestamp = timestamp

        val started = SystemClock.elapsedRealtime()
        val result = runCatching {
            val image = BitmapImageBuilder(bitmap).build()
            engine.detectForVideo(image, timestamp)
        }.onFailure {
            consecutiveErrors++
            if (consecutiveErrors == 1 || consecutiveErrors % 50 == 0) {
                Log.w(TAG, "Hand inference failed ($consecutiveErrors)", it)
            }
        }.getOrNull()

        lastInferenceMs = SystemClock.elapsedRealtime() - started

        return result?.landmarks()?.firstOrNull()?.takeIf { it.size >= LANDMARK_COUNT }
    }

    fun close() {
        runCatching { landmarker?.close() }
    }

    companion object {
        private const val TAG = "HandTracker"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val LANDMARK_COUNT = 21
        private const val DETECTION_CONFIDENCE = 0.5f
        private const val PRESENCE_CONFIDENCE = 0.5f
        private const val TRACKING_CONFIDENCE = 0.5f
    }
}
