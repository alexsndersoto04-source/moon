package com.airgesture.transfer.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Real, persisted settings. Every value here changes runtime behaviour
 * immediately: the camera source, the gesture engine thresholds, the haptics
 * and the overlays all read from this object.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 0f = strict (harder to trigger), 1f = eager. Clamped on read. */
    var sensitivity: Float
        get() = sp.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0f, 1f)
        set(value) = sp.edit { putFloat(KEY_SENSITIVITY, value.coerceIn(0f, 1f)) }

    /** Front camera by default: the user looks at the screen while gesturing. */
    var useFrontCamera: Boolean
        get() = sp.getBoolean(KEY_FRONT_CAMERA, true)
        set(value) = sp.edit { putBoolean(KEY_FRONT_CAMERA, value) }

    var hapticsEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(value) = sp.edit { putBoolean(KEY_HAPTICS, value) }

    /** Draw the 21-point hand skeleton over the preview. */
    var skeletonVisible: Boolean
        get() = sp.getBoolean(KEY_SKELETON, true)
        set(value) = sp.edit { putBoolean(KEY_SKELETON, value) }

    /** Show the numeric HUD (fps, inference time, push value). */
    var diagnosticsVisible: Boolean
        get() = sp.getBoolean(KEY_DIAGNOSTICS, false)
        set(value) = sp.edit { putBoolean(KEY_DIAGNOSTICS, value) }

    /**
     * Keep the screen awake while the activity is open. Transfers are much more
     * reliable when the display does not sleep mid-send.
     */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = sp.edit { putBoolean(KEY_KEEP_AWAKE, value) }

    fun resetToDefaults() {
        sp.edit {
            putFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
            putBoolean(KEY_FRONT_CAMERA, true)
            putBoolean(KEY_HAPTICS, true)
            putBoolean(KEY_SKELETON, true)
            putBoolean(KEY_DIAGNOSTICS, false)
            putBoolean(KEY_KEEP_AWAKE, true)
        }
    }

    companion object {
        private const val FILE = "airgesture_prefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_FRONT_CAMERA = "front_camera"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SKELETON = "skeleton"
        private const val KEY_DIAGNOSTICS = "diagnostics"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        const val DEFAULT_SENSITIVITY = 0.62f
    }
}
