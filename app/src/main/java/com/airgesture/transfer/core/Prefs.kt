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

    /**
     * Send gesture: `true` = fist loads the file and the open hand releases it
     * (default, deliberate), `false` = plain push (accessibility fallback).
     */
    var fistToSend: Boolean
        get() = sp.getBoolean(KEY_FIST_SEND, true)
        set(value) = sp.edit { putBoolean(KEY_FIST_SEND, value) }

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

    /** Thresholds measured by the guided calibration (0 = not calibrated). */
    var profileGrowth: Float
        get() = sp.getFloat(KEY_P_GROWTH, 0f)
        set(value) = sp.edit { putFloat(KEY_P_GROWTH, value) }

    var profileDepth: Float
        get() = sp.getFloat(KEY_P_DEPTH, 0f)
        set(value) = sp.edit { putFloat(KEY_P_DEPTH, value) }

    var profileSpeed: Float
        get() = sp.getFloat(KEY_P_SPEED, 0f)
        set(value) = sp.edit { putFloat(KEY_P_SPEED, value) }

    var profileAccel: Float
        get() = sp.getFloat(KEY_P_ACCEL, 0f)
        set(value) = sp.edit { putFloat(KEY_P_ACCEL, value) }

    var profileHandSize: Float
        get() = sp.getFloat(KEY_P_SIZE, 0f)
        set(value) = sp.edit { putFloat(KEY_P_SIZE, value) }

    /** True once the first-run calibration sheet has been offered. */
    var calibrationAsked: Boolean
        get() = sp.getBoolean(KEY_CAL_ASKED, false)
        set(value) = sp.edit { putBoolean(KEY_CAL_ASKED, value) }

    var calibrated: Boolean
        get() = sp.getBoolean(KEY_CALIBRATED, false)
        set(value) = sp.edit { putBoolean(KEY_CALIBRATED, value) }

    fun saveProfile(profile: com.airgesture.transfer.vision.GestureProfile) {
        sp.edit {
            putFloat(KEY_P_GROWTH, profile.growth)
            putFloat(KEY_P_DEPTH, profile.depth)
            putFloat(KEY_P_SPEED, profile.speed)
            putFloat(KEY_P_ACCEL, profile.accel)
            putFloat(KEY_P_SIZE, profile.handSize)
            putBoolean(KEY_CALIBRATED, profile.calibrated)
        }
    }

    fun loadProfile(): com.airgesture.transfer.vision.GestureProfile {
        return if (calibrated) {
            com.airgesture.transfer.vision.GestureProfile(
                growth = profileGrowth.coerceAtLeast(0.1f),
                depth = profileDepth.coerceAtLeast(0.3f),
                speed = profileSpeed.coerceAtLeast(0.4f),
                accel = profileAccel.coerceAtLeast(0.5f),
                handSize = profileHandSize,
                calibrated = true
            )
        } else {
            com.airgesture.transfer.vision.GestureProfile()
        }
    }

    fun resetToDefaults() {
        sp.edit {
            putFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
            putBoolean(KEY_FRONT_CAMERA, true)
            putBoolean(KEY_HAPTICS, true)
            putBoolean(KEY_SKELETON, true)
            putBoolean(KEY_DIAGNOSTICS, false)
            putBoolean(KEY_KEEP_AWAKE, true)
            putBoolean(KEY_CALIBRATED, false)
            putBoolean(KEY_CAL_ASKED, false)
            putBoolean(KEY_FIST_SEND, true)
        }
    }

    companion object {
        private const val FILE = "airgesture_prefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_FRONT_CAMERA = "front_camera"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SKELETON = "skeleton"
        private const val KEY_FIST_SEND = "fist_send"
        private const val KEY_DIAGNOSTICS = "diagnostics"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_P_GROWTH = "p_growth"
        private const val KEY_P_DEPTH = "p_depth"
        private const val KEY_P_SPEED = "p_speed"
        private const val KEY_P_ACCEL = "p_accel"
        private const val KEY_P_SIZE = "p_size"
        private const val KEY_CALIBRATED = "calibrated"
        private const val KEY_CAL_ASKED = "cal_asked"
        const val DEFAULT_SENSITIVITY = 0.62f
    }
}
