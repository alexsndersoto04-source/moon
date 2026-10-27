package com.airgesture.transfer.vision

import com.airgesture.transfer.core.GestureEvent
import com.airgesture.transfer.core.GestureState
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pose-agnostic gesture engine.
 *
 * Design rule: **no required pose**. The only thing the user has to do is move
 * the hand toward the phone, however they like — open palm, half fist, fingers
 * spread. Nothing is "hold this exact shape for N frames".
 *
 * What is measured:
 *  - `closure` 0..1 — how curled the fingers are (scale invariant, no calibration).
 *    It is only used for feedback and for the soft "grab" state; the throw does
 *    **not** require it.
 *  - `push` 0..1 — forward-motion confidence fused from four independent cues:
 *      a) apparent hand growth   (getting closer to the lens)
 *      b) MediaPipe depth rate   (z decreasing)
 *      c) palm speed             (any direction, catches quick flicks)
 *      d) accelerometer forward  (the phone itself thrusting toward the peer)
 *    Each cue is measured as the *peak instantaneous rate* inside a short
 *    window, so a fast flick that returns to rest still counts.
 *
 * Re-arming: after a throw the detector disarms itself and only re-arms when the
 * push confidence falls back below `REARM_LEVEL`. That is what stops a single
 * gesture from firing twice, without asking the user to perform any "reset pose".
 *
 * Everything here is pure Kotlin with no per-frame allocations beyond one small
 * sample and one reading, so it is safe on the camera thread.
 */
class GestureInterpreter {

    data class Reading(
        val state: GestureState,
        val event: GestureEvent,
        val handVisible: Boolean,
        val fistStrength: Float,
        val openStrength: Float,
        val thrustStrength: Float,
        val palmX: Float,
        val palmY: Float,
        val handSize: Float
    )

    private data class Sample(
        val timeMs: Long,
        val x: Float,
        val y: Float,
        val size: Float,
        val depth: Float
    )

    private val history = ArrayDeque<Sample>(HISTORY_CAPACITY)

    private var state: GestureState = GestureState.IDLE
    private var throwFlashUntilMs = 0L

    private var lastThrowMs = 0L
    private var lastSwipeMs = 0L
    private var lastFistEventMs = 0L

    private var pushArmed = true
    private var missingFrames = 0
    private var closed = false
    private var firstSeenMs = 0L
    private var lastBurst = false

    private var smoothedClosure = 0f
    private var smoothedPush = 0f

    private var palmX = 0f
    private var palmY = 0f
    private var palmSize = 0f

    /** 0 = strict, 1 = eager. Read from the tuning screen / prefs. */
    var sensitivity: Float = DEFAULT_SENSITIVITY
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    fun reset() {
        history.clear()
        firstSeenMs = 0L
        lastBurst = false
        state = GestureState.IDLE
        throwFlashUntilMs = 0L
        pushArmed = true
        missingFrames = 0
        closed = false
        smoothedClosure = 0f
        smoothedPush = 0f
    }

    /**
     * @param landmarks 21 MediaPipe landmarks, or null when no hand was found.
     * @param forwardAccel metres/second² along the device's forward axis
     *        (positive = phone pushed away from the user).
     */
    fun update(
        landmarks: List<NormalizedLandmark>?,
        forwardAccel: Float,
        nowMs: Long
    ): Reading {
        if (landmarks == null || landmarks.size < LANDMARK_COUNT) {
            return onHandMissing(nowMs)
        }
        missingFrames = 0
        if (firstSeenMs == 0L) firstSeenMs = nowMs

        val palm = palmCentre(landmarks)
        val size = max(distance(landmarks[WRIST], landmarks[MIDDLE_MCP]), MIN_HAND_SIZE)
        palmX = palm[0]
        palmY = palm[1]
        palmSize = size

        val closure = closure(landmarks, palm)

        pushSample(nowMs, palm, size, landmarks[MIDDLE_MCP].z())

        val rawPush = pushConfidence(nowMs, forwardAccel)
        smoothedPush += PUSH_SMOOTHING * (rawPush - smoothedPush)
        smoothedClosure += CLOSURE_SMOOTHING * (closure - smoothedClosure)

        var event = GestureEvent.NONE
        val strictness = 1.15f - 0.45f * sensitivity

        // ---- Soft grab: pure feedback, never a requirement -------------------
        if (!closed && smoothedClosure > GRAB_LEVEL && nowMs - lastFistEventMs > 400L) {
            closed = true
            lastFistEventMs = nowMs
            event = GestureEvent.FIST_CLOSED
        } else if (closed && smoothedClosure < RELEASE_LEVEL) {
            closed = false
            event = GestureEvent.HAND_OPENED
        }

        // ---- Throw: any pose, four fused cues -------------------------------
        // Warm-up: the hand must have been visible for a moment before a push
        // counts, otherwise simply raising the hand would read as a throw.
        val warm = nowMs - firstSeenMs >= WARMUP_MS
        val threshold = THROW_LEVEL * strictness
        if (!pushArmed && rawPush < REARM_LEVEL) pushArmed = true

        if (pushArmed &&
            warm &&
            lastBurst &&
            nowMs - lastThrowMs > THROW_COOLDOWN_MS &&
            smoothedPush >= threshold
        ) {
            pushArmed = false
            lastThrowMs = nowMs
            throwFlashUntilMs = nowMs + THROW_FLASH_MS
            event = GestureEvent.THROW
        }

        // ---- Lateral browse: low threshold, any pose -----------------------
        if (event == GestureEvent.NONE &&
            smoothedPush < SWIPE_SUPPRESS_PUSH &&
            nowMs - lastSwipeMs > SWIPE_COOLDOWN_MS
        ) {
            val swipe = detectSwipe(nowMs)
            if (swipe != GestureEvent.NONE) {
                lastSwipeMs = nowMs
                event = swipe
            }
        }

        state = when {
            nowMs < throwFlashUntilMs -> GestureState.THROW_VECTOR
            smoothedClosure > GRAB_LEVEL -> GestureState.GRAB_FIST
            else -> GestureState.IDLE
        }

        return Reading(
            state = state,
            event = event,
            handVisible = true,
            fistStrength = smoothedClosure.coerceIn(0f, 1f),
            openStrength = (1f - smoothedClosure).coerceIn(0f, 1f),
            thrustStrength = smoothedPush.coerceIn(0f, 1f),
            palmX = palmX,
            palmY = palmY,
            handSize = palmSize
        )
    }

