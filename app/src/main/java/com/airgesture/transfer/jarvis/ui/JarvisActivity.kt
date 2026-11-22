package com.airgesture.transfer.jarvis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airgesture.transfer.jarvis.actions.ActionEngine
import com.airgesture.transfer.jarvis.ai.AiClient
import com.airgesture.transfer.jarvis.ai.ChatMessage
import com.airgesture.transfer.jarvis.ai.JarvisBrain
import com.airgesture.transfer.jarvis.data.ApiKeyStore
import com.airgesture.transfer.jarvis.data.JarvisPrefs
import com.airgesture.transfer.jarvis.data.ProjectStore
import com.airgesture.transfer.jarvis.voice.VoiceEngine
import kotlinx.coroutines.launch

/**
 * Pantalla principal de JARVIS: orbe animado, chat, micrófono y acciones rápidas.
 * Todo el UI se construye en código para ser ligero y funcionar en cualquier móvil.
 */
class JarvisActivity : AppCompatActivity() {

    private lateinit var prefs: JarvisPrefs
    private lateinit var keys: ApiKeyStore
    private lateinit var store: ProjectStore
    private lateinit var aiClient: AiClient
    private lateinit var actions: ActionEngine
    private lateinit var brain: JarvisBrain
    private lateinit var voice: VoiceEngine

    private lateinit var orb: OrbView
    private lateinit var statusText: TextView
    private lateinit var providerPill: TextView
    private lateinit var setupBanner: TextView
    private lateinit var chatList: RecyclerView
    private lateinit var input: EditText
    private lateinit var micButton: TextView
    private lateinit var voiceToggle: TextView
    private lateinit var wakeToggle: TextView

    private val history = mutableListOf<ChatMessage>()
    private val messages = mutableListOf<UiMsg>()
    private lateinit var adapter: ChatAdapter
    private var busy = false

    private data class UiMsg(val text: String, val isUser: Boolean, val label: String = "")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Los permisos se piden de nuevo al tocar el micrófono si faltan. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = JarvisPrefs(this)
        keys = ApiKeyStore(this)
        store = ProjectStore(this)
        aiClient = AiClient(keys, prefs)
        actions = ActionEngine(this, store)
        brain = JarvisBrain(this, aiClient, actions, store, prefs)

        voice = VoiceEngine(this, prefs, object : VoiceEngine.Listener {
            override fun onListeningStarted() {
                orb.state = OrbView.State.LISTENING
                statusText.text = "Escuchando… habla ahora"
            }

            override fun onPartial(text: String) {
                input.setText(text)
                input.setSelection(text.length)
            }

            override fun onFinal(text: String) {
                input.setText("")
                sendMessage(text)
            }

            override fun onError(message: String) {
                orb.state = OrbView.State.IDLE
                statusText.text = message
                addJarvisMessage(message, "VOZ")
            }

            override fun onSpeakingStarted() {
                orb.state = OrbView.State.SPEAKING
                statusText.text = "Hablando…"
            }

            override fun onSpeakingStopped() {
                if (!busy) {
                    orb.state = OrbView.State.IDLE
                    statusText.text = "Toca el micrófono o escribe"
                }
            }
        })
        voice.initTts()

