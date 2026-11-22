package com.airgesture.transfer.jarvis.ai

import com.airgesture.transfer.jarvis.data.ApiKeyStore
import com.airgesture.transfer.jarvis.data.JarvisPrefs
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class Provider { OPENROUTER, GROQ }

data class ChatMessage(val role: String, val content: String)

data class AiResult(
    val text: String,
    val provider: Provider,
    val model: String
)

class AiException(message: String) : Exception(message)

/**
 * Cliente de IA con doble proveedor y fallback automático.
 * Ambos hablan el dialecto OpenAI (/chat/completions), así que el mismo
 * cuerpo sirve para OpenRouter y para Groq.
 */
class AiClient(
    private val keys: ApiKeyStore,
    private val prefs: JarvisPrefs
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    fun availableProviders(): List<Provider> {
        val list = mutableListOf<Provider>()
        if (keys.hasOpenRouter()) list.add(Provider.OPENROUTER)
        if (keys.hasGroq()) list.add(Provider.GROQ)
        return list
    }

    fun hasAnyKey(): Boolean = keys.hasAnyKey()

    /** Orden de intento según la preferencia del usuario. */
    fun providerOrder(): List<Provider> {
        val available = availableProviders()
        if (available.isEmpty()) return emptyList()
        val primary = when (prefs.primaryProvider) {
            "OPENROUTER" -> Provider.OPENROUTER
            "GROQ" -> Provider.GROQ
            else -> available.first()
        }
        if (!prefs.autoFallback) {
            return if (available.contains(primary)) listOf(primary) else listOf(available.first())
        }
        val ordered = mutableListOf<Provider>()
        if (available.contains(primary)) ordered.add(primary)
        for (p in available) if (!ordered.contains(p)) ordered.add(p)
        return ordered
    }

    suspend fun chat(messages: List<ChatMessage>): AiResult {
        val order = providerOrder()
        if (order.isEmpty()) {
            throw AiException(
                "No hay ninguna clave API configurada. Abre Ajustes y añade tu clave de OpenRouter o de Groq."
            )
        }
        var lastError: String? = null
        for (provider in order) {
            try {
                return chatWithProvider(provider, messages)
            } catch (e: Exception) {
                lastError = e.message
                // Si queda otro proveedor, se intenta automáticamente.
            }
        }
        throw AiException(lastError ?: "La IA no respondió. Revisa tu conexión a internet.")
    }

    suspend fun chatWithProvider(
        provider: Provider,
        messages: List<ChatMessage>
    ): AiResult = withContext(Dispatchers.IO) {
        val (url, apiKey, model) = when (provider) {
            Provider.OPENROUTER -> Triple(
                URL_OPENROUTER,
                keys.openRouterKey,
                prefs.openRouterModel.ifBlank { JarvisPrefs.DEFAULT_OPENROUTER_MODEL }
            )
            Provider.GROQ -> Triple(
                URL_GROQ,
                keys.groqKey,
                prefs.groqModel.ifBlank { JarvisPrefs.DEFAULT_GROQ_MODEL }
            )
        }
        if (apiKey.isBlank()) {
            throw AiException(
                if (provider == Provider.OPENROUTER) "Falta la clave de OpenRouter."
                else "Falta la clave de Groq."
            )
        }

        val body = buildRequestBody(messages, model)
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMedia))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
        if (provider == Provider.OPENROUTER) {
            builder.addHeader("HTTP-Referer", "https://github.com/alexsndersoto04-source/moon")
            builder.addHeader("X-Title", "JARVIS Asistente")
        }

        val response = http.newCall(builder.build()).execute()
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw AiException(parseApiError(response.code, raw, provider))
        }
        val text = parseContent(raw)
        if (text.isBlank()) throw AiException("La IA devolvió una respuesta vacía (${provider.name}).")
        AiResult(text = text, provider = provider, model = model)
    }

    /** Prueba de conexión usada en la pantalla de configuración. */
    suspend fun testConnection(provider: Provider): String {
        val result = chatWithProvider(
            provider,
            listOf(
                ChatMessage("system", "Responde únicamente con la palabra OK."),
                ChatMessage("user", "Di solo: OK")
            )
        )
        return "Conectado con ${provider.name} · modelo ${result.model} · respondió: ${result.text.take(40)}"
    }

    private fun buildRequestBody(messages: List<ChatMessage>, model: String): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("temperature", 0.7)
        root.addProperty("max_tokens", 1200)
        val arr = JsonArray()
        for (m in messages) {
            val o = JsonObject()
            o.addProperty("role", m.role)
            o.addProperty("content", m.content)
            arr.add(o)
        }
        root.add("messages", arr)
        return root.toString()
    }

    private fun parseContent(raw: String): String {
        return try {
            val root = JsonParser.parseString(raw).asJsonObject
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return ""
            val first = choices[0].asJsonObject
            val message = first.getAsJsonObject("message")
            message?.get("content")?.let { if (!it.isJsonNull) return it.asString }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseApiError(code: Int, raw: String, provider: Provider): String {
        val detail = try {
            val root = JsonParser.parseString(raw).asJsonObject
            val err = root.getAsJsonObject("error")
            err?.get("message")?.asString ?: raw.take(200)
        } catch (e: Exception) {
            raw.take(200)
        }
        return when (code) {
            401, 403 -> "Clave de ${provider.name} rechazada (401/403). Revisa que la hayas copiado completa."
            404 -> "Modelo no encontrado en ${provider.name}. Cambia el modelo en Ajustes."
            429 -> "Límite de ${provider.name} alcanzado (429). Espera un minuto o cambia de proveedor."
            in 500..599 -> "El servidor de ${provider.name} falló ($code). Inténtalo de nuevo."
            else -> "Error de ${provider.name} ($code): $detail"
        }
    }

    companion object {
        const val URL_OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"
        const val URL_GROQ = "https://api.groq.com/openai/v1/chat/completions"
    }
}