    private fun onHandMissing(nowMs: Long): Reading {
        missingFrames++
        history.clear()
        smoothedPush = 0f
        smoothedClosure = 0f
        closed = false
        pushArmed = true

        if (missingFrames >= MISSING_FRAMES_RESET) state = GestureState.IDLE

        return Reading(
            state = if (nowMs < throwFlashUntilMs) GestureState.THROW_VECTOR else state,
            event = GestureEvent.NONE,
            handVisible = false,
            fistStrength = 0f,
            openStrength = 0f,
            thrustStrength = 0f,
            palmX = palmX,
            palmY = palmY,
            handSize = palmSize
        )
    }

    // ------------------------------------------------------------------ math

    private fun palmCentre(landmarks: List<NormalizedLandmark>): FloatArray {
        var x = 0f
        var y = 0f
        for (index in PALM_INDICES) {
            x += landmarks[index].x()
            y += landmarks[index].y()
        }
        val count = PALM_INDICES.size.toFloat()
        return floatArrayOf(x / count, y / count)
    }

    /**
     * Average finger curl, 0 = fully extended, 1 = fully closed.
     * A finger is curled when its tip is closer to the palm centre than its PIP
     * joint; the ratio is normalised so hand size and distance do not matter.
     */
    private fun closure(landmarks: List<NormalizedLandmark>, palm: FloatArray): Float {
        var sum = 0f
        var weight = 0f
        for (i in FINGERS.indices) {
            val finger = FINGERS[i]
            val tipDistance = distance2D(landmarks[finger.tip], palm)
            val pipDistance = distance2D(landmarks[finger.pip], palm)
            if (pipDistance < MIN_HAND_SIZE) continue
            val ratio = (tipDistance - pipDistance) / pipDistance
            sum += clamp(-ratio / CURL_SCALE) * finger.weight
            weight += finger.weight
        }
        return if (weight <= 0f) 0f else sum / weight
    }

    private fun pushSample(timeMs: Long, palm: FloatArray, size: Float, depth: Float) {
        history.addLast(Sample(timeMs, palm[0], palm[1], size, depth))
        while (history.isNotEmpty() && timeMs - history.first().timeMs > HISTORY_MS) {
            history.removeFirst()
        }
        while (history.size > HISTORY_CAPACITY) history.removeFirst()
    }

