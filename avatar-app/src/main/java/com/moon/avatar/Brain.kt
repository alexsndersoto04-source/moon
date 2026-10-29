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
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
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
        val raw = postJson(url, body, null) ?: return null
        val text = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return parseReply(text)
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
        val raw = postJson(url, body, "Bearer $key") ?: return null
        val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return parseReply(text)
    }

    private fun postJson(url: URL, body: JSONObject, auth: String?): String? {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (auth != null) conn.setRequestProperty("Authorization", auth)
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        conn.disconnect()
        return if (code in 200..299) text else null
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
                "Uy, no pude conectar con mi cerebro en la nube. Revisa tu internet o tu clave en Ajustes, ¿sí?",
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
