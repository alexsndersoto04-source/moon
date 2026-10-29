package com.moon.avatar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Pantalla única: el AVATAR es la app.
 * No hay lista de mensajes ni burbujas de chat: le hablas, y él te responde
 * hablando, con gestos y expresiones. El texto solo aparece como subtítulo
 * de lo que el avatar está diciendo.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var avatarView: AvatarView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private lateinit var nameText: TextView
    private lateinit var brain: Brain

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mouthFallback: Runnable? = null

    // Opciones de apariencia: nombre visible -> valor
    private val skinOptions = listOf(
        "Clara" to Color.rgb(240, 199, 165),
        "Media" to Color.rgb(214, 158, 110),
        "Trigueña" to Color.rgb(198, 134, 88),
        "Morena" to Color.rgb(150, 92, 56),
        "Oscura" to Color.rgb(105, 62, 38)
    )
    private val hairColorOptions = listOf(
        "Negro" to Color.rgb(28, 22, 20),
        "Castaño" to Color.rgb(92, 56, 32),
        "Rubio" to Color.rgb(214, 176, 92),
        "Rojo" to Color.rgb(178, 74, 44),
        "Violeta" to Color.rgb(124, 78, 200),
        "Azul" to Color.rgb(58, 110, 200)
    )
    private val hairStyleOptions = listOf("Corto", "Largo", "Moño", "Rizado")
    private val shirtOptions = listOf(
        "Violeta" to Color.rgb(99, 82, 220),
        "Azul" to Color.rgb(52, 120, 220),
        "Verde" to Color.rgb(46, 160, 110),
        "Rosa" to Color.rgb(226, 110, 160),
        "Naranja" to Color.rgb(235, 140, 60),
        "Negro" to Color.rgb(42, 42, 52)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        brain = Brain(this)

        avatarView = findViewById(R.id.avatarView)
        caption = findViewById(R.id.captionText)
        status = findViewById(R.id.statusText)
        nameText = findViewById(R.id.avatarNameText)

        applyAppearance()
        nameText.text = brain.avatarName
        avatarView.emotion = Emotion.HAPPY
        avatarView.playGesture(Gesture.WAVE)

        tts = TextToSpeech(this, this)

        findViewById<TextView>(R.id.micButton).setOnClickListener { startListening() }
        findViewById<TextView>(R.id.gestureButton).setOnClickListener { showGestures() }
        findViewById<TextView>(R.id.settingsButton).setOnClickListener { showSettings() }

        val textRow = findViewById<View>(R.id.textInputRow)
        findViewById<TextView>(R.id.keyboardButton).setOnClickListener {
            textRow.visibility = if (textRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val input = findViewById<EditText>(R.id.textInput)
        findViewById<TextView>(R.id.sendTextButton).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                hear(text)
            }
        }

        if (brain.apiKey.isBlank()) {
            status.text = "Cerebro local · añade tu clave en ⚙️ para la IA completa"
        } else {
            status.text = "Cerebro conectado (${brain.provider}) · háblale 🎤"
        }
    }

    // ---------- Voz del avatar (TTS + lip-sync) ----------
    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            val spanish = Locale("es", "ES")
            val result = tts?.setLanguage(spanish)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setPitch(1.05f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post { avatarView.speaking = true }
                }
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        avatarView.speaking = false
                        status.text = "Te escucho · toca el micrófono"
                    }
                }
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { avatarView.speaking = false }
                }
            })
        }
    }

    private fun say(reply: AvatarReply) {
        caption.text = reply.text
        avatarView.emotion = reply.emotion
        if (reply.gesture != Gesture.NONE) avatarView.playGesture(reply.gesture)
        status.text = "${brain.avatarName} está hablando…"

        if (ttsReady) {
            tts?.speak(reply.text, TextToSpeech.QUEUE_FLUSH, null, "avatar_speech")
        } else {
            // Sin voz instalada, al menos mueve la boca el tiempo estimado.
            avatarView.speaking = true
            mouthFallback?.let { mainHandler.removeCallbacks(it) }
            val durationMs = (1200L + reply.text.length * 55L).coerceAtMost(12000L)
            mouthFallback = Runnable {
                avatarView.speaking = false
                status.text = "Te escucho · toca el micrófono"
            }
            mainHandler.postDelayed(mouthFallback!!, durationMs)
        }
    }

    // ---------- Oídos del avatar (STT) ----------
    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Este teléfono no tiene reconocimiento de voz", Toast.LENGTH_LONG).show()
            return
        }
        tts?.stop()
        avatarView.speaking = false

        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                status.text = "Pensando…"
                if (text.isNotBlank()) hear(text) else status.text = "No te escuché, intenta otra vez"
            }
            override fun onError(error: Int) {
                status.text = "No te escuché · toca el micrófono e intenta otra vez"
                avatarView.emotion = Emotion.NEUTRAL
            }
            override fun onReadyForSpeech(params: Bundle?) {
                status.text = "Escuchando… habla ahora 🎤"
                avatarView.emotion = Emotion.NEUTRAL
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No pude abrir el micrófono", Toast.LENGTH_SHORT).show()
        }
    }

    /** El usuario dijo algo (por voz o texto): el cerebro piensa y el avatar actúa. */
    private fun hear(text: String) {
        caption.text = "Tú: $text"
        avatarView.emotion = Emotion.THINKING
        status.text = "${brain.avatarName} está pensando…"
        brain.think(text) { reply ->
            mainHandler.post { say(reply) }
        }
    }

    // ---------- Gestos a petición ----------
    private fun showGestures() {
        val names = arrayOf("👋 Saludar", "😄 Celebrar", "🙏 Reverencia", "🤔 Pensar", "👍 Asentir", "🙅 Negar")
        val gestures = arrayOf(Gesture.WAVE, Gesture.CELEBRATE, Gesture.BOW, Gesture.THINK, Gesture.NOD, Gesture.SHAKE)
        val emotions = arrayOf(Emotion.HAPPY, Emotion.EXCITED, Emotion.HAPPY, Emotion.THINKING, Emotion.HAPPY, Emotion.NEUTRAL)
        AlertDialog.Builder(this)
            .setTitle("Pídele un gesto")
            .setItems(names) { _, which ->
                avatarView.emotion = emotions[which]
                avatarView.playGesture(gestures[which])
            }
            .show()
    }

    // ---------- Ajustes: identidad, cerebro y apariencia ----------
    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val nameInput = view.findViewById<EditText>(R.id.setAvatarName)
        val userInput = view.findViewById<EditText>(R.id.setUserName)
        val personalityInput = view.findViewById<EditText>(R.id.setPersonality)
        val providerSpinner = view.findViewById<Spinner>(R.id.setProvider)
        val keyInput = view.findViewById<EditText>(R.id.setApiKey)
        val hairStyleSpinner = view.findViewById<Spinner>(R.id.setHairStyle)
        val hairColorSpinner = view.findViewById<Spinner>(R.id.setHairColor)
        val skinSpinner = view.findViewById<Spinner>(R.id.setSkin)
        val shirtSpinner = view.findViewById<Spinner>(R.id.setShirt)

        nameInput.setText(brain.avatarName)
        userInput.setText(brain.userName)
        personalityInput.setText(brain.personality)
        keyInput.setText(brain.apiKey)

        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Gemini (Google, gratis)", "Groq (muy rápido, gratis)"))
        providerSpinner.setSelection(if (brain.provider == "groq") 1 else 0)

        hairStyleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hairStyleOptions)
        hairStyleSpinner.setSelection(avatarView.hairStyle)
        hairColorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hairColorOptions.map { it.first })
        hairColorSpinner.setSelection(hairColorOptions.indexOfFirst { it.second == avatarView.hairColor }.coerceAtLeast(0))
        skinSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, skinOptions.map { it.first })
        skinSpinner.setSelection(skinOptions.indexOfFirst { it.second == avatarView.skinColor }.coerceAtLeast(0))
        shirtSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shirtOptions.map { it.first })
        shirtSpinner.setSelection(shirtOptions.indexOfFirst { it.second == avatarView.shirtColor }.coerceAtLeast(0))

        AlertDialog.Builder(this)
            .setTitle("⚙️ Tu avatar, a tu manera")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                brain.avatarName = nameInput.text.toString().ifBlank { "Luna" }
                brain.userName = userInput.text.toString()
                brain.personality = personalityInput.text.toString()
                brain.provider = if (providerSpinner.selectedItemPosition == 1) "groq" else "gemini"
                brain.apiKey = keyInput.text.toString()

                avatarView.hairStyle = hairStyleSpinner.selectedItemPosition
                avatarView.hairColor = hairColorOptions[hairColorSpinner.selectedItemPosition].second
                avatarView.skinColor = skinOptions[skinSpinner.selectedItemPosition].second
                avatarView.shirtColor = shirtOptions[shirtSpinner.selectedItemPosition].second
                brain.saveAppearance(avatarView.skinColor, avatarView.hairColor,
                    avatarView.hairStyle, avatarView.shirtColor)

                nameText.text = brain.avatarName
                avatarView.invalidate()
                status.text = if (brain.apiKey.isBlank())
                    "Cerebro local · añade tu clave para la IA completa"
                else "Cerebro conectado (${brain.provider}) · háblale 🎤"
                say(AvatarReply("¡Listo! Así me veo ahora. ¿Te gusta?",
                    Emotion.HAPPY, Gesture.WAVE))
            }
            .setNeutralButton("Borrar memoria") { _, _ ->
                brain.forgetAll()
                Toast.makeText(this, "Memoria borrada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyAppearance() {
        val saved = brain.appearance()
        if (saved[0] != -1) avatarView.skinColor = saved[0]
        if (saved[1] != -1) avatarView.hairColor = saved[1]
        if (saved[2] != -1) avatarView.hairStyle = saved[2]
        if (saved[3] != -1) avatarView.shirtColor = saved[3]
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
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}
