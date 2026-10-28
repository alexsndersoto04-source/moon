package com.airgesture.transfer.vision

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
        val livePush: Float = 0f,
        val profile: GestureProfile? = null
    )

    var current: State = State()
        private set
    private val growths = ArrayList<Float>(ATTEMPTS)
    private val speeds = ArrayList<Float>(ATTEMPTS)
    private val depths = ArrayList<Float>(ATTEMPTS)
    private val accels = ArrayList<Float>(ATTEMPTS)
    private val sizes = ArrayList<Float>(ATTEMPTS)

    private var attemptGrowth = 0f
    private var attemptSpeed = 0f
    private var attemptDepth = 0f
    private var attemptAccel = 0f
    private var attemptSize = 0f
    private var aboveSinceMs = 0L
    private var lastAttemptMs = 0L

    val isActive: Boolean
        get() = current.phase != Phase.IDLE && current.phase != Phase.DONE

    fun start(): State {
        growths.clear(); speeds.clear(); depths.clear(); accels.clear(); sizes.clear()
        attemptGrowth = 0f; attemptSpeed = 0f; attemptDepth = 0f; attemptAccel = 0f
        attemptSize = 0f; aboveSinceMs = 0L; lastAttemptMs = 0L
        current = State(phase = Phase.WAITING, attempt = 0, total = ATTEMPTS)
        return current
    }

    fun cancel() {
        current = State()
    }

    /**
     * @param motion current frame metrics, already filtered.
     */
    fun onFrame(motion: Motion, nowMs: Long): State {
        if (current.phase == Phase.IDLE || current.phase == Phase.DONE) return current
        if (!motion.handVisible) {
            current = current.copy(phase = Phase.WAITING, livePush = 0f)
            return current
        }

        val push = motion.push
        val above = push >= CAPTURE_LEVEL
        if (above && aboveSinceMs == 0L) aboveSinceMs = nowMs
        if (!above) aboveSinceMs = 0L

        attemptGrowth = max(attemptGrowth, motion.peakGrowth)
        attemptSpeed = max(attemptSpeed, motion.peakSpeed)
        attemptDepth = max(attemptDepth, motion.peakDepth)
        attemptAccel = max(attemptAccel, motion.accel)
        attemptSize = max(attemptSize, motion.size)

        current = when (current.phase) {
            Phase.WAITING, Phase.SETTLING -> {
                val next = current.copy(
                    phase = if (current.phase == Phase.SETTLING) Phase.SETTLING else Phase.WAITING,
                    livePush = push
                )
                if (above && nowMs - lastAttemptMs > ATTEMPT_COOLDOWN_MS) {
                    next.copy(phase = Phase.PUSHING)
                } else {
                    next
                }
            }

            Phase.PUSHING -> {
                val enough = nowMs - aboveSinceMs >= MIN_PUSH_MS || attemptGrowth > 0f
                val stillRising = motion.push > CAPTURE_LEVEL * 0.6f
                if (enough && !stillRising) {
                    recordAttempt()
                    lastAttemptMs = nowMs
                    attemptGrowth = 0f; attemptSpeed = 0f; attemptDepth = 0f
                    attemptAccel = 0f; attemptSize = 0f; aboveSinceMs = 0L
                    val done = growths.size >= ATTEMPTS
                    if (done) {
                        State(phase = Phase.DONE, attempt = ATTEMPTS, total = ATTEMPTS, progress = 1f, profile = buildProfile())
                    } else {
                        State(
                            phase = Phase.SETTLING,
                            attempt = growths.size,
                            total = ATTEMPTS,
                            progress = growths.size / ATTEMPTS.toFloat(),
                            livePush = push
                        )
                    }
                } else {
                    current.copy(
                        phase = Phase.PUSHING,
                        livePush = push,
                        progress = (growths.size + 0.5f) / ATTEMPTS.toFloat()
                    )
                }
            }

            else -> current.copy(livePush = push)
        }
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
        private const val CAPTURE_LEVEL = 0.22f
        private const val MIN_PUSH_MS = 60L
        private const val ATTEMPT_COOLDOWN_MS = 700L
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
    val lateralSpeed: Float
)
