package com.airgesture.transfer.jarvis.ai

import android.content.Context
import android.os.Build
import com.airgesture.transfer.jarvis.actions.ActionEngine
import com.airgesture.transfer.jarvis.data.JarvisPrefs
import com.airgesture.transfer.jarvis.data.ProjectStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BrainResult(
    val text: String,
    val actionsExecuted: List<String> = emptyList(),
    val providerLabel: String = ""
)

/**
 * Cerebro de JARVIS: decide entre comando local (rápido, sin internet),
 * IA con acciones en el móvil (OpenRouter/Groq) o respuesta de la IA.
 */
class JarvisBrain(
    private val context: Context,
    private val aiClient: AiClient,
    private val actions: ActionEngine,
    private val store: ProjectStore,
    private val prefs: JarvisPrefs
) {

    suspend fun handleUserMessage(
        userText: String,
        history: MutableList<ChatMessage>
    ): BrainResult {
        val text = userText.trim()
        if (text.isBlank()) return BrainResult("Dime qué necesitas.")

        // 1) Comando local: inmediato y funciona sin claves ni internet.
        val local = actions.tryLocalIntent(text)
        if (local != null) {
            // Si hay IA, le pedimos que lo confirme con naturalidad; si no, hablamos el resultado local.
            if (aiClient.hasAnyKey()) {
                try {
                    val confirm = askAi(
                        history,
                        "El usuario dijo: «$text». Ya ejecuté esta acción en su móvil con este resultado: ${local.message} " +
                            "Responde en español, en una frase corta y natural confirmando lo hecho. No pidas otra acción."
                    )
                    return BrainResult(confirm.text, listOf(local.message), confirm.provider.name)
                } catch (e: Exception) {
                    return BrainResult(local.speak, listOf(local.message), "LOCAL")
                }
            }
            return BrainResult(local.speak, listOf(local.message), "LOCAL")
        }

        // 2) Sin claves: ayuda offline honesta.
        if (!aiClient.hasAnyKey()) {
            return BrainResult(
                "Puedo ayudarte ya con comandos locales: abre WhatsApp, pon una alarma a las 7, " +
                    "recuérdame comprar pan en 10 minutos, crea un proyecto llamado Mi app, " +
                    "enciende la linterna, busca pizza en Maps o calcula 120 por 3. " +
                    "Para conversar conmigo de verdad, configura tu clave de OpenRouter o de Groq en Ajustes: " +
                    "tarda un minuto y es gratis empezar.",
                emptyList(),
                "OFFLINE"
            )
        }

        // 3) IA con capacidad de actuar en el móvil.
        val systemPrompt = buildSystemPrompt()
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))
        messages.addAll(history.takeLast(12))
        messages.add(ChatMessage("user", text))

        val first = aiClient.chat(messages)
        val executed = actions.executeJson(first.text)

        if (executed.isEmpty()) {
            // Respuesta normal de la IA (sin acciones).
            return BrainResult(cleanForSpeech(first.text), emptyList(), first.provider.name)
        }

        // 4) La IA pidió acciones: se ejecutaron y ahora le pedimos la frase final.
        val actionSummary = executed.joinToString("; ") { it.message }
        val followUp = mutableListOf<ChatMessage>()
        followUp.add(ChatMessage("system", systemPrompt))
        followUp.add(ChatMessage("user", text))
        followUp.add(ChatMessage("assistant", first.text))
        followUp.add(
            ChatMessage(
                "user",
                "Ya ejecuté estas acciones en el móvil: $actionSummary. " +
                    "Responde en español, en 1-2 frases cortas y naturales, confirmando lo hecho. " +
                    "No repitas el JSON ni pidas más acciones."
            )
        )
        return try {
            val final = aiClient.chat(followUp)
            BrainResult(cleanForSpeech(final.text), executed.map { it.message }, final.provider.name)
        } catch (e: Exception) {
            val spoken = executed.joinToString(" ") { it.speak }
            BrainResult(spoken, executed.map { it.message }, first.provider.name)
        }
    }

    private suspend fun askAi(history: List<ChatMessage>, instruction: String): AiResult {
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", buildSystemPrompt()))
        messages.addAll(history.takeLast(6))
        messages.add(ChatMessage("user", instruction))
        return aiClient.chat(messages)
    }

    private fun buildSystemPrompt(): String {
        val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "ES")).format(Date())
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        val userName = prefs.userName.ifBlank { "jefe" }
        val projects = store.summaryForAi()
        return """
Eres ${prefs.assistantName}, un asistente personal de IA que vive en el móvil de $userName y puede actuar en él.
Hablas español, con tono cercano, claro y breve (1-3 frases salvo que pidan detalle). Sin rodeos, sin listas eternas.
Fecha y hora actuales: $now. Dispositivo: $device.

$projects

Puedes ejecutar acciones en el móvil. Cuando el usuario pida algo que sea una acción, responde SOLO con este JSON (sin texto fuera):
{"actions":[{"action":"open_app","app":"whatsapp"}],"response":"Abriendo WhatsApp"}
Acciones disponibles (con sus parámetros):
- open_app {app}: whatsapp, youtube, spotify, instagram, tiktok, gmail, maps, chrome, telegram, facebook, cámara, reloj, galería, archivos, ajustes, calculadora, notas, drive, contactos, teléfono, mensajes, netflix
- set_alarm {hour:0-23, minute:0-59, label}
- set_timer {minutes, label} o {seconds, label}
- create_reminder {title, in_minutes, note} o {title, at_time:"HH:mm", note}
- create_project {name, description}
- create_task {project, title} · complete_task {title} · list_projects {} · list_tasks {project}
- add_note {title, body}
- set_volume {level:0-100, stream:"music|alarm|ring"}
- set_brightness {level:0-100}
- toggle_wifi {on:true|false} · toggle_bluetooth {on} · toggle_flashlight {on} · toggle_dnd {on}
- vibrate {ms}
- call {number} · sms {number, message} · whatsapp {number, message}
- open_url {url} · search_web {query} · search_maps {query} · search_youtube {query} · play_music {query}
- take_photo {} · record_video {}
- calculator {expression} · get_time {} · get_date {} · get_battery {} · get_device_info {}
- copy_text {text} · share_text {text} · open_settings {section:"wifi|bluetooth|display|sound|battery"}
- create_calendar_event {title, in_minutes, note} · speak {text}

Reglas:
- Si no es una acción (pregunta, consejo, idea, ayuda con un proyecto), responde en texto normal, sin JSON.
- Nunca inventes que hiciste algo que no está en las acciones ejecutadas.
- Si falta un dato (número, hora, nombre), pregunta una sola cosa concreta.
- Ayuda con proyectos: sugiere pasos, tareas y prioridades cuando el usuario hable de sus proyectos.
        """.trimIndent()
    }

    private fun cleanForSpeech(text: String): String {
        var clean = text.trim()
        // Quita bloques de código residuales por si la IA los dejó.
        clean = clean.replace(Regex("```[\\s\\S]*?```"), " ").trim()
        clean = clean.replace(Regex("\\s+"), " ")
        return clean.ifBlank { "De acuerdo." }
    }
}
