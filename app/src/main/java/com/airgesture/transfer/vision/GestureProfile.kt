package com.airgesture.transfer.vision

/**
 * Thresholds measured on the actual user during calibration, instead of the
 * guesses that made the gesture feel arbitrary.
 *
 * Every value is a *peak rate* observed in normal use. The interpreter fires
 * when the live signal reaches [triggerRatio] of that peak, so the same gesture
 * that the user performed while calibrating is the one that triggers later.
 */
data class GestureProfile(
    val growth: Float = DEFAULT_GROWTH,
    val depth: Float = DEFAULT_DEPTH,
    val speed: Float = DEFAULT_SPEED,
    val accel: Float = DEFAULT_ACCEL,
    /** Normalised palm size at the user's comfortable distance. 0 = unknown. */
    val handSize: Float = 0f,
    val calibrated: Boolean = false
) {

    fun withSensitivity(sensitivity: Float): GestureProfile {
        // 0 = strict (needs 115% of the calibrated peak), 1 = eager (55%).
        val factor = 1.15f - 0.6f * sensitivity.coerceIn(0f, 1f)
        return copy(
            growth = (growth * factor).coerceAtLeast(MIN_GROWTH),
            depth = (depth * factor).coerceAtLeast(MIN_DEPTH),
            speed = (speed * factor).coerceAtLeast(MIN_SPEED),
            accel = (accel * factor).coerceAtLeast(MIN_ACCEL)
        )
    }

    companion object {
        const val TRIGGER_RATIO = 0.55f

        // Uncalibrated defaults: conservative but reachable.
        const val DEFAULT_GROWTH = 0.45f
        const val DEFAULT_DEPTH = 1.30f
        const val DEFAULT_SPEED = 1.50f
        const val DEFAULT_ACCEL = 1.80f

        private const val MIN_GROWTH = 0.12f
        private const val MIN_DEPTH = 0.35f
        private const val MIN_SPEED = 0.40f
        private const val MIN_ACCEL = 0.50f
    }
}
