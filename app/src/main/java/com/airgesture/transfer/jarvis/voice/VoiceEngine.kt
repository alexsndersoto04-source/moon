package com.airgesture.transfer.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.airgesture.transfer.jarvis.data.JarvisPrefs
import java.util.Locale
import java.util.UUID

/**
 * Motor de voz realista de JARVIS:
 * - Entrada: SpeechRecognizer (STT) en español, con resultados parciales en vivo.
 * - Salida: TextToSpeech (TTS) en español con velocidad/tono configurables,
 *   voz española preferente y habla por frases para sonar natural.
 * - Modo wake-word opcional: escucha continua hasta oír «Jarvis».
 */
class VoiceEngine(
    private val context: Context,
    private val prefs: JarvisPrefs,
    private val listener: Listener
) {

    interface Listener {
        fun onListeningStarted()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onSpeakingStarted()
        fun onSpeakingStopped()
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var wakeWord = false
    private var destroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            listening = false
            if (destroyed) return
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No te he oído. Inténtalo de nuevo."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te he oído. Inténtalo de nuevo."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Necesito el permiso de micrófono para escucharte."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "La voz necesita conexión para reconocer tu voz."
                else -> "Error del micrófono ($error). Inténtalo de nuevo."
            }
            listener.onError(msg)
            if (wakeWord && !destroyed) scheduleWakeRestart()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            if (destroyed) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isBlank()) {
                listener.onError("No te he oído. Inténtalo de nuevo.")
                if (wakeWord) scheduleWakeRestart()
                return
            }
            if (wakeWord && !containsWakeWord(text)) {
                scheduleWakeRestart()
                return
            }
            val clean = if (wakeWord) stripWakeWord(text) else text
            if (clean.isNotBlank()) listener.onFinal(clean) else scheduleWakeRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) listener.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val spanish = Locale("es", "ES")
                engine.language = spanish
                // Voz española preferente si el teléfono la tiene instalada.
                try {
                    val voices = engine.voices
                    val spanishVoice = voices.firstOrNull {
                        it.locale.language == "es" && !it.isNetworkConnectionRequired
                    } ?: voices.firstOrNull { it.locale.language == "es" }
                    if (spanishVoice != null) engine.voice = spanishVoice
                } catch (e: Exception) {
                    // Sin voz española instalada: el sistema usa la suya.
                }
                engine.setSpeechRate(prefs.voiceSpeed)
                engine.setPitch(prefs.voicePitch)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        mainHandler.post { listener.onSpeakingStarted() }
                    }

                    override fun onDone(id: String?) {
                        mainHandler.post {
                            listener.onSpeakingStopped()
                            if (wakeWord && !destroyed) scheduleWakeRestart()
                        }
                    }

                    override fun onError(id: String?) {
                        mainHandler.post { listener.onSpeakingStopped() }
                    }

                })
                ttsReady = true
            }
        }
    }

    fun refreshVoiceSettings() {
        tts?.let {
            it.setSpeechRate(prefs.voiceSpeed)
            it.setPitch(prefs.voicePitch)
        }
    }

    fun isTtsReady(): Boolean = ttsReady

    fun speak(text: String, urgent: Boolean = false) {
        val engine = tts ?: return
        val clean = text.trim()
        if (clean.isBlank()) return
        if (!ttsReady) {
            // Reintenta la inicialización y habla en cuanto esté lista.
            initTts()
            mainHandler.postDelayed({ if (ttsReady) speak(clean, urgent) }, 600)
            return
        }
        if (urgent) engine.stop()
        // Hablar por frases cortas suena mucho más natural que un bloque largo.
        val chunks = splitIntoChunks(clean)
        for ((index, chunk) in chunks.withIndex()) {
            val mode = if (index == 0 && urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = UUID.randomUUID().toString()
            engine.speak(chunk, mode, null, id)
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.isEmpty()) return listOf(text)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length > 220) {
                chunks.add(current.toString().trim())
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(sentence)
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())
        return chunks.ifEmpty { listOf(text) }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun startListening() {
        if (listening || destroyed) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Este teléfono no tiene reconocimiento de voz. Escribe tu mensaje.")
            return
        }
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(recognitionListener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            }
            listening = true
            listener.onListeningStarted()
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            listener.onError("No pude activar el micrófono: ${e.message}")
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (e: Exception) {
            // Sin micrófono activo no hay nada que parar.
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        wakeWord = enabled
        if (enabled) {
            scheduleWakeRestart()
        } else {
            stopListening()
        }
    }

    fun isWakeWordEnabled(): Boolean = wakeWord

    private fun scheduleWakeRestart() {
        if (!wakeWord || destroyed || listening) return
        mainHandler.postDelayed({ if (wakeWord && !destroyed && !listening) startListening() }, 700)
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale("es", "ES"))
        return lower.contains("jarvis") || lower.contains("hey jarvis") || lower.contains("hola jarvis")
    }

    private fun stripWakeWord(text: String): String {
        var clean = text.trim()
        clean = clean.replace(Regex("(?i)^(hey|hola|oye)\\s+jarvis[,\\s]*"), "")
        clean = clean.replace(Regex("(?i)^jarvis[,\\s]*"), "")
        return clean.trim()
    }

    fun shutdown() {
        destroyed = true
        wakeWord = false
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Ya destruido.
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // Ya cerrado.
        }
        tts = null
    }
}
