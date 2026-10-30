package com.moon.avatar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Voz humana del avatar mediante Gemini TTS. Si la petición o la reproducción
 * falla, MainActivity puede continuar con la voz local del teléfono.
 */
class Voice(private val brain: Brain) {

    @Volatile private var playing = false
    @Volatile private var stopped = false
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    var lastError: String = ""
        private set

    @Synchronized
    fun stop() {
        generation++
        stopped = true
        playing = false
        releaseTrack()
    }

    val isPlaying: Boolean get() = playing

    /**
     * Pide una sola pista de audio para la respuesta completa. Los callbacks se
     * llaman en el hilo secundario; quien los use debe saltar al hilo principal.
     */
    @Synchronized
    fun speak(text: String, voiceName: String, onStart: () -> Unit, onDone: (Boolean) -> Unit) {
        generation++
        val requestId = generation
        releaseTrack()
        stopped = false
        lastError = ""
        thread(name = "moon-avatar-tts") {
            var ok = false
            try {
                val audio = fetchTtsAudio(text, voiceName, requestId)
                if (audio != null && isActive(requestId)) {
                    ok = playPcm(audio.first, audio.second, requestId, onStart)
                }
            } catch (e: Exception) {
                if (isActive(requestId)) {
                    lastError = e.message?.take(140) ?: "Error reproduciendo la voz"
                }
            } finally {
                if (generation == requestId) playing = false
                onDone(ok)
            }
        }
    }

    private fun isActive(requestId: Int): Boolean = !stopped && generation == requestId

    private fun releaseTrack() {
        val current = track
        track = null
        try { current?.stop() } catch (e: Exception) { /* ya parada */ }
        try { current?.release() } catch (e: Exception) { /* noop */ }
    }

