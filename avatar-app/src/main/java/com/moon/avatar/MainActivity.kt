package com.moon.avatar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Pantalla única: el AVATAR 3D es la app.
 *
 * No hay caricatura ni botones de gestos: el avatar es un modelo humano 3D
 * (WebGL/Three.js en avatar3d.html) y SOLO se mueve por tres motivos:
 *   1. Vida idle automática (respiración, balanceo, cabeceo).
 *   2. Al hablar (lip/cabeceo sincronizado con su voz TTS).
 *   3. Reacciones que decide el CEREBRO en cada respuesta
 *      (emoción + gesto del JSON: asentir, negar, celebrar, pensar…).
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var avatarWeb: WebView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private lateinit var nameText: TextView
    private lateinit var brain: Brain

    private lateinit var voice: Voice
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    // Cola de frases: la voz empieza con la 1ª frase mientras genera el resto
    private val sentenceQueue = ArrayDeque<String>()
    private var sentenceSpeaking = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mouthFallback: Runnable? = null
    private var avatarReady = false

    inner class AvatarBridge {
        @JavascriptInterface
        fun onReady(info: String) {
            mainHandler.post {
                avatarReady = true
                status.text = if (brain.apiKey.isBlank())
                    "Avatar 3D listo · cerebro local (pon tu clave en ⚙️)"
                else "Avatar 3D listo · cerebro conectado (${brain.provider})"
            }
        }

        @JavascriptInterface
        fun onError(info: String) {
            mainHandler.post {
                status.text = "Problema cargando el 3D: revisa tu conexión y reabre la app"
            }
        }

        @JavascriptInterface
        fun onClips(info: String) { /* diagnóstico futuro */ }

        @JavascriptInterface
        fun onDebug(info: String) {
            mainHandler.post {
                android.util.Log.i("MoonAvatar3D", info)
                if (!avatarReady) status.text = "Avatar 3D cargado · encuadrando…"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        brain = Brain(this)

        avatarWeb = findViewById(R.id.avatarView)
        caption = findViewById(R.id.captionText)
        status = findViewById(R.id.statusText)
        nameText = findViewById(R.id.avatarNameText)
        nameText.text = brain.avatarName
        caption.text = "¡Hola! Soy ${brain.avatarName}. Háblame con el micrófono 🎤"

        avatarWeb.settings.javaScriptEnabled = true
        avatarWeb.settings.domStorageEnabled = true
        avatarWeb.settings.mediaPlaybackRequiresUserGesture = false
        avatarWeb.webViewClient = WebViewClient()
        avatarWeb.addJavascriptInterface(AvatarBridge(), "AvatarBridge")
        loadAvatar()

        voice = Voice(brain)
        tts = TextToSpeech(this, this)


        findViewById<TextView>(R.id.micButton).setOnClickListener { startListening() }
        findViewById<TextView>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.keyboardButton).setOnClickListener {
            startActivity(Intent(this, TextChatActivity::class.java))
        }
        status.text = "Despertando a tu avatar 3D…"
    }

    private fun loadAvatar() {
        avatarReady = false
        val url = StringBuilder("file:///android_asset/avatar3d.html?inapp=1")
        if (brain.modelUrl.isNotBlank()) {
            url.append("&model=").append(Uri.encode(brain.modelUrl))
        }
        avatarWeb.loadUrl(url.toString())
    }

    /** El cerebro manda: emoción + gesto. Sin intervención manual del usuario. */
    private fun react(emotion: Emotion, gesture: Gesture) {
        if (!avatarReady) return
        val e = emotion.name.lowercase()
        val g = gesture.name.lowercase()
        avatarWeb.evaluateJavascript(
            "window.Avatar3D && window.Avatar3D.react('$e','$g');", null)
    }

    private fun setSpeaking(speaking: Boolean) {
        if (!avatarReady) return
        avatarWeb.evaluateJavascript(
            "window.Avatar3D && window.Avatar3D.setSpeaking($speaking);", null)
    }

    // ---------- Voz del avatar (TTS) ----------
    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("es", "ES"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            // Mejor voz disponible en español (las de red suenan menos robóticas)
            try {
                val spanishVoices = tts?.voices?.filter {
                    it.locale.language == "es"
                }?.sortedByDescending { v -> (if (v.isNetworkConnectionRequired) 2 else 0) + v.quality } ?: emptyList()
                spanishVoices.firstOrNull()?.let { tts?.voice = it }
            } catch (e: Exception) { /* sin selección: voz por defecto */ }
            tts?.setPitch(0.85f)
            tts?.setSpeechRate(0.97f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post { setSpeaking(true) }
                }
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        setSpeaking(false)
                        status.text = "Te escucho · toca el micrófono"
                    }
                }
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { setSpeaking(false) }
                }
            })
        }
    }

    private fun say(reply: AvatarReply) {
        caption.text = reply.text
        react(reply.emotion, reply.gesture)
        status.text = "${brain.avatarName} está hablando…"

        // Sin clave Gemini no hay voz humana: directo al respaldo del sistema
        if (brain.voiceMode == "system" || brain.provider != "gemini" || brain.apiKey.isBlank()) {
            speakWithSystemTts(reply)
            return
        }
        // FRASE A FRASE: la primera frase suena en cuanto su audio esta listo,
        // sin esperar a generar la respuesta hablada entera (menos retraso).
        val voiceName = when (brain.voiceMode) {
            "orus" -> "Orus"
            "enceladus" -> "Enceladus"
            else -> "Charon"
        }
        synchronized(sentenceQueue) {
            sentenceQueue.clear()
            splitSentences(reply.text).forEach { sentenceQueue.addLast(it) }
        }
        speakNextSentence(voiceName, reply)
    }

    private fun splitSentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(text) else parts
    }

    private fun speakNextSentence(voiceName: String, fullReply: AvatarReply) {
        val sentence: String?
        synchronized(sentenceQueue) {
            sentence = sentenceQueue.removeFirstOrNull()
            if (sentence == null) sentenceSpeaking = false
        }
        if (sentence == null) {
            setSpeaking(false)
            status.text = "Te escucho · toca Hablar y dime algo"
            return
        }
        sentenceSpeaking = true
        voice.speak(sentence, voiceName,
            onStart = { mainHandler.post { setSpeaking(true) } },
            onDone = { ok ->
                mainHandler.post {
                    if (ok) {
                        speakNextSentence(voiceName, fullReply)
                    } else {
                        // La voz de la IA fallo a mitad: respaldo del sistema
                        synchronized(sentenceQueue) { sentenceQueue.clear() }
                        sentenceSpeaking = false
                        speakWithSystemTts(fullReply)
                    }
                }
            })
    }

    /** Respaldo: voz del sistema (la robótica), mejorada eligiendo la mejor voz es. */
    private fun speakWithSystemTts(reply: AvatarReply) {
        if (ttsReady) {
            tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "avatar_speech")
        } else {
            setSpeaking(true)
            mouthFallback?.let { mainHandler.removeCallbacks(it) }
            val durationMs = (1200L + reply.text.length * 55L).coerceAtMost(12000L)
            mouthFallback = Runnable {
                setSpeaking(false)
                status.text = "Te escucho · toca el micrófono"
            }
            mainHandler.postDelayed(mouthFallback!!, durationMs)
        }
    }

    // ---------- Boton Hablar: te escucha una vez y responde por voz ----------
    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Este teléfono no tiene reconocimiento de voz: usa el botón Texto", Toast.LENGTH_LONG).show()
            return
        }
        tts?.stop()
        if (::voice.isInitialized) voice.stop()
        synchronized(sentenceQueue) { sentenceQueue.clear() }
        setSpeaking(false)

        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) hear(text)
                else status.text = "No te escuché · toca Hablar e intenta otra vez"
            }
            override fun onError(error: Int) {
                status.text = "No te escuché · toca Hablar e intenta otra vez"
            }
            override fun onReadyForSpeech(params: Bundle?) {
                status.text = "Escuchando… habla ahora 🎤"
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No pude abrir el micrófono", Toast.LENGTH_SHORT).show()
        }
    }

    /** Dijiste algo (botón Hablar): el cerebro piensa y el avatar lo actúa por voz. */
    private fun hear(text: String) {
        caption.text = "Tú: $text"
        react(Emotion.THINKING, Gesture.THINK)
        status.text = "${brain.avatarName} está pensando…"
        brain.think(text) { reply ->
            mainHandler.post { say(reply) }
        }
    }

    // ---------- Ajustes: identidad, cerebro y cuerpo 3D ----------
    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val nameInput = view.findViewById<EditText>(R.id.setAvatarName)
        val userInput = view.findViewById<EditText>(R.id.setUserName)
        val personalityInput = view.findViewById<EditText>(R.id.setPersonality)
        val providerSpinner = view.findViewById<Spinner>(R.id.setProvider)
        val keyInput = view.findViewById<EditText>(R.id.setApiKey)
        val modelInput = view.findViewById<EditText>(R.id.setModelUrl)
        val voiceSpinner = view.findViewById<Spinner>(R.id.setVoice)

        nameInput.setText(brain.avatarName)
        userInput.setText(brain.userName)
        personalityInput.setText(brain.personality)
        keyInput.setText(brain.apiKey)
        modelInput.setText(brain.modelUrl)

        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Gemini (Google, gratis)", "Groq (muy rápido, gratis)"))
        providerSpinner.setSelection(if (brain.provider == "groq") 1 else 0)

        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Humana IA · Charon (masculina, recomendada)", "Humana IA · Orus (masculina firme)",
                "Humana IA · Enceladus (masculina suave)", "Sistema del teléfono (robótica)"))
        voiceSpinner.setSelection(when (brain.voiceMode) {
            "orus" -> 1; "enceladus" -> 2; "system" -> 3; else -> 0 })

        AlertDialog.Builder(this)
            .setTitle("⚙️ Tu avatar, a tu manera")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val modelChanged = modelInput.text.toString().trim() != brain.modelUrl
                brain.avatarName = nameInput.text.toString().ifBlank { "Luna" }
                brain.userName = userInput.text.toString()
                brain.personality = personalityInput.text.toString()
                brain.provider = if (providerSpinner.selectedItemPosition == 1) "groq" else "gemini"
                brain.apiKey = keyInput.text.toString()
                brain.modelUrl = modelInput.text.toString()
                brain.voiceMode = when (voiceSpinner.selectedItemPosition) {
                    1 -> "orus"; 2 -> "enceladus"; 3 -> "system"; else -> "charon" }

                nameText.text = brain.avatarName
                if (modelChanged) loadAvatar()
                status.text = if (brain.apiKey.isBlank())
                    "Cerebro local · añade tu clave para la IA completa"
                else "Cerebro conectado (${brain.provider}) · háblale 🎤"
                say(AvatarReply("¡Listo! Así soy ahora. ¿Te gusta?",
                    Emotion.HAPPY, Gesture.NOD))
            }
            .setNeutralButton("Borrar memoria") { _, _ ->
                brain.forgetAll()
                Toast.makeText(this, "Memoria borrada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == 42) {
            Toast.makeText(this, "Sin permiso de micrófono no puede escucharte", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.stop()
        tts?.stop()
        tts?.shutdown()
        try { recognizer?.destroy() } catch (e: Exception) { /* noop */ }
        avatarWeb.destroy()
        super.onDestroy()
    }
}
