package com.airgesture.transfer.vision

import kotlin.math.abs
import kotlin.math.max

/**
 * Guided calibration: the user pushes their hand toward the phone three times
 * and the engine keeps their real peaks. No magic constants survive afterwards.
 *
 * State machine per attempt:
 *   WAITING   → hand visible, push level low
 *   PUSHING   → the push confidence started rising; peaks are being recorded
 *   SETTLING  → the push fell back; the attempt is stored
 * and after [ATTEMPTS] attempts the median of each cue becomes the profile.
 */
class GestureCalibration {

    enum class Phase { IDLE, WAITING, PUSHING, SETTLING, DONE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val attempt: Int = 0,
        val total: Int = ATTEMPTS,
        val progress: Float = 0f,
        /** Live push level of the current frame: the bar the user watches. */
        val livePush: Float = 0f,
        val handVisible: Boolean = false,
        val profile: GestureProfile? = null
    )

    /** Written on the camera thread, read on the main thread once per tick. */
    @Volatile
    var current: State = State()
        private set

    private val growths = ArrayList<Float>(ATTEMPTS)
    private val speeds = ArrayList<Float>(ATTEMPTS)
    private val depths = ArrayList<Float>(ATTEMPTS)
    private val accels = ArrayList<Float>(ATTEMPTS)
    private val sizes = ArrayList<Float>(ATTEMPTS)

    private var attemptPeak = 0f
    private var attemptGrowth = 0f
    private var attemptSpeed = 0f
    private var attemptDepth = 0f
    private var attemptAccel = 0f
    private var attemptSize = 0f
    private var startedAtMs = 0L
    private var lastAttemptMs = 0L

    val isActive: Boolean
        get() = current.phase != Phase.IDLE && current.phase != Phase.DONE

    @Synchronized
    fun start(): State {
        clear()
        current = State(phase = Phase.WAITING, attempt = 0, total = ATTEMPTS)
        return current
    }

    @Synchronized
    fun cancel() {
        clear()
        current = State()
    }

    private fun clear() {
        growths.clear(); speeds.clear(); depths.clear(); accels.clear(); sizes.clear()
        attemptPeak = 0f; attemptGrowth = 0f; attemptSpeed = 0f; attemptDepth = 0f
        attemptAccel = 0f; attemptSize = 0f; startedAtMs = 0L; lastAttemptMs = 0L
    }

    private fun resetAttempt() {
        attemptPeak = 0f; attemptGrowth = 0f; attemptSpeed = 0f; attemptDepth = 0f
        attemptAccel = 0f; attemptSize = 0f; startedAtMs = 0L
    }

