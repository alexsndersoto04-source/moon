package com.airgesture.transfer.vision

import com.airgesture.transfer.core.GestureEvent
import com.airgesture.transfer.core.GestureState
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pure-Kotlin gesture state machine. No Android imports, no allocations per
 * frame beyond one small [Reading] and one history sample, so it is safe to run
 * inside the camera analyzer thread.
 *
 * Detection strategy (scale invariant, no calibration):
 *  - A finger is *extended* when its tip is farther from the palm centre than
 *    its PIP joint, and *curled* when it is closer. The normalised delta is
 *    averaged over four fingers with weights that trust the noisier pinky less.
 *  - Fist / open decisions are debounced over consecutive frames (hysteresis)
 *    so a single noisy frame can never select a file.
 *  - The throw combines three independent cues: apparent hand growth
 *    (Z translation shows up as scale), palm depth (MediaPipe z) and the
 *    accelerometer's forward axis. Two cues must agree, which is why the
 *    gesture does not fire while the phone is simply tilted.
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

    private var fistFrames = 0
    private var openFrames = 0
    private var missingFrames = 0

    private var lastFistEmitMs = 0L
    private var lastThrowMs = 0L
    private var lastSwipeMs = 0L
    private var throwFlashUntilMs = 0L

    private var smoothedThrust = 0f
    private var smoothedOpen = 0f
    private var smoothedFist = 0f

    private var sx = 0f
    private var sy = 0f
    private var sSize = 0f

    var sensitivity: Float = 0.55f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    fun reset() {
        history.clear()
        state = GestureState.IDLE
        fistFrames = 0
        openFrames = 0
        missingFrames = 0
        smoothedThrust = 0f
        smoothedFist = 0f
        smoothedOpen = 0f
        throwFlashUntilMs = 0L
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

        val palm = palmCentre(landmarks)
        val handSize = max(distance(landmarks[WRIST], landmarks[MIDDLE_MCP]), MIN_HAND_SIZE)

        val fist = fistStrength(landmarks, palm)
        val open = openStrength(landmarks, palm)

        // Exponential smoothing keeps the overlay calm without adding latency
        // that would be perceptible for the state transitions.
        smoothedFist = smoothedFist + ALPHA * (fist - smoothedFist)
        smoothedOpen = smoothedOpen + ALPHA * (open - smoothedOpen)

        pushSample(nowMs, palm, handSize, landmarks[MIDDLE_MCP].z())

        val thrust = if (state == GestureState.GRAB_FIST) {
            thrustScore(nowMs, forwardAccel)
        } else {
            0f
        }
        smoothedThrust = smoothedThrust + ALPHA * (thrust - smoothedThrust)

        var event = GestureEvent.NONE

        // ---- Fist -> select ------------------------------------------------
        if (smoothedFist > FIST_ENTER_THRESHOLD) fistFrames++ else fistFrames = max(fistFrames - 1, 0)
        if (smoothedOpen > OPEN_ENTER_THRESHOLD) openFrames++ else openFrames = max(openFrames - 1, 0)

        if (state != GestureState.GRAB_FIST && fistFrames >= FIST_ENTER_FRAMES) {
            state = GestureState.GRAB_FIST
            lastFistEmitMs = nowMs
            event = GestureEvent.FIST_CLOSED
            openFrames = 0
        }

        // ---- Open palm -> release -----------------------------------------
        if (state == GestureState.GRAB_FIST && openFrames >= OPEN_RELEASE_FRAMES) {
            state = GestureState.IDLE
            event = GestureEvent.HAND_OPENED
            fistFrames = 0
            throwFlashUntilMs = 0L
        }

        // ---- Throw ---------------------------------------------------------
        if (state == GestureState.GRAB_FIST &&
            nowMs - lastThrowMs > THROW_COOLDOWN_MS &&
            smoothedThrust >= THROW_THRESHOLD
        ) {
            lastThrowMs = nowMs
            throwFlashUntilMs = nowMs + THROW_FLASH_MS
            event = GestureEvent.THROW
        }

        // ---- Lateral swipe (browsing) --------------------------------------
        if (state == GestureState.IDLE &&
            event == GestureEvent.NONE &&
            nowMs - lastSwipeMs > SWIPE_COOLDOWN_MS &&
            smoothedOpen > SWIPE_OPEN_THRESHOLD
        ) {
            val swipe = detectSwipe(nowMs)
            if (swipe != GestureEvent.NONE) {
                lastSwipeMs = nowMs
                event = swipe
            }
        }

        val reportedState = when {
            nowMs < throwFlashUntilMs -> GestureState.THROW_VECTOR
            state == GestureState.GRAB_FIST -> GestureState.GRAB_FIST
            else -> GestureState.IDLE
        }

        sx = palm[0]
        sy = palm[1]
        sSize = handSize

        return Reading(
            state = reportedState,
            event = event,
            handVisible = true,
            fistStrength = smoothedFist.coerceIn(0f, 1f),
            openStrength = smoothedOpen.coerceIn(0f, 1f),
            thrustStrength = smoothedThrust.coerceIn(0f, 1f),
            palmX = sx,
            palmY = sy,
            handSize = sSize
        )
    }

    private fun onHandMissing(nowMs: Long): Reading {
        missingFrames++
        history.clear()
        smoothedThrust = 0f
        smoothedFist = 0f
        smoothedOpen = 0f
        fistFrames = 0
        openFrames = 0

        var event = GestureEvent.NONE
        if (missingFrames >= MISSING_FRAMES_RELEASE && state == GestureState.GRAB_FIST) {
            state = GestureState.IDLE
            event = GestureEvent.HAND_OPENED
        }
        return Reading(
            state = if (nowMs < throwFlashUntilMs) GestureState.THROW_VECTOR else state,
            event = event,
            handVisible = false,
            fistStrength = 0f,
            openStrength = 0f,
            thrustStrength = 0f,
            palmX = sx,
            palmY = sy,
            handSize = sSize
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

    private fun fistStrength(landmarks: List<NormalizedLandmark>, palm: FloatArray): Float {
        var sum = 0f
        var weight = 0f
        for (i in FINGERS.indices) {
            val finger = FINGERS[i]
            val tipDistance = distance2D(landmarks[finger.tip], palm)
            val pipDistance = distance2D(landmarks[finger.pip], palm)
            if (pipDistance < MIN_HAND_SIZE) continue
            val ratio = (tipDistance - pipDistance) / pipDistance
            val curl = clamp(-ratio / FIST_RATIO_SCALE)
            sum += curl * finger.weight
            weight += finger.weight
        }
        return if (weight <= 0f) 0f else sum / weight
    }

    private fun openStrength(landmarks: List<NormalizedLandmark>, palm: FloatArray): Float {
        var sum = 0f
        var weight = 0f
        for (i in FINGERS.indices) {
            val finger = FINGERS[i]
            val tipDistance = distance2D(landmarks[finger.tip], palm)
            val pipDistance = distance2D(landmarks[finger.pip], palm)
            if (pipDistance < MIN_HAND_SIZE) continue
            val ratio = (tipDistance - pipDistance) / pipDistance
            val extension = clamp(ratio / OPEN_RATIO_SCALE)
            sum += extension * finger.weight
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
     * 0..1 forward-thrust confidence. Requires the fist to *grow* (moving toward
     * the lens) or the palm depth to shrink, and rewards accelerometer agreement.
     */
    private fun thrustScore(nowMs: Long, forwardAccel: Float): Float {
        if (history.size < 4) return 0f
        val newest = history.last()
        val oldest = history.first()
        val dtSeconds = (newest.timeMs - oldest.timeMs) / 1000f
        if (dtSeconds < MIN_WINDOW_SECONDS) return 0f

        val speed = hypot(newest.x - oldest.x, newest.y - oldest.y) / dtSeconds
        val growth = (newest.size - oldest.size) / max(oldest.size, MIN_HAND_SIZE) / dtSeconds
        val depthRate = (oldest.depth - newest.depth) / dtSeconds // toward camera = positive

        // Sensitivity 0 -> stricter, 1 -> eager.
        val strictness = 1.15f - 0.45f * sensitivity

        val cGrowth = clamp(growth / (GROWTH_THRESHOLD * strictness))
        val cSpeed = clamp(speed / (SPEED_THRESHOLD * strictness))
        val cAccel = clamp(forwardAccel / (ACCEL_THRESHOLD * strictness))
        val cDepth = clamp(depthRate / (DEPTH_THRESHOLD * strictness))

        val score = 0.40f * cGrowth + 0.22f * cSpeed + 0.24f * cAccel + 0.14f * cDepth

        // At least two cues must be meaningful: a phone waving in the air alone
        // (accel only) or a hand approaching alone must not trigger a send.
        val meaningful = (if (cGrowth > 0.35f) 1 else 0) +
            (if (cSpeed > 0.35f) 1 else 0) +
            (if (cAccel > 0.35f) 1 else 0) +
            (if (cDepth > 0.35f) 1 else 0)
        if (meaningful < 2) return score * 0.5f

        return score
    }

    private fun detectSwipe(nowMs: Long): GestureEvent {
        if (history.size < 4) return GestureEvent.NONE
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

        private const val WRIST = 0
        private const val MIDDLE_MCP = 9
        private val PALM_INDICES = intArrayOf(0, 5, 9, 13, 17)

        private val FINGERS = arrayOf(
            Finger(pip = 6, tip = 8, weight = 1.2f),   // index
            Finger(pip = 10, tip = 12, weight = 1.2f), // middle
            Finger(pip = 14, tip = 16, weight = 1.0f), // ring
            Finger(pip = 18, tip = 20, weight = 0.8f)  // pinky
        )

        private const val ALPHA = 0.55f
        private const val MIN_HAND_SIZE = 0.02f

        private const val FIST_RATIO_SCALE = 0.28f
        private const val OPEN_RATIO_SCALE = 0.33f

        private const val FIST_ENTER_THRESHOLD = 0.62f
        private const val OPEN_ENTER_THRESHOLD = 0.58f
        private const val FIST_ENTER_FRAMES = 3
        private const val OPEN_RELEASE_FRAMES = 2
        private const val MISSING_FRAMES_RELEASE = 6

        private const val HISTORY_MS = 380L
        private const val HISTORY_CAPACITY = 48
        private const val MIN_WINDOW_SECONDS = 0.10f
        private const val MIN_SWIPE_SECONDS = 0.14f

        private const val GROWTH_THRESHOLD = 0.55f
        private const val SPEED_THRESHOLD = 1.8f
        private const val ACCEL_THRESHOLD = 2.4f
        private const val DEPTH_THRESHOLD = 1.9f
        private const val THROW_THRESHOLD = 0.62f
        private const val THROW_COOLDOWN_MS = 1400L
        private const val THROW_FLASH_MS = 650L

        private const val SWIPE_DISTANCE = 0.16f
        private const val SWIPE_ASPECT = 1.7f
        private const val SWIPE_MIN_SPEED = 0.45f
        private const val SWIPE_OPEN_THRESHOLD = 0.45f
        private const val SWIPE_COOLDOWN_MS = 800L
    }
}
