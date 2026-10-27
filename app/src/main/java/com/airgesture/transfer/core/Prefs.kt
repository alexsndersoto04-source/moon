package com.airgesture.transfer.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted, intentionally tiny preferences. The app is zero-configuration for
 * the user: these knobs only remember how the last session felt so the tuning
 * survives process death.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Haptic confirmation on fist / throw. */
    var hapticsEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(value) = sp.edit { putBoolean(KEY_HAPTICS, value) }

    /** 0f = calm (harder to trigger), 1f = eager. Clamped on read. */
    var sensitivity: Float
        get() = sp.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0f, 1f)
        set(value) = sp.edit { putFloat(KEY_SENSITIVITY, value.coerceIn(0f, 1f)) }

    /** Subtle hand-skeleton overlay on top of the preview. */
    var skeletonOverlay: Boolean
        get() = sp.getBoolean(KEY_SKELETON, true)
        set(value) = sp.edit { putBoolean(KEY_SKELETON, value) }

    companion object {
        private const val FILE = "airgesture_prefs"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_SKELETON = "skeleton"
        const val DEFAULT_SENSITIVITY = 0.55f
    }
}
