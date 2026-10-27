package com.airgesture.transfer.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted, intentionally tiny preferences. The app is zero-configuration for
 * the user: only the gesture sensitivity is remembered, so the tuning survives
 * process death while every other behaviour stays automatic.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 0f = calm (harder to trigger), 1f = eager. Clamped on read. */
    var sensitivity: Float
        get() = sp.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0f, 1f)
        set(value) = sp.edit { putFloat(KEY_SENSITIVITY, value.coerceIn(0f, 1f)) }

    companion object {
        private const val FILE = "airgesture_prefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        const val DEFAULT_SENSITIVITY = 0.62f
    }
}
