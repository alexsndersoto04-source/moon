package com.airgesture.transfer.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.airgesture.transfer.vision.HandTracker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX analyzer: one dedicated thread, zero-allocation frame conversion,
 * proxy closed immediately (the pipeline never backs up) and only then
 * inference. The tracker is resolved lazily so MediaPipe initialises here, on
 * the camera thread, instead of blocking app start.
 *
 * Also accumulates the live counters the diagnostics HUD displays.
 */
class CameraAnalyzer(
    private val converter: FrameConverter,
    private val trackerProvider: () -> HandTracker,
    private val onResult: (List<NormalizedLandmark>?, Long) -> Unit
) : ImageAnalysis.Analyzer {

    private val frameCounter = AtomicLong(0)
    private val handCounter = AtomicLong(0)
    private val fpsValue = AtomicInteger(0)

    private var tracker: HandTracker? = null
    private var windowStartMs = 0L
    private var windowFrames = 0

    val frames: Long get() = frameCounter.get()
    val handFrames: Long get() = handCounter.get()
    val fps: Int get() = fpsValue.get()
    val conversionFailures: Long get() = converter.failures
    val conversionMode: String get() = converter.lastMode
    val inferenceMs: Long get() = tracker?.lastInferenceMs ?: 0L
    val modelReady: Boolean get() = tracker?.isReady ?: false
    val modelError: String? get() = tracker?.modelError

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

        val engine = tracker ?: trackerProvider().also { tracker = it }
        val landmarks = if (engine.isReady) engine.detect(bitmap, timestampMs) else null

        frameCounter.incrementAndGet()
        if (landmarks != null) handCounter.incrementAndGet()
        updateFps()

        onResult(landmarks, timestampMs)
    }

    private fun updateFps() {
        val now = SystemClock.elapsedRealtime()
        if (windowStartMs == 0L) {
            windowStartMs = now
            windowFrames = 0
            return
        }
        windowFrames++
        val elapsed = now - windowStartMs
        if (elapsed >= FPS_WINDOW_MS) {
            fpsValue.set((windowFrames * 1000L / elapsed).toInt())
            windowStartMs = now
            windowFrames = 0
        }
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val FPS_WINDOW_MS = 1000L
    }
}
