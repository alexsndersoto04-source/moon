package com.airgesture.transfer.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Two micro-patterns only: a soft tick for selection and a firmer double tick
 * for the throw. Kept in one place so intensity stays consistent everywhere.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val supported: Boolean = vibrator?.hasVibrator() == true

    fun tick() = vibrate(VibrationEffect.createOneShot(12L, 55))

    fun confirm() = vibrate(
        VibrationEffect.createWaveform(longArrayOf(0L, 18L, 42L, 26L), intArrayOf(0, 90, 0, 120), -1)
    )

    private fun vibrate(effect: VibrationEffect) {
        if (!supported) return
        runCatching { vibrator?.vibrate(effect) }
    }
}