    /** Pide el audio a Gemini TTS. Devuelve (PCM, frecuencia de muestreo). */
    private fun fetchTtsAudio(text: String, voiceName: String, requestId: Int): Pair<ByteArray, Int>? {
        val key = brain.apiKey
        if (key.isBlank() || brain.provider != "gemini") {
            if (isActive(requestId)) lastError = "La voz humana requiere una clave de Gemini"
            return null
        }

        val keyFingerprint = fingerprint(key)
        if (keysWithoutTts.containsKey(keyFingerprint)) {
            lastError = "Esta clave no tiene un modelo de voz Gemini disponible"
            return null
        }
        val cached = getCachedTtsModel(keyFingerprint)
        val models = (listOfNotNull(cached) + TTS_MODELS).distinct()
        var rejectedModels = 0
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", voiceName)))))

        for (model in models) {
            if (!isActive(requestId)) return null
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val result = postJson(url, body, key)
            if (!isActive(requestId)) return null
            val raw = result.text
            if (raw == null) {
                lastError = describeTtsError(result)
                if (isModelUnavailable(result)) {
                    rejectedModels++
                    ttsModelsByKey.remove(keyFingerprint, model)
                    continue
                }
                if (result.code == 401 || result.code == 403) {
                    keysWithoutTts[keyFingerprint] = true
                }
                return null
            }
            try {
                val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val mime = inline.optString("mimeType", "audio/L16;rate=24000")
                    val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                    val data = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                    if (data.isNotEmpty() && isActive(requestId)) {
                        rememberTtsModel(keyFingerprint, model)
                        lastError = ""
                        return data to rate
                    }
                }
                rejectedModels++
                lastError = "Gemini no devolvió audio utilizable ($model)"
            } catch (e: Exception) {
                rejectedModels++
                if (isActive(requestId)) lastError = "No pude interpretar el audio de Gemini ($model)"
            }
        }
        if (rejectedModels >= models.size) {
            keysWithoutTts[keyFingerprint] = true
            lastError = "Esta clave no tiene un modelo de voz Gemini disponible"
        }
        return null
    }

    private fun getCachedTtsModel(keyFingerprint: String): String? = ttsModelsByKey[keyFingerprint]

    private fun rememberTtsModel(keyFingerprint: String, model: String) {
        ttsModelsByKey[keyFingerprint] = model
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class HttpResult(val code: Int, val text: String?, val errorBody: String?)

    private fun postJson(url: URL, body: JSONObject, apiKey: String): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (code in 200..299) HttpResult(code, response, null)
            else HttpResult(code, null, response)
        } catch (e: Exception) {
            HttpResult(-1, null, e.message)
        } finally {
            connection?.disconnect()
        }
    }

    private fun isModelUnavailable(result: HttpResult): Boolean {
        if (result.code == 404) return true
        if (result.code != 400) return false
        val error = result.errorBody.orEmpty().lowercase()
        return error.contains("model") && listOf(
            "not found", "does not exist", "not supported", "unsupported", "not available"
        ).any { it in error }
    }

    private fun describeTtsError(result: HttpResult): String {
        val detail = try {
            if (result.errorBody.isNullOrBlank()) "" else JSONObject(result.errorBody)
                .optJSONObject("error")?.optString("message")?.take(120).orEmpty()
        } catch (e: Exception) {
            result.errorBody?.take(120).orEmpty()
        }
        return when {
            result.code == -1 -> "No se pudo conectar con Gemini para generar voz"
            result.code == 401 || result.code == 403 -> "Gemini rechazó la clave para generar voz"
            result.code == 429 -> "Gemini alcanzó el límite gratuito de generación de voz"
            result.code == 404 -> "Ese modelo de voz de Gemini ya no está disponible"
            else -> "Gemini TTS respondió ${result.code}: $detail"
        }
    }

    /**
     * Reproduce PCM mono de 16 bits. AudioTrack.write ya espera mientras va
     * sonando el audio; esperar otra vez toda su duración al final duplicaba el
     * tiempo y creaba pausas enormes entre frases. Ahora esperamos al cabezal
     * real de reproducción y detectamos escrituras truncadas.
     */
    private fun playPcm(pcm: ByteArray, sampleRate: Int, requestId: Int, onStart: () -> Unit): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            lastError = "Este teléfono no acepta el formato de audio de Gemini"
            return false
        }
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minBuffer, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (!isActive(requestId)) {
            audioTrack.release()
            return false
        }
        track = audioTrack
        try {
            if (!isActive(requestId)) return false
            audioTrack.play()
            if (!isActive(requestId)) return false
            playing = true
            onStart()
            var offset = 0
            while (offset < pcm.size && isActive(requestId)) {
                val count = minOf(4096, pcm.size - offset)
                val written = audioTrack.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    if (isActive(requestId)) lastError = "El teléfono interrumpió la reproducción de voz"
                    return false
                }
                if (written == 0) {
                    Thread.sleep(10)
                    continue
                }
                offset += written
            }
            if (!isActive(requestId) || offset < pcm.size) {
                if (isActive(requestId)) lastError = "La reproducción de voz se interrumpió antes de terminar"
                return false
            }

            val totalFrames = offset.toLong() / 2L
            val durationMs = totalFrames * 1000L / sampleRate
            val deadline = SystemClock.elapsedRealtime() + durationMs + 1500L
            while (isActive(requestId) && playbackFrames(audioTrack) < totalFrames &&
                SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(20)
            }
            if (!isActive(requestId) || playbackFrames(audioTrack) < totalFrames) {
                if (isActive(requestId)) lastError = "La reproducción de voz no llegó al final"
                return false
            }
            return true
        } finally {
            if (generation == requestId) playing = false
            if (track === audioTrack) track = null
            try { if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop() }
            catch (e: Exception) { /* puede haberse detenido desde stop() */ }
            try { audioTrack.release() } catch (e: Exception) { /* noop */ }
        }
    }

    private fun playbackFrames(audioTrack: AudioTrack): Long =
        audioTrack.playbackHeadPosition.toLong() and 0xffffffffL

    companion object {
        private val ttsModelsByKey = ConcurrentHashMap<String, String>()
        private val keysWithoutTts = ConcurrentHashMap<String, Boolean>()
        private val TTS_MODELS = listOf(
            "gemini-3.1-flash-tts-preview",
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts",
            "gemini-2.0-flash-preview-tts"
        )
    }
}
