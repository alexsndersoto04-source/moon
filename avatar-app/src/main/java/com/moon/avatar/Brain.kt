package com.moon.avatar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Emociones que el cerebro puede pedir en cada respuesta. */
enum class Emotion { NEUTRAL, HAPPY, SAD, SURPRISED, THINKING, EXCITED, SHY }

/** Gestos que el cerebro puede pedir: el avatar los actúa solo, sin botones. */
enum class Gesture { NONE, WAVE, NOD, SHAKE, THINK, CELEBRATE, BOW }

/** Lo que el cerebro decide: qué decir + cómo sentirlo + qué gesto hacer. */
data class AvatarReply(
    val text: String,
    val emotion: Emotion = Emotion.HAPPY,
    val gesture: Gesture = Gesture.NONE
)

/**
 * El cerebro del avatar.
 *
 * - Con API key (Gemini o Groq): IA real en la nube, con personalidad y memoria.
 *   La IA responde en JSON: {"text": "...", "emotion": "...", "gesture": "..."}
 *   para que el avatar ACTÚE la respuesta, no solo la escriba.
 * - Sin API key o sin conexión: cerebro local de respaldo — el avatar nunca
 *   se queda mudo ni convertido en una pantalla de chat.
 *
 * La memoria (últimas conversaciones + datos del usuario) vive SOLO en el
 * teléfono (SharedPreferences privadas). La clave API también.
 */
class Brain(context: Context) {

    /** Último error real del cerebro en la nube (para mostrarlo, no adivinar). */
    @Volatile var lastError: String = ""
        private set

    private val prefs = context.getSharedPreferences("moon_avatar", Context.MODE_PRIVATE)

