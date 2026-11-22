package com.airgesture.transfer.jarvis.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airgesture.transfer.jarvis.ai.AiClient
import com.airgesture.transfer.jarvis.ai.Provider
import com.airgesture.transfer.jarvis.data.ApiKeyStore
import com.airgesture.transfer.jarvis.data.JarvisPrefs
import com.airgesture.transfer.jarvis.voice.VoiceEngine
import kotlinx.coroutines.launch

/**
 * Sesión de configuración de JARVIS: identidad, claves de OpenRouter y Groq
 * (con prueba de conexión), proveedor principal, modelos y voz.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: JarvisPrefs
    private lateinit var keys: ApiKeyStore
    private lateinit var aiClient: AiClient

    private lateinit var userNameInput: EditText
    private lateinit var assistantNameInput: EditText
    private lateinit var openRouterKeyInput: EditText
    private lateinit var groqKeyInput: EditText
    private lateinit var openRouterModelInput: EditText
    private lateinit var groqModelInput: EditText
    private lateinit var openRouterStatus: TextView
    private lateinit var groqStatus: TextView
    private lateinit var providerSpinner: Spinner
    private lateinit var fallbackSwitch: Switch
    private lateinit var voiceSwitch: Switch
    private lateinit var wakeSwitch: Switch
    private lateinit var speedValue: TextView
    private lateinit var pitchValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = JarvisPrefs(this)
        keys = ApiKeyStore(this)
        aiClient = AiClient(keys, prefs)
        buildUi()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(34, 211, 238))
            setPadding(0, dp(18), 0, dp(6))
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(203, 213, 225))
            setPadding(0, dp(8), 0, dp(2))
        }
    }

    private fun hint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(2), 0, dp(4))
        }
    }

    private fun inputField(hintText: String, isPassword: Boolean = false): EditText {
        return EditText(this).apply {
            hint = hintText
            hintTextColor = Color.rgb(100, 116, 139)
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            background = roundedBg(Color.rgb(17, 24, 39), 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun linkButton(text: String, url: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(34, 211, 238))
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    // Sin navegador: el enlace queda visible en el texto.
                }
            }
        }
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(11, 15, 25))
            gravity = Gravity.CENTER
            background = roundedBg(Color.rgb(34, 211, 238), 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBg(Color.rgb(30, 41, 59), 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 15, 25))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Configura JARVIS"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(hint("Sesión de configuración: tus claves se guardan solo en este teléfono y solo se usan para hablar con OpenRouter y Groq. Nunca se comparten."))

        // Identidad
        root.addView(sectionTitle("1 · Tu identidad"))
        root.addView(label("Tu nombre"))
        userNameInput = inputField("Ej.: Alex")
        userNameInput.setText(prefs.userName)
        root.addView(userNameInput)
        root.addView(label("Nombre del asistente"))
        assistantNameInput = inputField("Jarvis")
        assistantNameInput.setText(prefs.assistantName)
        root.addView(assistantNameInput)

        // OpenRouter
        root.addView(sectionTitle("2 · Clave de OpenRouter"))
        root.addView(hint("Crea una clave gratis en openrouter.ai/keys. OpenRouter da acceso a muchos modelos (GPT, Claude, Gemini, Llama…)."))
        root.addView(linkButton("Abrir openrouter.ai/keys", "https://openrouter.ai/keys"))
        root.addView(label("Clave API de OpenRouter"))
        openRouterKeyInput = inputField("sk-or-v1-…", isPassword = true)
        openRouterKeyInput.setText(keys.openRouterKey)
        root.addView(openRouterKeyInput)
        root.addView(label("Modelo de OpenRouter"))
        openRouterModelInput = inputField(JarvisPrefs.DEFAULT_OPENROUTER_MODEL)
        openRouterModelInput.setText(prefs.openRouterModel)
        root.addView(openRouterModelInput)
        root.addView(hint("Sugeridos: " + JarvisPrefs.OPENROUTER_PRESETS.joinToString(" · ")))
        openRouterStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(148, 163, 184))
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(openRouterStatus)
        root.addView(secondaryButton("Probar conexión OpenRouter") { testProvider(Provider.OPENROUTER) })

        // Groq
        root.addView(sectionTitle("3 · Clave de Groq"))
        root.addView(hint("Crea una clave gratis en console.groq.com/keys. Groq es rapidísimo y muy bueno para respuestas en tiempo real."))
        root.addView(linkButton("Abrir console.groq.com/keys", "https://console.groq.com/keys"))
        root.addView(label("Clave API de Groq"))
        groqKeyInput = inputField("gsk_…", isPassword = true)
        groqKeyInput.setText(keys.groqKey)
        root.addView(groqKeyInput)
        root.addView(label("Modelo de Groq"))
        groqModelInput = inputField(JarvisPrefs.DEFAULT_GROQ_MODEL)
        groqModelInput.setText(prefs.groqModel)
        root.addView(groqModelInput)
        root.addView(hint("Sugeridos: " + JarvisPrefs.GROQ_PRESETS.joinToString(" · ")))
        groqStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(148, 163, 184))
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(groqStatus)
        root.addView(secondaryButton("Probar conexión Groq") { testProvider(Provider.GROQ) })

        // Proveedor
        root.addView(sectionTitle("4 · Proveedor de IA"))
        root.addView(label("Proveedor principal"))
        providerSpinner = Spinner(this).apply {
            background = roundedBg(Color.rgb(17, 24, 39), 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val providerOptions = listOf("Automático (OpenRouter → Groq)", "Solo OpenRouter", "Solo Groq")
        providerSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, providerOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        providerSpinner.setSelection(
            when (prefs.primaryProvider) {
                "OPENROUTER" -> 1
                "GROQ" -> 2
                else -> 0
            }
        )
        root.addView(providerSpinner)
        fallbackSwitch = Switch(this).apply {
            text = "Si el principal falla, probar el otro automáticamente"
            setTextColor(Color.rgb(203, 213, 225))
            isChecked = prefs.autoFallback
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(fallbackSwitch)

        // Voz
        root.addView(sectionTitle("5 · Voz"))
        voiceSwitch = Switch(this).apply {
            text = "Hablar las respuestas en voz alta"
            setTextColor(Color.rgb(203, 213, 225))
            isChecked = prefs.voiceEnabled
        }
        root.addView(voiceSwitch)
        wakeSwitch = Switch(this).apply {
            text = "Escucha continua: di «Jarvis» para hablarme"
            setTextColor(Color.rgb(203, 213, 225))
            isChecked = prefs.wakeWordEnabled
        }
        root.addView(wakeSwitch)
        root.addView(label("Velocidad de la voz"))
        speedValue = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
        }
        root.addView(speedValue)
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedRow.addView(slider(prefs.voiceSpeed, 50, 180) { value ->
            prefs.voiceSpeed = value
            speedValue.text = "Velocidad: ${"%.2f".format(value)}x"
        })
        root.addView(speedRow)
        speedValue.text = "Velocidad: ${"%.2f".format(prefs.voiceSpeed)}x"

        root.addView(label("Tono de la voz"))
        pitchValue = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
        }
        root.addView(pitchValue)
        val pitchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pitchRow.addView(slider(prefs.voicePitch, 60, 150) { value ->
            prefs.voicePitch = value
            pitchValue.text = "Tono: ${"%.2f".format(value)}x"
        })
        root.addView(pitchRow)
        pitchValue.text = "Tono: ${"%.2f".format(prefs.voicePitch)}x"

        root.addView(secondaryButton("Probar la voz") { testVoice() })

        root.addView(actionButton("Guardar y empezar") { saveAndFinish() })
        root.addView(secondaryButton("Borrar mis claves de este teléfono") {
            keys.clearAll()
            openRouterKeyInput.setText("")
            groqKeyInput.setText("")
            openRouterStatus.text = "Claves borradas."
            groqStatus.text = "Claves borradas."
        })
        root.addView(hint("Consejo: con una sola clave ya funciona. Con las dos, JARVIS es más rápido y nunca se queda sin IA."))
    }

    private fun slider(current: Float, min: Int, max: Int, onChange: (Float) -> Unit): android.widget.SeekBar {
        val seek = android.widget.SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = (current * 100).toInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })
        }
        return seek
    }

    private fun saveFromInputs() {
        prefs.userName = userNameInput.text.toString()
        prefs.assistantName = assistantNameInput.text.toString()
        keys.openRouterKey = openRouterKeyInput.text.toString()
        keys.groqKey = groqKeyInput.text.toString()
        prefs.openRouterModel = openRouterModelInput.text.toString()
        prefs.groqModel = groqModelInput.text.toString()
        prefs.primaryProvider = when (providerSpinner.selectedItemPosition) {
            1 -> "OPENROUTER"
            2 -> "GROQ"
            else -> "AUTO"
        }
        prefs.autoFallback = fallbackSwitch.isChecked
        prefs.voiceEnabled = voiceSwitch.isChecked
        prefs.wakeWordEnabled = wakeSwitch.isChecked
        prefs.setupDone = true
    }

    private fun saveAndFinish() {
        saveFromInputs()
        finish()
    }

    private fun testProvider(provider: Provider) {
        saveFromInputs()
        val statusView = if (provider == Provider.OPENROUTER) openRouterStatus else groqStatus
        val key = if (provider == Provider.OPENROUTER) keys.openRouterKey else keys.groqKey
        if (key.isBlank()) {
            statusView.text = "Pega primero tu clave de ${provider.name}."
            statusView.setTextColor(Color.rgb(253, 224, 71))
            return
        }
        statusView.text = "Probando ${provider.name}…"
        statusView.setTextColor(Color.rgb(148, 163, 184))
        lifecycleScope.launch {
            try {
                val result = aiClient.testConnection(provider)
                statusView.text = "OK · $result"
                statusView.setTextColor(Color.rgb(74, 222, 128))
            } catch (e: Exception) {
                statusView.text = "Error: ${e.message}"
                statusView.setTextColor(Color.rgb(248, 113, 113))
            }
        }
    }

    private fun testVoice() {
        saveFromInputs()
        val engine = VoiceEngine(this, prefs, object : VoiceEngine.Listener {
            override fun onListeningStarted() = Unit
            override fun onPartial(text: String) = Unit
            override fun onFinal(text: String) = Unit
            override fun onError(message: String) = Unit
            override fun onSpeakingStarted() = Unit
            override fun onSpeakingStopped() = Unit
        })
        engine.initTts()
        engine.speak("Hola, soy ${prefs.assistantName}. Así suena mi voz. Si te gusta, pulsa Guardar y empezar.")
        // El motor se cierra al salir de la pantalla; el TTS en curso puede cortarse,
        // por eso la prueba usa el ciclo de vida de esta Activity.
        engineRef = engine
    }

    private var engineRef: VoiceEngine? = null

    override fun onDestroy() {
        engineRef?.shutdown()
        engineRef = null
        super.onDestroy()
    }
}
