package com.moon.avatar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * La voz HUMANA del avatar: la genera la IA (Gemini TTS), no el sintetizador
 * robótico del teléfono. Devuelve audio PCM real de una voz masculina con
 * entonación natural y lo reproduce con AudioTrack.
 *
 * Usa la MISMA API key de Gemini que el cerebro. Si falla (sin clave, modelo
 * no disponible…), MainActivity cae a la voz del sistema como respaldo.
 */
class Voice(private val brain: Brain) {

    @Volatile private var playing = false
    @Volatile private var stopped = false
    private var track: AudioTrack? = null

    var lastError: String = ""
        private set

    fun stop() {
        stopped = true
        playing = false
        try { track?.stop() } catch (e: Exception) { /* ya parada */ }
        try { track?.release() } catch (e: Exception) { /* noop */ }
        track = null
    }

    val isPlaying: Boolean get() = playing

    /**
     * Habla [text] con voz humana. [onStart]/[onDone] se llaman en hilo
     * secundario: quien llame debe saltar al hilo principal para tocar la UI.
     */
    fun speak(text: String, voiceName: String, onStart: () -> Unit, onDone: (Boolean) -> Unit) {
        stopped = false
        thread {
            var ok = false
            try {
                val audio = fetchTtsAudio(text, voiceName)
                if (audio != null && !stopped) {
                    ok = playPcm(audio.first, audio.second, onStart)
                }
            } catch (e: Exception) {
                lastError = e.message ?: "error de voz"
            }
            playing = false
            onDone(ok)
        }
    }

    /** Pide el audio a Gemini TTS. Devuelve (bytes PCM, sampleRate). */
    private fun fetchTtsAudio(text: String, voiceName: String): Pair<ByteArray, Int>? {
        val key = brain.apiKey
        if (key.isBlank() || brain.provider != "gemini") {
            lastError = "La voz humana requiere el cerebro Gemini con tu clave"
            return null
        }
        val models = listOf(
            "gemini-3.1-flash-tts-preview",
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts",
            "gemini-2.0-flash-preview-tts"
        )
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
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val result = postJson(url, body, key) ?: continue
            try {
                val parts = JSONObject(result).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inline = part.optJSONObject("inlineData") ?: continue
                    val mime = inline.optString("mimeType", "audio/L16;rate=24000")
                    val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                    val data = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                    if (data.isNotEmpty()) return data to rate
                }
                lastError = "Gemini no devolvió audio en $model"
            } catch (e: Exception) {
                lastError = "Respuesta de voz rara en $model"
            }
        }
        return null
    }

    private fun postJson(url: URL, body: JSONObject, apiKey: String): String? {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code in 200..299) text else {
                lastError = "Voz Gemini HTTP $code"
                null
            }
        } catch (e: Exception) {
            lastError = e.message ?: "sin conexión para la voz"
            null
        }
    }

    /** Reproduce PCM 16-bit mono; bloquea hasta terminar o hasta stop(). */
    private fun playPcm(pcm: ByteArray, sampleRate: Int, onStart: () -> Unit): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        playing = true
        stopped = false
        t.play()
        onStart()
        var offset = 0
        val chunk = 4096
        while (offset < pcm.size && !stopped) {
            val n = minOf(chunk, pcm.size - offset)
            val written = t.write(pcm, offset, n)
            if (written <= 0) break
            offset += written
        }
        // Esperar a que suene lo que queda en el buffer
        if (!stopped) {
            val totalFrames = pcm.size / 2
            var waitedMs = 0L
            val approxMs = (totalFrames * 1000L / sampleRate) + 350L
            while (!stopped && waitedMs < approxMs && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(60)
                waitedMs += 60
            }
        }
        val completed = !stopped
        try { t.stop() } catch (e: Exception) { /* noop */ }
        t.release()
        if (track === t) track = null
        playing = false
        return completed
    }
}
