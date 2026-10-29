package com.moon.avatar

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView

/**
 * Ventana de TEXTO, independiente del modo en vivo.
 * Aquí sí se escribe: el cerebro responde por escrito y con su voz humana,
 * sin mezclar esta pantalla con la conversación hablada en directo.
 */
class TextChatActivity : Activity() {

    private lateinit var brain: Brain
    private lateinit var voice: Voice
    private lateinit var replyText: TextView
    private lateinit var historyText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text)
        brain = Brain(this)
        voice = Voice(brain)

        replyText = findViewById(R.id.chatReply)
        historyText = findViewById(R.id.chatHistory)
        findViewById<TextView>(R.id.chatTitle).text = "💬 Texto con ${brain.avatarName}"
        replyText.text = "Escríbele algo a ${brain.avatarName} y te responde aquí, con su voz."

        val input = findViewById<EditText>(R.id.chatInput)
        findViewById<TextView>(R.id.chatSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.text.clear()
            historyText.text = "Tú: $text"
            replyText.text = "${brain.avatarName} está pensando…"
            brain.think(text) { reply ->
                mainHandler.post {
                    replyText.text = reply.text
                    historyText.text = "Tú: $text"
                    speak(reply)
                }
            }
        }
        findViewById<TextView>(R.id.chatBack).setOnClickListener { finish() }
    }

    private fun speak(reply: AvatarReply) {
        if (brain.voiceMode != "system" && brain.provider == "gemini" && brain.apiKey.isNotBlank()) {
            val voiceName = when (brain.voiceMode) {
                "orus" -> "Orus"
                "enceladus" -> "Enceladus"
                else -> "Charon"
            }
            voice.speak(reply.text, voiceName, {}, {})
        }
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.stop()
        super.onDestroy()
    }
}
