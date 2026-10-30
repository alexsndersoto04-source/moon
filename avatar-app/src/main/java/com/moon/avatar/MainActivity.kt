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
    private lateinit var live: LiveVoice
    private var liveOn = false
    private val avatarCaption = StringBuilder()
    // Activacion por voz: escucha "Hey Luna" (nombre del avatar) en reposo
    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeListening = false
    private var activityResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mouthFallback: Runnable? = null
    private var avatarReady = false

    inner class AvatarBridge {
        @JavascriptInterface
        fun onReady(info: String) {
            mainHandler.post {
                avatarReady = true
                startWakeListening()
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

        live = LiveVoice(this, brain, object : LiveVoice.Listener {
            override fun onConnected() {
                mainHandler.post {
                    liveOn = true
                    status.text = "🟢 EN VIVO · habla, te escucha en tiempo real"
                }
            }
            override fun onDisconnected(reason: String) {
                mainHandler.post {
                    liveOn = false
                    setSpeaking(false)
                    status.text = reason
                    startWakeListening()
                }
            }
            override fun onSpeaking(speaking: Boolean) {
                mainHandler.post { setSpeaking(speaking) }
            }
            override fun onUserText(text: String) {
                mainHandler.post { caption.text = "Tú: $text" }
            }
            override fun onAvatarText(text: String) {
                mainHandler.post {
                    avatarCaption.append(text)
                    caption.text = avatarCaption.toString()
                }
            }
            override fun onInterrupted() {
                mainHandler.post { status.text = "🟢 EN VIVO · lo interrumpiste, sigue hablando" }
            }
        })

        findViewById<TextView>(R.id.settingsButton).setOnClickListener { showSettings() }
        // Sin botones de llamada: el en vivo arranca solo. Tocar la pantalla
        // reintenta si se corto.
        avatarWeb.setOnClickListener { ensureLive() }
        caption.setOnClickListener { ensureLive() }
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

        // 1) Voz HUMANA generada por la IA (con tu clave de Gemini)
        if (brain.voiceMode != "system" && brain.provider == "gemini" && brain.apiKey.isNotBlank()) {
            val voiceName = when (brain.voiceMode) {
                "orus" -> "Orus"
                "enceladus" -> "Enceladus"
                else -> "Charon"
            }
            voice.speak(reply.text, voiceName,
                onStart = { mainHandler.post { setSpeaking(true) } },
                onDone = { ok ->
                    mainHandler.post {
                        setSpeaking(false)
                        if (ok) {
                            status.text = "Te escucho · toca el micrófono"
                        } else {
                            // Respaldo: que al menos lo diga con la voz del sistema
                            speakWithSystemTts(reply)
                        }
                    }
                })
            return
        }
        speakWithSystemTts(reply)
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

    // ---------- Modo EN VIVO automatico (sin botones) ----------
    /** El en vivo se conecta solo al abrir y al volver a la app. */
    private fun ensureLive() {
        if (liveOn || (::live.isInitialized && live.isRunning())) return
        if (brain.apiKey.isBlank() || brain.provider != "gemini") {
            status.text = "Pon tu clave de Gemini en ⚙️ y el en vivo arranca solo"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        tts?.stop()
        if (::voice.isInitialized) voice.stop()
        avatarCaption.clear()
        status.text = "Conectando en vivo…"
        live.start()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::live.isInitialized && !liveOn) startWakeListening()
    }

    override fun onPause() {
        activityResumed = false
        stopWakeListening()
        super.onPause()
    }

    // ---------- Activacion por voz: "Hey Luna" ----------
    private fun wakePhrases(): List<String> {
        val n = brain.avatarName.trim().lowercase()
        return listOf("hey $n", "oye $n", "hola $n", "eh $n", n)
    }

    private fun startWakeListening() {
        if (!activityResumed || liveOn || wakeListening) return
        if (brain.apiKey.isBlank() || brain.provider != "gemini") {
            status.text = "Pon tu clave de Gemini en ⚙️ para activar por voz"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Toca la pantalla para hablar en vivo"
            return
        }
        status.text = "Di «Hey ${brain.avatarName}» para hablar conmigo en vivo"
        if (wakeRecognizer == null) wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        wakeListening = true
        wakeRecognizer?.setRecognitionListener(object : RecognitionListener {
            private fun checkForWake(texts: List<String>?) {
                val heard = texts?.joinToString(" ")?.lowercase() ?: return
                val hit = wakePhrases().any { phrase ->
                    phrase.length > 2 && heard.contains(phrase)
                }
                if (hit) wakeUp()
            }
            override fun onResults(results: Bundle?) {
                checkForWake(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                wakeListening = false
                mainHandler.postDelayed({ if (!liveOn) startWakeListening() }, 350)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                checkForWake(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            }
            override fun onError(error: Int) {
                wakeListening = false
                mainHandler.postDelayed({ if (!liveOn) startWakeListening() }, 900)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            wakeRecognizer?.startListening(intent)
        } catch (e: Exception) {
            wakeListening = false
        }
    }

    private fun stopWakeListening() {
        wakeListening = false
        try { wakeRecognizer?.stopListening() } catch (e: Exception) { /* noop */ }
        try { wakeRecognizer?.cancel() } catch (e: Exception) { /* noop */ }
    }

    /** Dijo la frase: a hablar en vivo directamente con la IA. */
    private fun wakeUp() {
        stopWakeListening()
        if (liveOn) return
        avatarCaption.clear()
        status.text = "¡Te escuché! Conectando en vivo…"
        live.start(greet = true)
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
            startWakeListening()
        } else if (requestCode == 42) {
            Toast.makeText(this, "Sin permiso de micrófono no puede escucharte", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.stop()
        tts?.stop()
        tts?.shutdown()
        if (::live.isInitialized) live.stop()
        avatarWeb.destroy()
        super.onDestroy()
    }
}