        buildUi()
        requestInitialPermissions()
        refreshProviderPill()
        addJarvisMessage(
            greeting(),
            "JARVIS"
        )
    }

    private fun greeting(): String {
        val name = prefs.userName.ifBlank { "" }
        val hello = if (name.isNotBlank()) "Hola, $name. Soy ${prefs.assistantName}." else "Hola. Soy ${prefs.assistantName}."
        return if (keys.hasAnyKey()) {
            "$hello Estoy listo: dime qué necesitas, pídeme que abra una app, ponga una alarma o ayude con tus proyectos."
        } else {
            "$hello Ya puedo hacer cosas sin internet: prueba «abre WhatsApp», «pon una alarma a las 7» o «crea un proyecto llamado Mi app». " +
                "Y si quieres conversar conmigo de verdad, configura tu clave de OpenRouter o Groq en Ajustes."
        }
    }

    // ---------------- UI ----------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 15, 25))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        setContentView(root)

        // Barra superior
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(title)

        providerPill = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(165, 243, 252))
            background = roundedBg(Color.rgb(17, 24, 39), 12)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        topBar.addView(providerPill)
        root.addView(topBar)

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        navRow.addView(navButton("Proyectos") { startActivity(Intent(this@JarvisActivity, ProjectsActivity::class.java)) })
        navRow.addView(navButton("Ajustes / Claves IA") { startActivity(Intent(this@JarvisActivity, SetupActivity::class.java)) })
        navRow.addView(navButton("Gestos") {
            val intent = Intent(this@JarvisActivity, com.airgesture.transfer.ui.MainActivity::class.java)
            startActivity(intent)
        })
        root.addView(navRow)

        setupBanner = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(253, 224, 71))
            background = roundedBg(Color.rgb(30, 27, 75), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@JarvisActivity, SetupActivity::class.java)) }
        }
        root.addView(setupBanner)

        // Orbe + estado
        orb = OrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            }
        }
        root.addView(orb)

        statusText = TextView(this).apply {
            text = "Toca el micrófono o escribe"
            textSize = 14f
            setTextColor(Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(statusText)

        // Acciones rápidas
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = listOf(
            "Qué hora es",
            "Abre WhatsApp",
            "Pon alarma a las 7",
            "Crea proyecto Mi app",
            "Enciende linterna",
            "Busca pizza en Maps",
            "Calcula 120 por 3",
            "Qué proyectos tengo",
            "Recuérdame comprar pan en 10 minutos",
            "Sube el volumen"
        )
        for (chip in chips) {
            val chipView = TextView(this).apply {
                text = chip
                textSize = 12f
                setTextColor(Color.rgb(165, 243, 252))
                background = roundedBg(Color.rgb(17, 24, 39), 16)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(8)
                layoutParams = lp
                setOnClickListener { sendMessage(chip) }
            }
            chipsRow.addView(chipView)
        }
        chipsScroll.addView(chipsRow)
        root.addView(chipsScroll)

        // Chat
        chatList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@JarvisActivity).apply {
                stackFromEnd = true
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        adapter = ChatAdapter(messages)
        chatList.adapter = adapter
        root.addView(chatList)

        // Fila de entrada
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        input = EditText(this).apply {
            hint = "Escribe o toca el micrófono…"
            setHintTextColor(Color.rgb(100, 116, 139))
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            background = roundedBg(Color.rgb(17, 24, 39), 20)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ ->
                val text = input.text.toString()
                input.setText("")
                sendMessage(text)
                true
            }
        }
        inputRow.addView(input)

        val sendButton = TextView(this).apply {
            text = "Enviar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(11, 15, 25))
            gravity = Gravity.CENTER
            background = roundedBg(Color.rgb(34, 211, 238), 20)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener {
                val text = input.text.toString()
                input.setText("")
                sendMessage(text)
            }
        }
        inputRow.addView(sendButton)

        micButton = TextView(this).apply {
            text = "MIC"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBg(Color.rgb(37, 99, 235), 28)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener { onMicClicked() }
        }
        inputRow.addView(micButton)
        root.addView(inputRow)

        // Toggles de voz
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        voiceToggle = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(148, 163, 184))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                prefs.voiceEnabled = !prefs.voiceEnabled
                refreshToggles()
                if (!prefs.voiceEnabled) voice.stopSpeaking()
            }
        }
        wakeToggle = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(148, 163, 184))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                prefs.wakeWordEnabled = !prefs.wakeWordEnabled
                voice.setWakeWordEnabled(prefs.wakeWordEnabled)
                refreshToggles()
                statusText.text = if (prefs.wakeWordEnabled) "Wake-word activado: di «Jarvis» para hablarme" else "Toca el micrófono o escribe"
            }
        }
        toggleRow.addView(voiceToggle)
        toggleRow.addView(wakeToggle)
        root.addView(toggleRow)
        refreshToggles()
    }

    private fun navButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(30, 41, 59), 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun refreshToggles() {
        voiceToggle.text = if (prefs.voiceEnabled) "Voz: ON" else "Voz: OFF"
        wakeToggle.text = if (prefs.wakeWordEnabled) "Wake «Jarvis»: ON" else "Wake «Jarvis»: OFF"
    }

    private fun refreshProviderPill() {
        val order = aiClient.providerOrder()
        providerPill.text = when {
            order.isEmpty() -> "Sin IA · modo local"
            order.size > 1 -> "IA: ${order[0].name} → ${order[1].name}"
            else -> "IA: ${order[0].name}"
        }
        val needsSetup = !prefs.setupDone || !keys.hasAnyKey()
        setupBanner.visibility = if (needsSetup) View.VISIBLE else View.GONE
        if (needsSetup) {
            setupBanner.text = "Toca aquí para configurar tus claves de OpenRouter y Groq (1 minuto). Sin claves funciono en modo local."
        }
    }

    private fun requestInitialPermissions() {
        val toRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun onMicClicked() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            statusText.text = "Concede el micrófono y toca MIC de nuevo"
            return
        }
        voice.stopSpeaking()
        voice.startListening()
    }

    // ---------------- Chat ----------------

    private fun addUserMessage(text: String) {
        messages.add(UiMsg(text, true))
        adapter.notifyItemInserted(messages.size - 1)
        chatList.scrollToPosition(messages.size - 1)
    }

    private fun addJarvisMessage(text: String, label: String = "") {
        messages.add(UiMsg(text, false, label))
        adapter.notifyItemInserted(messages.size - 1)
        chatList.scrollToPosition(messages.size - 1)
    }

    private fun sendMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank() || busy) return
        busy = true
        orb.state = OrbView.State.THINKING
        statusText.text = "Pensando…"
        addUserMessage(text)
        history.add(ChatMessage("user", text))
        if (history.size > 24) {
            while (history.size > 24) history.removeAt(0)
        }
        lifecycleScope.launch {
            try {
                val result = brain.handleUserMessage(text, history)
                history.add(ChatMessage("assistant", result.text))
                if (history.size > 24) {
                    while (history.size > 24) history.removeAt(0)
                }
                addJarvisMessage(result.text, result.providerLabel)
                if (prefs.voiceEnabled) {
                    orb.state = OrbView.State.SPEAKING
                    voice.speak(result.text)
                } else {
                    orb.state = OrbView.State.IDLE
                    statusText.text = "Toca el micrófono o escribe"
                }
            } catch (e: Exception) {
                val msg = "Algo falló: ${e.message ?: "error desconocido"}. Revisa tu conexión o las claves en Ajustes."
                addJarvisMessage(msg, "ERROR")
                if (prefs.voiceEnabled) voice.speak("Algo falló. Revisa tu conexión o las claves en Ajustes.")
                orb.state = OrbView.State.IDLE
                statusText.text = "Toca el micrófono o escribe"
            } finally {
                busy = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProviderPill()
        refreshToggles()
        voice.refreshVoiceSettings()
        if (prefs.wakeWordEnabled) voice.setWakeWordEnabled(true)
    }

    override fun onPause() {
        voice.stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }

    // ---------------- Adaptador de chat ----------------

    private inner class ChatAdapter(private val items: List<UiMsg>) :
        RecyclerView.Adapter<ChatAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }
            val bubble = TextView(parent.context).apply {
                textSize = 14f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                maxWidth = (resources.displayMetrics.widthPixels * 0.75f).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(bubble)
            return Holder(container, bubble)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val msg = items[position]
            val view = holder.bubble
            holder.container.gravity = if (msg.isUser) Gravity.END else Gravity.START
            view.text = if (msg.isUser) msg.text else {
                if (msg.label.isNotBlank()) "[${msg.label}] ${msg.text}" else msg.text
            }
            if (msg.isUser) {
                view.setTextColor(Color.rgb(11, 15, 25))
                view.background = roundedBg(Color.rgb(34, 211, 238), 16)
            } else {
                view.setTextColor(Color.WHITE)
                view.background = roundedBg(Color.rgb(17, 24, 39), 16)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(val container: LinearLayout, val bubble: TextView) : RecyclerView.ViewHolder(container)
    }
}
