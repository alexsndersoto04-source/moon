package com.airgesture.transfer.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.airgesture.transfer.vision.HandTracker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * CameraX analyzer. Runs on a single dedicated thread, converts the frame with
 * a zero-allocation converter, closes the proxy immediately (so the pipeline
 * never backs up) and only then runs inference.
 *
 * The tracker is resolved through a provider so that MediaPipe — a ~7 MB graph
 * that takes a few hundred milliseconds to build — is initialised on this
 * background thread on the first usable frame instead of blocking app start.
 *
 * Frame drops are intentional: the strategy is KEEP_ONLY_LATEST, so on a slow
 * device the preview keeps its native frame rate while detection simply samples
 * fewer frames.
 */
class CameraAnalyzer(
    private val converter: FrameConverter,
    private val trackerProvider: () -> HandTracker,
    private val onResult: (List<NormalizedLandmark>?, Long) -> Unit
) : ImageAnalysis.Analyzer {

    private var tracker: HandTracker? = null

    override fun analyze(image: ImageProxy) {
        val timestampMs = image.imageInfo.timestamp / NANOS_PER_MILLI
        val bitmap = try {
            converter.convert(image)
        } catch (error: Throwable) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            onResult(null, timestampMs)
            return
        }

        // Resolved once, on this thread, on the first frame that carries pixels.
        val engine = tracker ?: trackerProvider().also { tracker = it }
        val landmarks = if (engine.isReady) engine.detect(bitmap, timestampMs) else null
        onResult(landmarks, timestampMs)
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
