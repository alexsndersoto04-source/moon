package com.airgesture.transfer.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Almacén seguro (solo en el dispositivo) de las dos claves API de IA.
 * Nunca se envían a ningún servidor propio: solo se usan en las cabeceras
 * Authorization contra OpenRouter y Groq desde el propio teléfono.
 */
class ApiKeyStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var openRouterKey: String
        get() = sp.getString(KEY_OPENROUTER, "").orEmpty().trim()
        set(value) = sp.edit { putString(KEY_OPENROUTER, value.trim()) }

    var groqKey: String
        get() = sp.getString(KEY_GROQ, "").orEmpty().trim()
        set(value) = sp.edit { putString(KEY_GROQ, value.trim()) }

    fun hasOpenRouter(): Boolean = openRouterKey.isNotBlank()
    fun hasGroq(): Boolean = groqKey.isNotBlank()
    fun hasAnyKey(): Boolean = hasOpenRouter() || hasGroq()

    /** Enmascara la clave para mostrarla en pantalla: sk-…abcd */
    fun mask(key: String): String {
        val k = key.trim()
        if (k.length <= 8) return if (k.isBlank()) "(sin clave)" else "••••"
        return k.take(3) + "••••••••" + k.takeLast(4)
    }

    fun clearAll() {
        sp.edit { remove(KEY_OPENROUTER); remove(KEY_GROQ) }
    }

    companion object {
        private const val FILE = "jarvis_api_keys"
        private const val KEY_OPENROUTER = "openrouter_key"
        private const val KEY_GROQ = "groq_key"
    }
}
