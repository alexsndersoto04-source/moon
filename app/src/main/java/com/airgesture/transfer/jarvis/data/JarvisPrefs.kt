package com.airgesture.transfer.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Preferencias de JARVIS: identidad, proveedor de IA, modelos y voz. */
class JarvisPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var assistantName: String
        get() = sp.getString(KEY_NAME, "Jarvis").orEmpty().ifBlank { "Jarvis" }
        set(value) = sp.edit { putString(KEY_NAME, value.trim().ifBlank { "Jarvis" }) }

    var userName: String
        get() = sp.getString(KEY_USER, "").orEmpty().trim()
        set(value) = sp.edit { putString(KEY_USER, value.trim()) }

    /** AUTO | OPENROUTER | GROQ */
    var primaryProvider: String
        get() = sp.getString(KEY_PROVIDER, "AUTO").orEmpty()
        set(value) = sp.edit { putString(KEY_PROVIDER, value) }

    var openRouterModel: String
        get() = sp.getString(KEY_OR_MODEL, DEFAULT_OPENROUTER_MODEL).orEmpty()
        set(value) = sp.edit { putString(KEY_OR_MODEL, value.trim()) }

    var groqModel: String
        get() = sp.getString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL).orEmpty()
        set(value) = sp.edit { putString(KEY_GROQ_MODEL, value.trim()) }

    var autoFallback: Boolean
        get() = sp.getBoolean(KEY_FALLBACK, true)
        set(value) = sp.edit { putBoolean(KEY_FALLBACK, value) }

    var voiceEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE, true)
        set(value) = sp.edit { putBoolean(KEY_VOICE, value) }

    var voiceSpeed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f).coerceIn(0.5f, 1.8f)
        set(value) = sp.edit { putFloat(KEY_SPEED, value.coerceIn(0.5f, 1.8f)) }

    var voicePitch: Float
        get() = sp.getFloat(KEY_PITCH, 1.0f).coerceIn(0.6f, 1.5f)
        set(value) = sp.edit { putFloat(KEY_PITCH, value.coerceIn(0.6f, 1.5f)) }

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE, false)
        set(value) = sp.edit { putBoolean(KEY_WAKE, value) }

    var setupDone: Boolean
        get() = sp.getBoolean(KEY_SETUP, false)
        set(value) = sp.edit { putBoolean(KEY_SETUP, value) }

    companion object {
        private const val FILE = "jarvis_prefs"
        private const val KEY_NAME = "assistant_name"
        private const val KEY_USER = "user_name"
        private const val KEY_PROVIDER = "primary_provider"
        private const val KEY_OR_MODEL = "openrouter_model"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_FALLBACK = "auto_fallback"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_SPEED = "voice_speed"
        private const val KEY_PITCH = "voice_pitch"
        private const val KEY_WAKE = "wake_word"
        private const val KEY_SETUP = "setup_done"

        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"
        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"

        val OPENROUTER_PRESETS = listOf(
            "openai/gpt-4o-mini",
            "google/gemini-2.0-flash-001",
            "meta-llama/llama-3.3-70b-instruct",
            "deepseek/deepseek-chat",
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o"
        )

        val GROQ_PRESETS = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768",
            "gemma2-9b-it"
        )
    }
}
