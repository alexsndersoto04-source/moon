package com.moon.avatar

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.EditText
import android.widget.TextView
import java.util.Locale

/**
 * Ventana de texto, independiente de la conversación por micrófono.
 * Las respuestas también se pueden escuchar; si Gemini TTS falla, usa la voz
 * local del teléfono en vez de dejar la pantalla en silencio.
 */
class TextChatActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var brain: Brain
    private lateinit var voice: Voice
    private lateinit var replyText: TextView
    private lateinit var historyText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitialized = false
    private var pendingSystemSpeech: String? = null
    private var turnId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text)
        brain = Brain(this)
        voice = Voice(brain)
        tts = TextToSpeech(this, this)

        replyText = findViewById(R.id.chatReply)
        historyText = findViewById(R.id.chatHistory)
        findViewById<TextView>(R.id.chatTitle).text = "💬 Texto con ${brain.avatarName}"
        replyText.text = "Escríbele algo a ${brain.avatarName} y te responde aquí."

        val input = findViewById<EditText>(R.id.chatInput)
        findViewById<TextView>(R.id.chatSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.text.clear()
            val thisTurn = ++turnId
            pendingSystemSpeech = null
            voice.stop()
            tts?.stop()
            historyText.text = "Tú: $text"
            replyText.text = "${brain.avatarName} está pensando…"
            brain.think(text) { reply ->
                mainHandler.post {
                    if (thisTurn != turnId || isFinishing) return@post
                    replyText.text = reply.text
                    historyText.text = "Tú: $text"
                    speak(reply, thisTurn)
                }
            }
        }
        findViewById<TextView>(R.id.chatBack).setOnClickListener { finish() }
    }

    override fun onInit(statusCode: Int) {
        ttsInitialized = true
        if (statusCode != TextToSpeech.SUCCESS) {
            ttsReady = false
            pendingSystemSpeech = null
            return
        }
        val result = tts?.setLanguage(Locale("es", "ES"))
        ttsReady = result != null && result >= TextToSpeech.LANG_AVAILABLE
        try {
            val spanishVoice = tts?.voices?.filter { it.locale.language == "es" }
                ?.sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality })?.firstOrNull()
            if (spanishVoice != null) tts?.voice = spanishVoice
        } catch (e: Exception) { /* usar la voz predeterminada */ }
        tts?.setPitch(0.92f)
        tts?.setSpeechRate(1.0f)
        pendingSystemSpeech?.let { text ->
            pendingSystemSpeech = null
            if (ttsReady) speakSystemText(text)
            else if (::replyText.isInitialized) {
                replyText.append("\n\nNo hay una voz española instalada en el teléfono.")
            }
        }
    }

    private fun speak(reply: AvatarReply, thisTurn: Int) {
        if (brain.lastError.isNotBlank() || brain.voiceMode == "system" ||
            brain.provider != "gemini" || brain.apiKey.isBlank()) {
            speakWithSystemTts(reply.text)
            return
        }
        val voiceName = when (brain.voiceMode) {
            "orus" -> "Orus"
            "enceladus" -> "Enceladus"
            else -> "Charon"
        }
        voice.speak(reply.text, voiceName, {}, onDone = { ok ->
            if (!ok) mainHandler.post {
                if (thisTurn == turnId && !isFinishing) speakWithSystemTts(reply.text)
            }
        })
    }

    private fun speakWithSystemTts(text: String) {
        if (!ttsInitialized) {
            pendingSystemSpeech = text
        } else if (!ttsReady) {
            pendingSystemSpeech = null
            replyText.append("\n\nNo hay una voz española instalada en el teléfono.")
        } else {
            speakSystemText(text)
        }
    }

    private fun speakSystemText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "avatar_text_reply")
    }

    override fun onDestroy() {
        turnId++
        if (::voice.isInitialized) voice.stop()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