    // ---------- Ajustes ----------
    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(v) = prefs.edit().putString("provider", v).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var avatarName: String
        get() = prefs.getString("avatar_name", "Luna") ?: "Luna"
        set(v) = prefs.edit().putString("avatar_name", v).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v).apply()

    var personality: String
        get() = prefs.getString("personality",
            "Eres cercana, alegre y curiosa. Hablas en español, con frases cortas y naturales, como una persona de verdad. Te gusta ayudar y conversar.") ?: ""
        set(v) = prefs.edit().putString("personality", v).apply()

    // ---------- Voz ----------
    /** charon/orus/enceladus = voces humanas de Gemini; system = la del teléfono. */
    var voiceMode: String
        get() = prefs.getString("voice_mode", "charon") ?: "charon"
        set(v) = prefs.edit().putString("voice_mode", v).apply()

    // ---------- Cuerpo 3D ----------
    /** URL de un .glb propio (ej. Ready Player Me desde una selfie). Vacío = modelo de serie. */
    var modelUrl: String
        get() = prefs.getString("model_url", "") ?: ""
        set(v) = prefs.edit().putString("model_url", v.trim()).apply()

    // ---------- Memoria ----------
    private fun history(): JSONArray =
        try { JSONArray(prefs.getString("history", "[]")) } catch (e: Exception) { JSONArray() }

    private fun remember(role: String, text: String) {
        val h = history()
        h.put(JSONObject().put("role", role).put("text", text))
        while (h.length() > 16) h.remove(0)
        prefs.edit().putString("history", h.toString()).apply()
    }

    fun forgetAll() = prefs.edit().remove("history").apply()

    // ---------- Pensar ----------
    fun think(userText: String, callback: (AvatarReply) -> Unit) {
        remember("user", userText)
        val key = apiKey
        if (key.isBlank()) {
            val reply = localBrain(userText)
            remember("model", reply.text)
            callback(reply)
            return
        }
        thread {
            val reply = try {
                if (provider == "groq") askGroq(key, userText) else askGemini(key, userText)
            } catch (e: Exception) {
                null
            } ?: localBrain(userText, sinConexion = true)
            remember("model", reply.text)
            callback(reply)
        }
    }

    private fun systemPrompt(): String {
        val user = if (userName.isBlank()) "la persona que te habla" else userName
        return """
            Eres $avatarName, un avatar humano que vive dentro de una app de teléfono.
            Tu personalidad: $personality
            Estás hablando con $user.
            Responde SIEMPRE solo con un JSON válido, sin texto fuera del JSON:
            {"text": "tu respuesta hablada, breve (1-3 frases)", "emotion": "happy|sad|surprised|thinking|excited|shy|neutral", "gesture": "wave|nod|shake|think|celebrate|bow|none"}
            Elige emotion y gesture según lo que digas: saluda con wave, afirma con nod,
            niega con shake, duda con think, celebra con celebrate.
            Habla como persona, nunca digas que eres un modelo de lenguaje ni menciones el JSON.
        """.trimIndent()
    }

    private fun askGemini(key: String, userText: String): AvatarReply? {
        val contents = JSONArray()
        val h = history()
        for (i in 0 until h.length()) {
            val m = h.getJSONObject(i)
            contents.put(JSONObject()
                .put("role", if (m.getString("role") == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", m.getString("text")))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.8).put("maxOutputTokens", 300))
        // Nada de adivinar nombres: primero preguntar a Google que modelos
        // tiene ESTA clave/proyecto y usar los que de verdad existan.
        val discovered = discoverGeminiModels(key)
        val models = (discovered + listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.0-flash", "gemini-2.5-flash-lite")).distinct()
        for (model in models) {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val result = postJson(url, body, null, "x-goog-api-key" to key)
            if (result.text != null) {
                try {
                    val text = JSONObject(result.text).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    lastError = ""
                    return parseReply(text)
                } catch (e: Exception) {
                    lastError = "Respuesta rara de Gemini ($model)"
                    continue
                }
            }
            lastError = describeHttpError("Gemini", result.code, result.errorBody)
            // Clave mala o sin permisos: no tiene sentido probar mas modelos.
            if (result.code == 400 && result.errorBody?.contains("API_KEY_INVALID") == true) break
            if (result.code == 401 || result.code == 403) break
        }
        return null
    }

    /** Lista real de modelos generateContent que la clave puede usar (o vacio). */
    private fun discoverGeminiModels(key: String): List<String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
        val result = getJson(url, "x-goog-api-key" to key)
        val raw = result.text ?: run {
            lastError = describeHttpError("Gemini", result.code, result.errorBody)
            return emptyList()
        }
        return try {
            val arr = JSONObject(raw).optJSONArray("models") ?: return emptyList()
            val found = mutableListOf<Pair<Int, String>>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (!methods.contains("generateContent")) continue
                val id = m.optString("name").removePrefix("models/")
                if (id.isBlank()) continue
                val rank = when {
                    id.contains("2.5") && id.contains("flash") && !id.contains("lite") -> 0
                    id.contains("flash") && !id.contains("lite") && !id.contains("image") -> 1
                    id.contains("flash") -> 2
                    id.contains("pro") -> 3
                    id.contains("Gemini") -> 4
                    else -> 5
                }
                found.add(rank to id)
            }
            found.sortedBy { it.first }.map { it.second }.take(6)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getJson(url: URL, vararg headers: Pair<String, String>): HttpResult {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code in 200..299) HttpResult(code, text, null) else HttpResult(code, null, text)
        } catch (e: Exception) {
            HttpResult(-1, null, e.message)
        }
    }

    private fun askGroq(key: String, userText: String): AvatarReply? {
        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        val h = history()
        for (i in 0 until h.length()) {
            val m = h.getJSONObject(i)
            messages.put(JSONObject()
                .put("role", if (m.getString("role") == "user") "user" else "assistant")
                .put("content", m.getString("text")))
        }
        val body = JSONObject()
            .put("model", "llama-3.3-70b-versatile")
            .put("messages", messages)
            .put("temperature", 0.8)
            .put("max_tokens", 300)
        val result = postJson(url, body, "Bearer $key")
        val raw = result.text ?: run {
            lastError = describeHttpError("Groq", result.code, result.errorBody)
            return null
        }
        val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return parseReply(text)
    }

    private data class HttpResult(val code: Int, val text: String?, val errorBody: String?)

    private fun postJson(url: URL, body: JSONObject, auth: String?, vararg headers: Pair<String, String>): HttpResult {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (auth != null) conn.setRequestProperty("Authorization", auth)
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code in 200..299) HttpResult(code, text, null) else HttpResult(code, null, text)
        } catch (e: Exception) {
            HttpResult(-1, null, e.message)
        }
    }

    private fun describeHttpError(providerName: String, code: Int, body: String?): String {
        val detail = try {
            if (body.isNullOrBlank()) "" else JSONObject(body).optJSONObject("error")
                ?.optString("message")?.take(140) ?: body.take(140)
        } catch (e: Exception) { body?.take(140) ?: "" }
        return when {
            code == -1 -> "Sin conexión con $providerName (revisa tu internet)"
            code == 400 && detail.contains("API_KEY_INVALID") ->
                "La clave de $providerName no es válida: vuelve a copiarla en Ajustes"
            code == 401 || code == 403 ->
                "Clave rechazada por $providerName ($code): revisa tu clave en Ajustes"
            code == 404 -> "Modelo no disponible en $providerName (404)"
            code == 429 -> "Límite gratuito de $providerName alcanzado: espera un poco"
            else -> "$providerName respondió $code: $detail"
        }
    }

    private fun parseReply(raw: String): AvatarReply {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val j = JSONObject(raw.substring(start, end + 1))
                val text = j.optString("text").ifBlank { raw }
                return AvatarReply(text, parseEmotion(j.optString("emotion")),
                    parseGesture(j.optString("gesture")))
            } catch (e: Exception) { /* cae a texto plano */ }
        }
        return AvatarReply(raw.trim())
    }

    private fun parseEmotion(s: String): Emotion = when (s.lowercase()) {
        "happy" -> Emotion.HAPPY
        "sad" -> Emotion.SAD
        "surprised" -> Emotion.SURPRISED
        "thinking" -> Emotion.THINKING
        "excited" -> Emotion.EXCITED
        "shy" -> Emotion.SHY
        else -> Emotion.NEUTRAL
    }

    private fun parseGesture(s: String): Gesture = when (s.lowercase()) {
        "wave" -> Gesture.WAVE
        "nod" -> Gesture.NOD
        "shake" -> Gesture.SHAKE
        "think" -> Gesture.THINK
        "celebrate" -> Gesture.CELEBRATE
        "bow" -> Gesture.BOW
        else -> Gesture.NONE
    }

    // ---------- Cerebro local de respaldo ----------
    private fun localBrain(userText: String, sinConexion: Boolean = false): AvatarReply {
        val t = userText.lowercase()
        val name = if (userName.isBlank()) "" else " $userName"
        return when {
            sinConexion -> AvatarReply(
                if (lastError.isNotBlank()) "Uy, mi cerebro falló: $lastError"
                else "Uy, no pude conectar con mi cerebro en la nube. Revisa tu internet o tu clave en Ajustes, ¿sí?",
                Emotion.THINKING, Gesture.THINK)
            t.contains("hola") || t.contains("buenas") -> AvatarReply(
                "¡Hola$name! Qué bueno verte. ¿En qué te ayudo hoy?",
                Emotion.HAPPY, Gesture.WAVE)
            t.contains("tu nombre") || t.contains("cómo te llamas") || t.contains("como te llamas") -> AvatarReply(
                "Me llamo $avatarName. Puedes cambiarme el nombre, la personalidad y hasta el look en Ajustes.",
                Emotion.HAPPY, Gesture.NOD)
            t.contains("gracias") -> AvatarReply(
                "¡De nada! Para eso estoy.", Emotion.HAPPY, Gesture.BOW)
            t.contains("triste") || t.contains("mal día") || t.contains("mal dia") -> AvatarReply(
                "Lo siento mucho… Estoy aquí contigo. Cuéntame qué pasó, ¿quieres?",
                Emotion.SAD, Gesture.NONE)
            t.contains("chiste") || t.contains("ríe") || t.contains("rie") -> AvatarReply(
                "¿Por qué el libro de matemáticas estaba triste? ¡Porque tenía demasiados problemas!",
                Emotion.EXCITED, Gesture.CELEBRATE)
            t.contains("baile") || t.contains("baila") || t.contains("saluda") -> AvatarReply(
                "¡Claro! Mira esto.", Emotion.EXCITED, Gesture.CELEBRATE)
            t.contains("piensa") || t.contains("qué opinas") || t.contains("que opinas") -> AvatarReply(
                "Déjame pensarlo un momento… Creo que es una gran idea.",
                Emotion.THINKING, Gesture.THINK)
            t.contains("adiós") || t.contains("adios") || t.contains("chao") -> AvatarReply(
                "¡Hasta luego$name! Aquí estaré cuando vuelvas.", Emotion.HAPPY, Gesture.WAVE)
            else -> AvatarReply(
                "Te escucho$name. Todavía estoy sin mi cerebro completo: pon tu clave gratuita de Gemini o Groq en Ajustes y podré conversar de todo contigo.",
                Emotion.NEUTRAL, Gesture.NONE)
        }
    }
}
