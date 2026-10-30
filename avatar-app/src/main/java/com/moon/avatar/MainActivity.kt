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
    private var ttsInitialized = false
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var interactionId = 0
    private var pendingSystemReply: AvatarReply? = null
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
            interactionId++
            pendingSystemReply = null
            tts?.stop()
            if (::voice.isInitialized) voice.stop()
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
        ttsInitialized = true
        if (statusCode != TextToSpeech.SUCCESS) {
            ttsReady = false
            if (pendingSystemReply != null) {
                pendingSystemReply = null
                status.text = "No se pudo iniciar la voz del teléfono"
            }
            return
        }
        val result = tts?.setLanguage(Locale("es", "ES"))
        ttsReady = result != null && result >= TextToSpeech.LANG_AVAILABLE
        // Preferir la voz española de mayor calidad que ya esté disponible.
        try {
            val spanishVoices = tts?.voices?.filter { it.locale.language == "es" }
                ?.sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }) ?: emptyList()
            spanishVoices.firstOrNull()?.let { tts?.voice = it }
        } catch (e: Exception) { /* usar la voz predeterminada */ }
        tts?.setPitch(0.92f)
        tts?.setSpeechRate(1.0f)
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
                mainHandler.post {
                    setSpeaking(false)
                    status.text = "No se pudo reproducir la voz del teléfono"
                }
            }
        })
        pendingSystemReply?.let { reply ->
            pendingSystemReply = null
            if (ttsReady) speakWithSystemTts(reply)
            else status.text = "No hay una voz española instalada en el teléfono"
        }
    }

    private fun say(reply: AvatarReply, thisInteraction: Int) {
        if (thisInteraction != interactionId) return
        caption.text = reply.text
        react(reply.emotion, reply.gesture)
        status.text = "${brain.avatarName} está preparando la voz…"

        // La voz del teléfono es local. La voz Gemini necesita una petición extra
        // a la red, así que se usa una sola generación para toda la respuesta.
        if (brain.lastError.isNotBlank() || brain.voiceMode == "system" ||
            brain.provider != "gemini" || brain.apiKey.isBlank()) {
            speakWithSystemTts(reply)
            return
        }
        val voiceName = when (brain.voiceMode) {
            "orus" -> "Orus"
            "enceladus" -> "Enceladus"
            else -> "Charon"
        }
        voice.speak(reply.text, voiceName,
            onStart = { mainHandler.post {
                if (thisInteraction == interactionId) setSpeaking(true)
            } },
            onDone = { ok ->
                mainHandler.post {
                    if (thisInteraction != interactionId || isFinishing) return@post
                    if (ok) {
                        setSpeaking(false)
                        status.text = "Te escucho · toca el micrófono"
                    } else {
                        setSpeaking(false)
                        status.text = "La voz IA no respondió; usaré la voz del teléfono"
                        speakWithSystemTts(reply)
                    }
                }
            })
    }

    /** Respaldo local: evita otra llamada a la IA si falla Gemini TTS. */
    private fun speakWithSystemTts(reply: AvatarReply) {
        if (!ttsInitialized) {
            pendingSystemReply = reply
            status.text = "Preparando la voz del teléfono…"
            return
        }
        if (!ttsReady) {
            pendingSystemReply = null
            status.text = "No hay una voz española instalada en el teléfono"
            return
        }
        pendingSystemReply = null
        status.text = "Hablando con la voz del teléfono…"
        tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "avatar_speech")
    }

    // ---------- Boton Hablar: te escucha una vez y responde por voz ----------
    private fun startListening() {
        // Una interacción nueva invalida respuestas anteriores que aún estén en red.
        interactionId++
        pendingSystemReply = null
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
        val thisInteraction = ++interactionId
        caption.text = "Tú: $text"
        react(Emotion.THINKING, Gesture.THINK)
        status.text = "${brain.avatarName} está pensando…"
        brain.think(text) { reply ->
            mainHandler.post {
                if (thisInteraction == interactionId && !isFinishing) say(reply, thisInteraction)
            }
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
            listOf("Gemini · Charon (natural, requiere internet)", "Gemini · Orus (firme)",
                "Gemini · Enceladus (suave)", "Sistema del teléfono (rápida, local)"))
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
                val reply = AvatarReply("¡Listo! Así soy ahora. ¿Te gusta?",
                    Emotion.HAPPY, Gesture.NOD)
                say(reply, ++interactionId)
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
        interactionId++
        if (::voice.isInitialized) voice.stop()
        tts?.stop()
        tts?.shutdown()
        try { recognizer?.destroy() } catch (e: Exception) { /* noop */ }
        avatarWeb.destroy()
        super.onDestroy()
    }
}
