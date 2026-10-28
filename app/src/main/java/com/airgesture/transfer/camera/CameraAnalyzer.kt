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

    @Volatile private var probeLocked = false
    @Volatile private var landmarkError: String? = null
    private var lastDiagMs = 0L

    /** True once the probe found which channel order this device delivers. */
    val channelProbe: String
        get() = when {
            !probeLocked -> "auto"
            converter.lastMode.endsWith("(BGR)") -> "BGR"
            else -> "RGB"
        }

    val frames: Long get() = frameCounter.get()
    val handFrames: Long get() = handCounter.get()
    val fps: Int get() = fpsValue.get()
    val conversionFailures: Long get() = converter.failures
    val conversionMode: String get() = converter.lastMode
    val inferenceMs: Long get() = tracker?.lastInferenceMs ?: 0L
    val modelReady: Boolean get() = tracker?.isReady ?: false
    val modelError: String? get() = tracker?.modelError ?: landmarkError

    /**
     * Colour-order probe.
     *
     * CameraX documents `R,G,B,A` for RGBA_8888 and that is what the converter
     * assumes. A handful of OEM camera stacks pack `B,G,R,A` instead, and the
     * symptom is brutal: a perfect preview and zero detections forever. Rather
     * than guess, the analyzer tries both orders on the first frames and keeps
     * whichever one actually finds a hand. Bounded to [PROBE_FRAMES] frames, so
     * the extra inference cost disappears after the first couple of seconds.
     */
    override fun analyze(image: ImageProxy) {
        val timestampMs = image.imageInfo.timestamp / NANOS_PER_MILLI
        var landmarks: List<NormalizedLandmark>? = null

        try {
            val bitmap = runCatching { converter.convert(image) }.getOrNull()
            if (bitmap == null) {
                publishDiag()
                onResult(null, timestampMs)
                return
            }

            val engine = tracker ?: trackerProvider().also { tracker = it }
            landmarks = if (engine.isReady) engine.detect(bitmap, timestampMs) else null

            if (landmarks == null && engine.isReady && frameCounter.get() < PROBE_FRAMES) {
                // Retry the same frame with the opposite channel order before
                // the proxy is closed and the pixels are gone.
                converter.swapChannels = !converter.swapChannels
                val alternate = runCatching { converter.convert(image) }.getOrNull()
                if (alternate != null) {
                    val retry = engine.detect(alternate, timestampMs + PROBE_STEP_MS)
                    if (retry != null) {
                        landmarks = retry
                        probeLocked = true
                        com.airgesture.transfer.core.Diag.add(
                            "Color de cámara detectado: ${converter.lastMode}"
                        )
                    } else if (!probeLocked) {
                        // Nothing either way yet: keep alternating, cheap enough
                        // while the probe window is open.
                        converter.swapChannels = !converter.swapChannels
                    }
                }
            }
        } catch (error: Throwable) {
            landmarkError = error.message
        } finally {
            image.close()
        }

        frameCounter.incrementAndGet()
        if (landmarks != null) handCounter.incrementAndGet()
        updateFps()
        publishDiag()

        onResult(landmarks, timestampMs)
    }

    /** Publishes the pipeline numbers to the copyable diagnostic report. */
    private fun publishDiag() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDiagMs < DIAG_INTERVAL_MS) return
        lastDiagMs = now
        com.airgesture.transfer.core.Diag.setPipeline(
            "cuadros=${frames} manos=${handFrames} fps=${fps} " +
                "modoColor=${converter.lastMode} sonda=${channelProbe} " +
                "fallosConversion=${conversionFailures} " +
                "modelo=${if (modelReady) "ok" else "NO: ${modelError ?: "sin cargar"}"} " +
                "inferencia=${inferenceMs}ms"
        )
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
        /** How many frames may be spent discovering the channel order. */
        private const val PROBE_FRAMES = 90L
        /** Keep MediaPipe timestamps strictly increasing across the probe pair. */
        private const val PROBE_STEP_MS = 1L
        private const val DIAG_INTERVAL_MS = 2_000L
    }
}