    /**
     * Forward confidence in 0..1.
     *
     * Fusion shape: the strongest "intentional" cue (apparent growth or device
     * acceleration) carries most of the weight, with palm speed and depth as
     * support. Uniform weights made a natural push fall just under threshold,
     * which is why this is deliberately top-heavy.
     *
     * `lastBurst` requires the motion to be *fast* (speed or acceleration) so a
     * slow lean toward the phone does not fire a transfer.
     */
    private fun pushConfidence(nowMs: Long, forwardAccel: Float): Float {
        lastBurst = false
        if (history.size < 3) return 0f

        var peakGrowth = 0f
        var peakDepth = 0f
        var peakSpeed = 0f

        var previous = history.first()
        for (i in 1 until history.size) {
            val current = history.elementAt(i)
            val dt = (current.timeMs - previous.timeMs) / 1000f
            if (dt < MIN_DT_SECONDS) {
                previous = current
                continue
            }
            val baseSize = max(previous.size, MIN_HAND_SIZE)
            val growth = (current.size - previous.size) / baseSize / dt
            val depthRate = (previous.depth - current.depth) / dt
            val speed = hypot(current.x - previous.x, current.y - previous.y) / dt

            if (growth > peakGrowth) peakGrowth = growth
            if (depthRate > peakDepth) peakDepth = depthRate
            if (speed > peakSpeed) peakSpeed = speed
            previous = current
        }

        val windowSeconds = (nowMs - history.first().timeMs) / 1000f
        val windowFactor = clamp(windowSeconds / MIN_WINDOW_SECONDS)

        val cGrowth = clamp(peakGrowth / GROWTH_THRESHOLD)
        val cDepth = clamp(peakDepth / DEPTH_THRESHOLD)
        val cSpeed = clamp(peakSpeed / SPEED_THRESHOLD)
        val cAccel = clamp(forwardAccel / ACCEL_THRESHOLD)

        val primary = max(cGrowth, cAccel)
        val support = (cSpeed + cDepth) * 0.5f
        val fusion = 0.65f * primary + 0.35f * support

        lastBurst = cSpeed > BURST_SPEED_LEVEL || cAccel > BURST_ACCEL_LEVEL

        return (fusion * windowFactor).coerceIn(0f, 1f)
    }

    /** Small lateral movement of the palm cycles through the shelf. */
    private fun detectSwipe(nowMs: Long): GestureEvent {
        if (history.size < 3) return GestureEvent.NONE
        val newest = history.last()
        val oldest = history.first()
        val dtSeconds = (newest.timeMs - oldest.timeMs) / 1000f
        if (dtSeconds < MIN_SWIPE_SECONDS) return GestureEvent.NONE

        val dx = newest.x - oldest.x
        val dy = newest.y - oldest.y
        if (abs(dx) < SWIPE_DISTANCE) return GestureEvent.NONE
        if (abs(dx) < abs(dy) * SWIPE_ASPECT) return GestureEvent.NONE
        if (abs(dx) / dtSeconds < SWIPE_MIN_SPEED) return GestureEvent.NONE

        return if (dx > 0f) GestureEvent.SWIPE_NEXT else GestureEvent.SWIPE_PREV
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        hypot(a.x() - b.x(), a.y() - b.y())

    private fun distance2D(landmark: NormalizedLandmark, point: FloatArray): Float =
        hypot(landmark.x() - point[0], landmark.y() - point[1])

    private fun clamp(value: Float, min: Float = 0f, max: Float = 1f): Float =
        if (value < min) min else if (value > max) max else value

    private class Finger(val pip: Int, val tip: Int, val weight: Float)

    companion object {
        const val LANDMARK_COUNT = 21
        const val DEFAULT_SENSITIVITY = 0.62f

        private const val WRIST = 0
        private const val MIDDLE_MCP = 9
        private val PALM_INDICES = intArrayOf(0, 5, 9, 13, 17)

        private val FINGERS = arrayOf(
            Finger(pip = 6, tip = 8, weight = 1.2f),   // index
            Finger(pip = 10, tip = 12, weight = 1.2f), // middle
            Finger(pip = 14, tip = 16, weight = 1.0f), // ring
            Finger(pip = 18, tip = 20, weight = 0.8f)  // pinky
        )

        private const val MIN_HAND_SIZE = 0.02f
        private const val CURL_SCALE = 0.26f

        private const val CLOSURE_SMOOTHING = 0.5f
        private const val PUSH_SMOOTHING = 0.45f

        private const val GRAB_LEVEL = 0.55f
        private const val RELEASE_LEVEL = 0.35f

        private const val HISTORY_MS = 420L
        private const val HISTORY_CAPACITY = 60
        private const val MIN_DT_SECONDS = 0.012f
        private const val MIN_WINDOW_SECONDS = 0.09f
        private const val MIN_SWIPE_SECONDS = 0.12f

        // Peak-rate thresholds for the four fusion cues.
        private const val GROWTH_THRESHOLD = 0.55f
        private const val DEPTH_THRESHOLD = 1.60f
        private const val SPEED_THRESHOLD = 1.80f
        private const val ACCEL_THRESHOLD = 2.20f

        private const val BURST_SPEED_LEVEL = 0.20f
        private const val BURST_ACCEL_LEVEL = 0.25f
        private const val WARMUP_MS = 450L

        private const val THROW_LEVEL = 0.45f
        private const val REARM_LEVEL = 0.20f
        private const val THROW_COOLDOWN_MS = 800L
        private const val THROW_FLASH_MS = 700L

        // Deliberately sensitive: browsing should feel effortless.
        private const val SWIPE_DISTANCE = 0.10f
        private const val SWIPE_ASPECT = 1.15f
        private const val SWIPE_MIN_SPEED = 0.30f
        private const val SWIPE_SUPPRESS_PUSH = 0.35f
        private const val SWIPE_COOLDOWN_MS = 550L

        private const val MISSING_FRAMES_RESET = 8
    }
}
