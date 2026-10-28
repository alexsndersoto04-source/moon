package com.airgesture.transfer.vision

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter for a single scalar channel.
 *
 * It is the standard answer to the trade-off a fixed exponential average cannot
 * solve: at low speed you want heavy smoothing (no jitter), at high speed you
 * want almost none (no lag). The cutoff frequency adapts to the signal's own
 * derivative, which is exactly the "glued to my hand" feel.
 *
 * @param minCutoff baseline smoothing. Lower = steadier, laggier.
 * @param beta speed reactivity. Higher = less lag on fast movement.
 * @param dCutoff cutoff applied to the derivative estimate.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.4f,
    private val beta: Float = 0.02f,
    private val dCutoff: Float = 1.0f
) {

    private var value = Float.NaN
    private var derivative = 0f
    private var lastTimeMs = 0L

    val isInitialised: Boolean get() = !value.isNaN()

    fun reset() {
        value = Float.NaN
        derivative = 0f
        lastTimeMs = 0L
    }

    fun filter(input: Float, nowMs: Long): Float {
        if (value.isNaN()) {
            value = input
            lastTimeMs = nowMs
            return input
        }
        val dt = ((nowMs - lastTimeMs).coerceAtLeast(1L)) / 1000f
        lastTimeMs = nowMs

        val rawDerivative = (input - value) / dt
        val smoothedDerivative = lowPass(rawDerivative, derivative, alpha(dCutoff, dt))
        derivative = smoothedDerivative

        val cutoff = minCutoff + beta * abs(smoothedDerivative) * 1000f
        val filtered = lowPass(input, value, alpha(cutoff, dt))
        value = filtered
        return filtered
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lowPass(input: Float, previous: Float, alpha: Float): Float =
        alpha * input + (1f - alpha) * previous
}
