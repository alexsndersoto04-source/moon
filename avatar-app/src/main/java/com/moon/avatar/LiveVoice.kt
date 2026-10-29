package com.moon.avatar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * MODO EN VIVO (Gemini Live API): conversación hablada en tiempo real.
 *
 * Nada de encadenar STT -> LLM -> TTS (eso era el retraso de segundos):
 * un WebSocket abierto manda tu voz en streaming (PCM 16 kHz) y la IA
 * devuelve su voz humana en streaming (PCM 24 kHz) mientras “piensa”.
 * Puedes interrumpirla hablando: el servidor avisa y se corta su audio.
 *
 * Eventos hacia la UI por [Listener] (siempre saltar al hilo principal).
 */
class LiveVoice(
    private val context: Context,
    private val brain: Brain,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onSpeaking(speaking: Boolean)
        fun onUserText(text: String)
        fun onAvatarText(text: String)
        fun onInterrupted()
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var setupDone = false
    private var modelIndex = 0

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var playThread: Thread? = null
    @Volatile private var playRunning = false
    @Volatile private var speakingNow = false

    // Modelos Live vigentes (si uno no existe en la cuenta, se prueba el siguiente)
    private val models = listOf(
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-12-2025",
        "gemini-2.5-flash-native-audio-preview-09-2025",
        "gemini-2.0-flash-live-001"
    )

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        if (brain.apiKey.isBlank() || brain.provider != "gemini") {
            listener.onDisconnected("Pon tu clave de Gemini en Ajustes para el modo en vivo")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onDisconnected("Falta el permiso del micrófono")
            return
        }
        running = true
        modelIndex = 0
        connect()
    }

    fun stop() {
        running = false
        stopCapture()
        stopPlayback()
        try { ws?.close(1000, "bye") } catch (e: Exception) { /* noop */ }
        ws = null
    }

    private fun connect() {
        if (!running) return
        setupDone = false
        val model = models[modelIndex]
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${brain.apiKey}"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendSetup(model)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!setupDone && modelIndex < models.lastIndex - 1 && running) {
                    modelIndex++
                    connect()
                } else if (running) {
                    running = false
                    stopCapture(); stopPlayback()
                    listener.onDisconnected("Se cortó el modo en vivo: ${t.message ?: "sin conexión"}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!setupDone && code != 1000 && modelIndex < models.lastIndex - 1 && running) {
                    modelIndex++
                    try { webSocket.close(1000, null) } catch (e: Exception) { /* noop */ }
                    connect()
                } else if (running) {
                    running = false
                    stopCapture(); stopPlayback()
                    listener.onDisconnected("Modo en vivo cerrado ($code)")
                }
            }
        })
    }

    private fun sendSetup(model: String) {
        val voiceName = when (brain.voiceMode) {
            "orus" -> "Orus"
            "enceladus" -> "Enceladus"
            else -> "Charon"
        }
        val user = if (brain.userName.isBlank()) "" else " La persona se llama ${brain.userName}."
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", voiceName)))))
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text",
                    "Eres ${brain.avatarName}, un avatar humano que conversa EN VIVO por voz. " +
                    "Tu personalidad: ${brain.personality}$user " +
                    "Habla en español, de forma natural y breve, como en una conversación real. " +
                    "Nada de listas ni textos largos: frases cortas habladas."))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        ws?.send(JSONObject().put("setup", setup).toString())
    }

    private fun handleServerMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (msg.has("setupComplete")) {
            setupDone = true
            startCapture()
            startPlayback()
            listener.onConnected()
            return
        }
        val content = msg.optJSONObject("serverContent") ?: return
        if (content.optBoolean("interrupted", false)) {
            clearPlayback()
            setSpeaking(false)
            listener.onInterrupted()
        }
        content.optJSONObject("inputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let { listener.onUserText(it) }
        content.optJSONObject("outputTranscription")?.optString("text")
            ?.takeIf { it.isNotBlank() }?.let { listener.onAvatarText(it) }
        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val mime = inline.optString("mimeType")
            if (!mime.startsWith("audio")) continue
            val pcm = Base64.decode(inline.optString("data"), Base64.DEFAULT)
            if (pcm.isNotEmpty()) {
                if (!speakingNow) setSpeaking(true)
                playQueue.offer(pcm)
            }
        }
        if (content.optBoolean("turnComplete", false)) {
            // La boca se apaga cuando la cola de audio termine de sonar
            playQueue.offer(TURN_END)
        }
    }

    private fun setSpeaking(v: Boolean) {
        if (speakingNow != v) {
            speakingNow = v
            listener.onSpeaking(v)
        }
    }

    // ---------- Captura: tu voz en streaming (PCM 16 kHz) ----------
    @SuppressLint("MissingPermission")
    private fun startCapture() {
        stopCapture()
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 4096))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            listener.onDisconnected("No se pudo abrir el micrófono")
            return
        }
        recorder = rec
        rec.startRecording()
        captureThread = thread(name = "live-capture") {
            val buffer = ByteArray(3200) // 100 ms a 16 kHz 16-bit mono
            while (running && recorder === rec) {
                val n = try { rec.read(buffer, 0, buffer.size) } catch (e: Exception) { -1 }
                if (n > 0) {
                    val chunk = buffer.copyOf(n)
                    val payload = JSONObject().put("realtimeInput", JSONObject()
                        .put("audio", JSONObject()
                            .put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                            .put("mimeType", "audio/pcm;rate=16000")))
                    ws?.send(payload.toString())
                }
            }
        }
    }

    private fun stopCapture() {
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (e: Exception) { /* noop */ }
        try { rec?.release() } catch (e: Exception) { /* noop */ }
        captureThread = null
    }

    // ---------- Reproducción: su voz en streaming (PCM 24 kHz) ----------
    private fun startPlayback() {
        if (playRunning) return
        val sampleRate = 24000
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
        playRunning = true
        t.play()
        playThread = thread(name = "live-playback") {
            while (playRunning) {
                val item = try { playQueue.poll(200, TimeUnit.MILLISECONDS) }
                    catch (e: InterruptedException) { null } ?: continue
                if (item === TURN_END) {
                    setSpeaking(false)
                    continue
                }
                var offset = 0
                while (offset < item.size && playRunning) {
                    val written = try { t.write(item, offset, item.size - offset) }
                        catch (e: Exception) { -1 }
                    if (written <= 0) break
                    offset += written
                }
            }
        }
    }

    private fun clearPlayback() {
        playQueue.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) { /* noop */ }
    }

    private fun stopPlayback() {
        playRunning = false
        playQueue.clear()
        setSpeaking(false)
        try { track?.stop() } catch (e: Exception) { /* noop */ }
        try { track?.release() } catch (e: Exception) { /* noop */ }
        track = null
        playThread = null
    }

    companion object {
        private val TURN_END = ByteArray(0)
    }
}
