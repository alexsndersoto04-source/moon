package com.airgesture.transfer.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.airgesture.transfer.core.Diag
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
    private var lastFrameAtMs = 0L
    private var fpsEma = 0f

    @Volatile private var probeLocked = false
    private var probeAttempts = 0
    @Volatile private var landmarkError: String? = null
    private var lastDiagMs = 0L

    /** True once the probe found which channel order this device delivers. */
    val channelProbe: String
        get() = when {
            !probeLocked -> "auto"
            converter.swapChannels -> "BGR"
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
     * CameraX documents `R,G,B,A` for RGBA_8888, but the analyser's own first
     * pass produced **zero** hand detections on a Redmi 9C (Android 10) while the
     * swapped order detected in 64 % of frames — that device delivers the plane as
     * `B,G,R,A`. Rather than guess per OEM, the probe tries both orders on the
     * same frame and locks onto whichever one finds a hand.
     *
     * Two rules keep it cheap, because the first version doubled every inference
     * for the whole session and pinned the phone at one frame per second:
     *  1. **Any** detection locks the probe, including a first-pass one.
     *  2. The window is a handful of frames, not ninety. If neither order finds a
     *     hand, the spec-correct order is restored and normal operation resumes.
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
            if (!engine.isReady) {
                landmarkError = engine.modelError ?: "modelo no cargado"
                publishDiag()
                onResult(null, timestampMs)
                return
            }

            val input = converter.fitTo(INFERENCE_MAX_SIDE) ?: bitmap
            landmarks = engine.detect(input, timestampMs)

            if (landmarks != null) {
                // The order currently in use works: stop probing for good.
                probeLocked = true
            } else if (!probeLocked && probeDue()) {
                // Flip the order and retry the very same pixels before closing.
                probeAttempts++
                converter.swapChannels = !converter.swapChannels
                val alternate = runCatching { converter.convert(image) }.getOrNull()
                val alternateInput = alternate?.let { converter.fitTo(INFERENCE_MAX_SIDE) }
                if (alternateInput != null) {
                    val retry = engine.detect(alternateInput, timestampMs + PROBE_STEP_MS)
                    if (retry != null) {
                        landmarks = retry
                        probeLocked = true
                        Diag.add("Color de cámara fijado: ${converter.lastMode}")
                    }
                }
                if (landmarks == null && probeAttempts < PROBE_ATTEMPTS) {
                    // Keep alternating inside the opening burst so the very next
                    // frame starts from the other order.
                    converter.swapChannels = !converter.swapChannels
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

    /**
     * The probe must never give up permanently: if it ran its opening burst
     * while nobody had a hand in front of the camera, locking to the wrong order
     * would reproduce the original "never detects anything" bug forever. After
     * the burst it simply retries on every [PROBE_RETRY_EVERY]th frame, which
     * costs one extra inference about once a second.
     */
    private fun probeDue(): Boolean {
        if (probeAttempts < PROBE_ATTEMPTS) return true
        return frameCounter.get() % PROBE_RETRY_EVERY == 0L
    }

    /** Publishes the pipeline numbers to the copyable diagnostic report. */
    private fun publishDiag() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDiagMs < DIAG_INTERVAL_MS) return
        lastDiagMs = now
        Diag.setPipeline(
            "cuadros=${frames} manos=${handFrames} fps=${fps} " +
                "silencio=${silenceSeconds()}s " +
                "modoColor=${converter.lastMode} sonda=${channelProbe} " +
                "fallosConversion=${conversionFailures} " +
                "modelo=${if (modelReady) "ok" else "NO: ${modelError ?: "sin cargar"}"} " +
                "inferencia=${inferenceMs}ms"
        )
    }

    /**
     * Instantaneous frame rate with a light exponential average.
     *
     * The previous version counted frames inside a fixed window and could report
     * 0 while the pipeline was in fact running at 2 fps, which is exactly the
     * kind of number that hides a problem. This one is direct: it measures the
     * interval between two real frames.
     */
    private fun updateFps() {
        val now = SystemClock.elapsedRealtime()
        val previous = lastFrameAtMs
        lastFrameAtMs = now
        if (previous == 0L) return
        val interval = (now - previous).coerceAtLeast(1L)
        val instant = 1000f / interval
        fpsEma = if (fpsEma <= 0f) instant else fpsEma * 0.75f + instant * 0.25f
        fpsValue.set(fpsEma.toInt())
    }

    /** Seconds since the last frame reached the analyser (stall detector). */
    private fun silenceSeconds(): Long {
        val last = lastFrameAtMs
        if (last == 0L) return -1L
        return (SystemClock.elapsedRealtime() - last) / 1000L
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        /**
         * Long side used for inference. MediaPipe resizes internally to ~224 px,
         * so this is free accuracy and a 3-4x speedup on a budget SoC.
         */
        private const val INFERENCE_MAX_SIDE = 320
        /** Hard cap on frames spent discovering the channel order (2 per frame). */
        private const val PROBE_ATTEMPTS = 8
        /** After the opening burst, probe once every this many frames. */
        private const val PROBE_RETRY_EVERY = 12L
        /** Keep MediaPipe timestamps strictly increasing across the probe pair. */
        private const val PROBE_STEP_MS = 1L
        private const val DIAG_INTERVAL_MS = 2_000L
    }
}
