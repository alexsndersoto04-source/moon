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
 * Nothing here asks for a specific hand shape. The user moves the hand toward
 * the phone however it feels natural and the engine reads the *motion*.
 *
 * Pipeline per frame:
 *  1. Palm centre, palm size and depth are filtered with [OneEuroFilter]s, one
 *     per channel: steady when the hand is still, lag-free when it moves fast.
 *  2. Peaks of four independent cues are tracked over a short window
 *     (apparent growth, depth rate, palm speed, device forward acceleration).
 *  3. The cues are fused into a single `push` score against the **user's own
 *     calibrated thresholds** ([GestureProfile]); with no calibration it falls
 *     back to conservative defaults.
 *  4. [arbitrate] decides whether the motion is a *forward push* or a *lateral
 *     swipe* before any event is emitted, so the two can never fire at once.
 *  5. Temporal hysteresis (must stay above the threshold for a few dozen ms)
 *     removes single-frame noise without adding a pose requirement.
 *
 * Pure Kotlin, no Android imports, no per-frame allocations beyond one [Motion]
 * and one [Reading]: safe to run on the camera thread.
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
        val handSize: Float,
        /** 0..3 band of the push meter, used for progressive haptics. */
        val pushZone: Int = 0
    )

    private data class Sample(
        val timeMs: Long,
        val x: Float,
        val y: Float,
        val size: Float,
        val depth: Float
    )

    // One Euro per channel: position needs more smoothing than size/depth.
    private val filterX = OneEuroFilter(minCutoff = 1.6f, beta = 0.010f)
    private val filterY = OneEuroFilter(minCutoff = 1.6f, beta = 0.010f)
    private val filterSize = OneEuroFilter(minCutoff = 1.0f, beta = 0.020f)
    private val filterDepth = OneEuroFilter(minCutoff = 1.0f, beta = 0.020f)

    private val history = ArrayDeque<Sample>(HISTORY_CAPACITY)

    private var state: GestureState = GestureState.IDLE
    private var throwFlashUntilMs = 0L

    private var lastThrowMs = 0L
    private var lastSwipeMs = 0L
    private var lastFistEventMs = 0L
    private var aboveSinceMs = 0L

    private var pushArmed = true
    private var missingFrames = 0
    private var closed = false
    private var firstSeenMs = 0L

    private var smoothedClosure = 0f
    private var smoothedPush = 0f
    private var lastZone = 0

    private var palmX = 0f
    private var palmY = 0f
    private var palmSize = 0f

    /** 0 = strict, 1 = eager. Scales the calibrated thresholds. */
    var sensitivity: Float = DEFAULT_SENSITIVITY
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Thresholds measured on this user, or the conservative defaults. */
    var profile: GestureProfile = GestureProfile()

    /** Set while the guided calibration is running; events are suppressed. */
    var calibration: GestureCalibration? = null

    fun reset() {
        history.clear()
        filterX.reset(); filterY.reset(); filterSize.reset(); filterDepth.reset()
        firstSeenMs = 0L
        state = GestureState.IDLE
        throwFlashUntilMs = 0L
        pushArmed = true
        missingFrames = 0
        closed = false
        aboveSinceMs = 0L
        smoothedClosure = 0f
        smoothedPush = 0f
        lastZone = 0
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

        val rawPalm = palmCentre(landmarks)
        val rawSize = max(distance(landmarks[WRIST], landmarks[MIDDLE_MCP]), MIN_HAND_SIZE)
        val rawDepth = landmarks[MIDDLE_MCP].z()

        palmX = filterX.filter(rawPalm[0], nowMs)
        palmY = filterY.filter(rawPalm[1], nowMs)
        palmSize = filterSize.filter(rawSize, nowMs)
        val depth = filterDepth.filter(rawDepth, nowMs)

        val closure = closure(landmarks, rawPalm)
        smoothedClosure += CLOSURE_SMOOTHING * (closure - smoothedClosure)

        pushSample(nowMs, palmX, palmY, palmSize, depth)

        val motion = measure(nowMs, forwardAccel)
        val effective = profile.withSensitivity(sensitivity)
        val push = fuse(motion, effective)

        smoothedPush += PUSH_SMOOTHING * (push - smoothedPush)

        // ---- Guided calibration takes over the engine ----------------------
        calibration?.let { calibration ->
            if (calibration.isActive) {
                calibration.onFrame(motion.copy(push = smoothedPush), nowMs)
            }
            // While the overlay exists nothing else may fire: neither a throw nor
            // a swipe. Events come back as soon as the panel is dismissed.
            return Reading(
                state = GestureState.IDLE,
                event = GestureEvent.NONE,
                handVisible = true,
                fistStrength = smoothedClosure.coerceIn(0f, 1f),
                openStrength = (1f - smoothedClosure).coerceIn(0f, 1f),
                thrustStrength = smoothedPush.coerceIn(0f, 1f),
                palmX = palmX, palmY = palmY, handSize = palmSize,
                pushZone = 0
            )
        }

        var event = GestureEvent.NONE

        // ---- Soft grab: feedback only, never a requirement ------------------
        if (!closed && smoothedClosure > GRAB_LEVEL && nowMs - lastFistEventMs > 400L) {
            closed = true
            lastFistEventMs = nowMs
            event = GestureEvent.FIST_CLOSED
        } else if (closed && smoothedClosure < RELEASE_LEVEL) {
            closed = false
            event = GestureEvent.HAND_OPENED
        }

        // ---- Arbitration: one gesture at a time ----------------------------
        val verdict = arbitrate(motion, smoothedPush)

        if (verdict == Verdict.FORWARD) {
            if (aboveSinceMs == 0L) aboveSinceMs = nowMs
            val heldLongEnough = nowMs - aboveSinceMs >= HOLD_MS
            val warm = nowMs - firstSeenMs >= WARMUP_MS
            if (!pushArmed && smoothedPush < REARM_LEVEL) pushArmed = true

            if (pushArmed && warm && heldLongEnough &&
                nowMs - lastThrowMs > THROW_COOLDOWN_MS
            ) {
                pushArmed = false
                lastThrowMs = nowMs
                throwFlashUntilMs = nowMs + THROW_FLASH_MS
                aboveSinceMs = 0L
                event = GestureEvent.THROW
            }
        } else {
            aboveSinceMs = 0L
        }

        if (verdict == Verdict.LATERAL &&
            event == GestureEvent.NONE &&
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

        val zone = when {
            smoothedPush >= 0.85f -> 3
            smoothedPush >= 0.60f -> 2
            smoothedPush >= 0.35f -> 1
            else -> 0
        }
        lastZone = zone

        return Reading(
            state = state,
            event = event,
            handVisible = true,
            fistStrength = smoothedClosure.coerceIn(0f, 1f),
            openStrength = (1f - smoothedClosure).coerceIn(0f, 1f),
            thrustStrength = smoothedPush.coerceIn(0f, 1f),
            palmX = palmX,
            palmY = palmY,
            handSize = palmSize,
            pushZone = zone
        )
    }

    private fun onHandMissing(nowMs: Long): Reading {
        missingFrames++
        history.clear()
        filterX.reset(); filterY.reset(); filterSize.reset(); filterDepth.reset()
        smoothedPush = 0f
        smoothedClosure = 0f
        closed = false
        pushArmed = true
        aboveSinceMs = 0L
        lastZone = 0

        if (missingFrames >= MISSING_FRAMES_RESET) {
            state = GestureState.IDLE
            firstSeenMs = 0L
        }

        return Reading(
            state = if (nowMs < throwFlashUntilMs) GestureState.THROW_VECTOR else state,
            event = GestureEvent.NONE,
            handVisible = false,
            fistStrength = 0f,
            openStrength = 0f,
            thrustStrength = 0f,
            palmX = palmX, palmY = palmY, handSize = palmSize,
            pushZone = 0
        )
    }

    // ------------------------------------------------------------------ math

    private enum class Verdict { NONE, FORWARD, LATERAL }

    /**
     * Decides which gesture the motion represents. Forward wins when there is
     * real approaching evidence (growth or depth) *and* the hand is not sliding
     * sideways; a pure horizontal slide is a swipe and damps the push score so
     * browsing can never trigger a transfer.
     */
    private fun arbitrate(motion: Motion, push: Float): Verdict {
        if (motion.lateralSpeed < LATERAL_MIN_SPEED) {
            return if (push >= effectiveLevel() * 0.5f) Verdict.FORWARD else Verdict.NONE
        }
        val approaching = motion.peakGrowth > 0.05f || motion.peakDepth > 0.15f
        val lateralDominant = !approaching || motion.lateralSpeed > motion.peakSpeed * 0.9f
        return when {
            lateralDominant && abs(motion.lateralSpeed) > LATERAL_MIN_SPEED -> Verdict.LATERAL
            push >= effectiveLevel() * 0.5f -> Verdict.FORWARD
            else -> Verdict.NONE
        }
    }

    private fun effectiveLevel(): Float = profile.withSensitivity(sensitivity).speed

    private fun pushSample(timeMs: Long, x: Float, y: Float, size: Float, depth: Float) {
        history.addLast(Sample(timeMs, x, y, size, depth))
        while (history.isNotEmpty() && timeMs - history.first().timeMs > HISTORY_MS) {
            history.removeFirst()
        }
        while (history.size > HISTORY_CAPACITY) history.removeFirst()
    }

    /** Peak rates of every cue inside the current window. */
    private fun measure(nowMs: Long, forwardAccel: Float): Motion {
        if (history.size < 3) {
            return Motion(false, 0f, 0f, 0f, 0f, forwardAccel, palmSize, 0f)
        }

        var peakGrowth = 0f
        var peakDepth = 0f
        var peakSpeed = 0f
        var lateral = 0f

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
            val lateralSpeed = abs(current.x - previous.x) / dt

            if (growth > peakGrowth) peakGrowth = growth
            if (depthRate > peakDepth) peakDepth = depthRate
            if (speed > peakSpeed) peakSpeed = speed
            if (lateralSpeed > lateral) lateral = lateralSpeed
            previous = current
        }

        val windowSeconds = (nowMs - history.first().timeMs) / 1000f
        val windowFactor = clamp(windowSeconds / MIN_WINDOW_SECONDS)

        return Motion(
            handVisible = true,
            push = 0f,
            peakGrowth = peakGrowth * windowFactor,
            peakSpeed = peakSpeed * windowFactor,
            peakDepth = peakDepth * windowFactor,
            accel = forwardAccel,
            size = palmSize,
            lateralSpeed = lateral * windowFactor
        )
    }

    /**
     * Fusion against the user's thresholds: the strongest "intentional" cue
     * (apparent growth or device acceleration) dominates, palm speed and depth
     * support it. A lateral slide damps the result so browsing stays browsing.
     */
    private fun fuse(motion: Motion, thresholds: GestureProfile): Float {
        val cGrowth = clamp(motion.peakGrowth / thresholds.growth)
        val cDepth = clamp(motion.peakDepth / thresholds.depth)
        val cSpeed = clamp(motion.peakSpeed / thresholds.speed)
        val cAccel = clamp(motion.accel / thresholds.accel)

        val primary = max(cGrowth, cAccel)
        val support = (cSpeed + cDepth) * 0.5f
        var fusion = 0.65f * primary + 0.35f * support

        if (motion.lateralSpeed > LATERAL_MIN_SPEED &&
            motion.peakGrowth < thresholds.growth * 0.5f
        ) {
            fusion *= LATERAL_DAMPING
        }

        val movingSideways = motion.lateralSpeed > thresholds.speed * 0.8f
        if (flingOnly(motion) && !movingSideways) fusion *= 0.6f

        return fusion.coerceIn(0f, 1f)
    }

    /** Hand speed alone (no approach, no device thrust) is not a push. */
    private fun flingOnly(motion: Motion): Boolean =
        motion.peakGrowth < 0.04f && motion.peakDepth < 0.10f && abs(motion.accel) < 0.35f

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
            Finger(pip = 6, tip = 8, weight = 1.2f),
            Finger(pip = 10, tip = 12, weight = 1.2f),
            Finger(pip = 14, tip = 16, weight = 1.0f),
            Finger(pip = 18, tip = 20, weight = 0.8f)
        )

        private const val MIN_HAND_SIZE = 0.02f
        private const val CURL_SCALE = 0.26f
        private const val CLOSURE_SMOOTHING = 0.5f
        private const val PUSH_SMOOTHING = 0.55f

        private const val GRAB_LEVEL = 0.55f
        private const val RELEASE_LEVEL = 0.35f

        private const val HISTORY_MS = 320L
        private const val HISTORY_CAPACITY = 48
        private const val MIN_DT_SECONDS = 0.012f
        private const val MIN_WINDOW_SECONDS = 0.09f
        private const val MIN_SWIPE_SECONDS = 0.12f

        private const val HOLD_MS = 70L
        private const val REARM_LEVEL = 0.20f
        private const val WARMUP_MS = 300L
        private const val THROW_COOLDOWN_MS = 700L
        private const val THROW_FLASH_MS = 450L

        private const val LATERAL_MIN_SPEED = 0.55f
        private const val LATERAL_DAMPING = 0.45f

        private const val SWIPE_DISTANCE = 0.10f
        private const val SWIPE_ASPECT = 1.15f
        private const val SWIPE_MIN_SPEED = 0.30f
        private const val SWIPE_COOLDOWN_MS = 550L

        private const val MISSING_FRAMES_RESET = 8
    }
}