    /**
     * Deliberately permissive: an attempt *starts* as soon as there is any real
     * forward motion, and it is *recorded* when the motion fades. Nothing here
     * can leave the panel stuck at "Intento 1" forever, which is exactly what
     * made the gesture feel unresponsive.
     */
    @Synchronized
    fun onFrame(motion: Motion, nowMs: Long): State {
        if (current.phase == Phase.IDLE || current.phase == Phase.DONE) return current

        val push = motion.approach.coerceIn(0f, 1f)

        if (!motion.handVisible) {
            if (current.phase == Phase.PUSHING) resetAttempt()
            current = current.copy(phase = Phase.WAITING, livePush = 0f, handVisible = false)
            return current
        }

        val settling = current.phase == Phase.SETTLING && nowMs - lastAttemptMs < SETTLE_MS
        if (current.phase != Phase.PUSHING && !settling &&
            push >= START_LEVEL && nowMs - lastAttemptMs >= ATTEMPT_COOLDOWN_MS
        ) {
            resetAttempt()
            startedAtMs = nowMs
            current = current.copy(phase = Phase.PUSHING, handVisible = true)
        }

        if (current.phase == Phase.PUSHING) {
            if (push > attemptPeak) attemptPeak = push
            if (motion.peakGrowth > attemptGrowth) attemptGrowth = motion.peakGrowth
            if (motion.peakSpeed > attemptSpeed) attemptSpeed = motion.peakSpeed
            if (motion.peakDepth > attemptDepth) attemptDepth = motion.peakDepth
            if (abs(motion.accel) > attemptAccel) attemptAccel = abs(motion.accel)
            if (motion.size > attemptSize) attemptSize = motion.size

            val elapsed = nowMs - startedAtMs
            val fading = push < attemptPeak * RELEASE_RATIO && elapsed >= MIN_PUSH_MS
            val timedOut = elapsed >= MAX_ATTEMPT_MS

            if ((fading || timedOut) && attemptPeak >= MIN_ATTEMPT_PEAK) {
                recordAttempt()
                lastAttemptMs = nowMs
                current = if (growths.size >= ATTEMPTS) {
                    State(
                        phase = Phase.DONE,
                        attempt = ATTEMPTS,
                        total = ATTEMPTS,
                        progress = 1f,
                        livePush = push,
                        handVisible = true,
                        profile = buildProfile()
                    )
                } else {
                    State(
                        phase = Phase.SETTLING,
                        attempt = growths.size,
                        total = ATTEMPTS,
                        progress = growths.size / ATTEMPTS.toFloat(),
                        livePush = push,
                        handVisible = true
                    )
                }
                resetAttempt()
                return current
            }

            if (fading || timedOut) {
                // Too weak to be a real push: do not count it, just go back.
                resetAttempt()
                current = current.copy(phase = Phase.WAITING, livePush = push, handVisible = true)
                return current
            }

            current = current.copy(
                phase = Phase.PUSHING,
                livePush = push,
                handVisible = true,
                progress = (growths.size + attemptPeak.coerceIn(0f, 1f) * 0.9f) / ATTEMPTS.toFloat()
            )
            return current
        }

        current = current.copy(
            phase = if (settling) Phase.SETTLING else Phase.WAITING,
            livePush = push,
            handVisible = true,
            progress = growths.size / ATTEMPTS.toFloat()
        )
        return current
    }

    private fun recordAttempt() {
        growths += attemptGrowth
        speeds += attemptSpeed
        depths += attemptDepth
        accels += attemptAccel
        if (attemptSize > 0f) sizes += attemptSize
    }

    /** Median peaks, then thresholds at [GestureProfile.TRIGGER_RATIO] of them. */
    private fun buildProfile(): GestureProfile {
        if (growths.isEmpty()) return GestureProfile()
        val ratio = GestureProfile.TRIGGER_RATIO
        return GestureProfile(
            growth = (median(growths) * ratio).coerceAtLeast(0.15f),
            depth = (median(depths) * ratio).coerceAtLeast(0.40f),
            speed = (median(speeds) * ratio).coerceAtLeast(0.45f),
            accel = (median(accels).takeIf { it > 0.05f }?.times(ratio) ?: GestureProfile.DEFAULT_ACCEL)
                .coerceAtLeast(0.55f),
            handSize = if (sizes.isEmpty()) 0f else median(sizes),
            calibrated = true
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2f else sorted[middle]
    }

    companion object {
        const val ATTEMPTS = 3

        /** Any measurable approach starts an attempt. */
        private const val START_LEVEL = 0.08f
        /** Below this peak the attempt is not counted (it was noise). */
        private const val MIN_ATTEMPT_PEAK = 0.12f
        private const val RELEASE_RATIO = 0.55f
        private const val MIN_PUSH_MS = 70L
        /** Hard cap per attempt: the panel can never freeze on "Intento 1". */
        private const val MAX_ATTEMPT_MS = 1300L
        private const val SETTLE_MS = 420L
        private const val ATTEMPT_COOLDOWN_MS = 320L
    }
}

/**
 * One frame of motion evidence, produced by [GestureInterpreter] and consumed by
 * both the state machine and the calibrator.
 */
data class Motion(
    val handVisible: Boolean,
    val push: Float,
    val peakGrowth: Float,
    val peakSpeed: Float,
    val peakDepth: Float,
    val accel: Float,
    val size: Float,
    val lateralSpeed: Float,
    /** 0..1 direct "hand is closer than its resting distance" measurement. */
    val approach: Float
) {
    fun withApproach(value: Float): Motion = copy(approach = value)
}
