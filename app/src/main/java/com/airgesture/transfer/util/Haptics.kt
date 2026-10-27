package com.airgesture.transfer.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.airgesture.transfer.core.AirGestureApp

/**
 * Two micro-patterns only: a soft tick for selection and a firmer double tick
 * for the throw. Respects the user preference stored in [com.airgesture.transfer.core.Prefs].
 */
class Haptics(context: Context) {

    private val app = context.applicationContext as? AirGestureApp

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val supported: Boolean = vibrator?.hasVibrator() == true

    private val enabled: Boolean
        get() = app?.prefs?.hapticsEnabled ?: true

    fun tick() = vibrate(VibrationEffect.createOneShot(12L, 55))

    fun confirm() = vibrate(
        VibrationEffect.createWaveform(longArrayOf(0L, 18L, 42L, 26L), intArrayOf(0, 90, 0, 120), -1)
    )

    private fun vibrate(effect: VibrationEffect) {
        if (!supported || !enabled) return
        runCatching { vibrator?.vibrate(effect) }
    }
}
