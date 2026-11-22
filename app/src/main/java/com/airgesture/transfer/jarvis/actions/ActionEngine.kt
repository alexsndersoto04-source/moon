package com.airgesture.transfer.jarvis.actions

import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.airgesture.transfer.jarvis.data.ProjectStore
import com.airgesture.transfer.jarvis.util.JarvisNotifications
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ActionResult(
    val success: Boolean,
    val message: String,
    val speak: String
)

/**
 * Motor de acciones de JARVIS: ejecuta en el móvil lo que la IA (o un comando
 * local en español) pide. Todo funciona sin servidor; si el sistema bloquea
 * una acción (p. ej. cambiar el Wi-Fi en Android 10+), se abre el ajuste
 * correspondiente en vez de fallar en silencio.
 */
class ActionEngine(
    private val context: Context,
    private val store: ProjectStore
) {

    // ---------------- Entrada desde la IA (JSON) ----------------

    fun executeJson(raw: String): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        val cleaned = extractJson(raw) ?: return results
        val root = try {
            JsonParser.parseString(cleaned).asJsonObject
        } catch (e: Exception) {
            return results
        }
        val actions = mutableListOf<JsonObject>()
        if (root.has("actions") && root.get("actions").isJsonArray) {
            for (el in root.getAsJsonArray("actions")) {
                if (el.isJsonObject) actions.add(el.asJsonObject)
            }
        } else if (root.has("action")) {
            actions.add(root)
        }
        for (a in actions) {
            val name = a.str("action").lowercase().trim()
            if (name.isBlank()) continue
            results.add(executeSingle(name, a))
        }
        return results
    }

    fun extractJson(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // Bloque ```json ... ```
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.find(text)?.let { return it.groupValues[1].trim() }
        // Primer objeto JSON completo
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        return null
    }

    fun executeSingle(action: String, p: JsonObject): ActionResult {
        return try {
            when (action) {
                "open_app" -> openApp(p.str("app").ifBlank { p.str("name") })
                "set_alarm" -> setAlarm(p.int("hour", -1), p.int("minute", 0), p.str("label").ifBlank { p.str("message") })
                "set_timer" -> setTimer(p.long("seconds", p.long("minutes", 0) * 60), p.str("label").ifBlank { p.str("message") })
                "create_reminder" -> createReminder(
                    p.str("title").ifBlank { p.str("text") },
                    p.long("in_minutes", p.long("in_seconds", 0) / 60),
                    p.str("at_time"),
                    p.str("note")
                )
                "create_project" -> createProject(p.str("name").ifBlank { p.str("title") }, p.str("description"))
                "create_task", "add_task" -> createTask(
                    p.str("project").ifBlank { p.str("project_name") },
                    p.str("title").ifBlank { p.str("task") }
                )
                "complete_task" -> completeTask(p.str("title").ifBlank { p.str("task") })
                "list_projects" -> listProjects()
                "list_tasks" -> listTasks(p.str("project"))
                "add_note" -> addNote(p.str("title"), p.str("body").ifBlank { p.str("text") })
                "set_volume" -> setVolume(p.int("level", -1), p.str("stream"))
                "set_brightness" -> setBrightness(p.int("level", -1))
                "toggle_wifi" -> toggleWifi(p.bool("on", true))
                "toggle_bluetooth" -> toggleBluetooth(p.bool("on", true))
                "toggle_flashlight" -> toggleFlashlight(p.bool("on", true))
                "toggle_dnd" -> toggleDnd(p.bool("on", true))
                "vibrate" -> vibrate(p.long("ms", 300))
                "call" -> call(p.str("number").ifBlank { p.str("contact") })
                "sms" -> sms(p.str("number"), p.str("message").ifBlank { p.str("text") })
                "whatsapp" -> whatsapp(p.str("number"), p.str("message").ifBlank { p.str("text") })
                "open_url" -> openUrl(p.str("url"))
                "search_web" -> searchWeb(p.str("query").ifBlank { p.str("q") })
                "search_maps" -> searchMaps(p.str("query").ifBlank { p.str("place") })
                "search_youtube" -> searchYoutube(p.str("query").ifBlank { p.str("q") })
                "play_music" -> playMusic(p.str("query"))
                "take_photo" -> takePhoto()
                "record_video" -> recordVideo()
                "calculator", "calculate" -> calculate(p.str("expression").ifBlank { p.str("text") })
                "get_time" -> getTime()
                "get_date" -> getDate()
                "get_battery" -> getBattery()
                "get_device_info" -> getDeviceInfo()
                "copy_text" -> copyText(p.str("text"))
                "share_text" -> shareText(p.str("text").ifBlank { p.str("message") })
                "open_settings" -> openSettings(p.str("section"))
                "create_calendar_event" -> createCalendarEvent(
                    p.str("title"), p.long("in_minutes", 60), p.str("note")
                )
                "speak" -> ActionResult(true, "Dicho.", p.str("text").ifBlank { "De acuerdo." })
                else -> ActionResult(false, "Acción no reconocida: $action", "No conozco esa acción.")
            }
        } catch (e: Exception) {
            ActionResult(false, "Error en $action: ${e.message}", "No pude completar esa acción.")
        }
    }

    // ---------------- Comandos locales en español (sin IA) ----------------

    fun tryLocalIntent(rawText: String): ActionResult? {
        val text = rawText.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase(Locale("es", "ES"))

        // Hora / fecha / batería (siempre locales)
        if (lower.contains("qué hora") || lower.contains("que hora") || lower == "hora") return getTime()
        if (lower.contains("qué fecha") || lower.contains("que fecha") || lower.contains("qué día es")) return getDate()
        if (lower.contains("batería") || lower.contains("bateria")) return getBattery()
        if (lower.contains("qué dispositivo") || lower.contains("que dispositivo") || lower.contains("información del dispositivo")) return getDeviceInfo()

        // Calculadora
        Regex("(?:calcula|cuánto es|cuanto es|cuánto vale)\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return calculate(it.groupValues[1])
        }

        // Alarmas y temporizador
        Regex("pon(?:me)? una alarma(?: para las)?\\s+(\\d{1,2})(?::(\\d{2}))?", RegexOption.IGNORE_CASE).find(text)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: -1
            val m = it.groupValues[2].toIntOrNull() ?: 0
            return setAlarm(h, m, "Alarma JARVIS")
        }
        Regex("alarma(?: para las)?\\s+(\\d{1,2})(?::(\\d{2}))?", RegexOption.IGNORE_CASE).find(text)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: -1
            val m = it.groupValues[2].toIntOrNull() ?: 0
            return setAlarm(h, m, "Alarma JARVIS")
        }
        Regex("(?:temporizador|cuenta atrás|cuenta atras)(?: de)?\\s+(\\d+)\\s*(minutos?|min|segundos?|s|horas?|h)?", RegexOption.IGNORE_CASE).find(text)?.let {
            val n = it.groupValues[1].toLongOrNull() ?: 0L
            val unit = it.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("s") -> n
                else -> n * 60
            }
            return setTimer(seconds, "Temporizador JARVIS")
        }

        // Recordatorios
        Regex("recuérdame\\s+(.+?)\\s+en\\s+(\\d+)\\s*(minutos?|min|horas?|h|segundos?|s)", RegexOption.IGNORE_CASE).find(text)?.let {
            val title = it.groupValues[1].trim()
            val n = it.groupValues[2].toLongOrNull() ?: 0L
            val unit = it.groupValues[3].lowercase()
            val minutes = when {
                unit.startsWith("h") -> n * 60
                unit.startsWith("s") -> maxOf(1L, n / 60)
                else -> n
            }
            return createReminder(title, minutes, "", "")
        }
        Regex("recuérdame\\s+(.+?)\\s+a las\\s+(\\d{1,2})(?::(\\d{2}))?", RegexOption.IGNORE_CASE).find(text)?.let {
            return createReminder(it.groupValues[1].trim(), 0, it.groupValues[2] + ":" + it.groupValues[3].ifBlank { "00" }, "")
        }
        Regex("(?:pon|crea)(?:me)? un recordatorio(?: de)?\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return createReminder(it.groupValues[1].trim(), 5, "", "")
        }

        // Proyectos y tareas
        Regex("(?:crea|nuevo|haz)(?:me)? un proyecto(?: llamado)?\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return createProject(it.groupValues[1].trim(), "")
        }
        Regex("(?:crea|nuevo) proyecto\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return createProject(it.groupValues[1].trim(), "")
        }
        Regex("añade(?:me)?(?: una)? tarea\\s+(.+?)\\s+(?:al|en el|a el) proyecto\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return createTask(it.groupValues[2].trim(), it.groupValues[1].trim())
        }
        Regex("añade\\s+(.+?)\\s+como tarea(?:\\s+(?:al|en el) proyecto\\s+(.+))?", RegexOption.IGNORE_CASE).find(text)?.let {
            val project = it.groupValues[2].trim()
            if (project.isNotBlank()) return createTask(project, it.groupValues[1].trim())
            return null
        }
        Regex("completa(?: la)? tarea\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return completeTask(it.groupValues[1].trim())
        }
        if (lower.contains("qué proyectos tengo") || lower.contains("que proyectos tengo") ||
            lower.contains("lista mis proyectos") || lower.contains("mis proyectos")
        ) return listProjects()
        if (lower.startsWith("lista") && lower.contains("tarea")) return listTasks("")

        // Notas
        Regex("(?:apunta|guarda una nota(?: que diga)?|toma nota(?: de)?|anota)\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return addNote("Nota rápida", it.groupValues[1].trim())
        }

        // Linterna / wifi / bluetooth / no molestar
        if (lower.contains("linterna")) {
            return toggleFlashlight(lower.contains("enciende") || lower.contains("pon") || lower.contains("activa"))
        }
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            if (lower.contains("enciende") || lower.contains("activa") || lower.contains("pon")) return toggleWifi(true)
            if (lower.contains("apaga") || lower.contains("desactiva") || lower.contains("quita")) return toggleWifi(false)
            return openSettings("wifi")
        }
        if (lower.contains("bluetooth")) {
            if (lower.contains("enciende") || lower.contains("activa") || lower.contains("pon")) return toggleBluetooth(true)
            if (lower.contains("apaga") || lower.contains("desactiva") || lower.contains("quita")) return toggleBluetooth(false)
            return openSettings("bluetooth")
        }
        if (lower.contains("no molestar") || lower.contains("modo silencio")) {
            return toggleDnd(lower.contains("pon") || lower.contains("activa") || lower.contains("enciende"))
        }

        // Volumen y brillo
        if (lower.contains("volumen")) {
            Regex("volumen al\\s+(\\d+)", RegexOption.IGNORE_CASE).find(text)?.let {
                return setVolume(it.groupValues[1].toIntOrNull() ?: -1, "")
            }
            if (lower.contains("sube") || lower.contains("más alto") || lower.contains("mas alto")) return setVolume(-2, "")
            if (lower.contains("baja") || lower.contains("más bajo") || lower.contains("silencio") || lower.contains("mutea")) return setVolume(-3, "")
        }
        if (lower.contains("brillo")) {
            Regex("brillo al\\s+(\\d+)", RegexOption.IGNORE_CASE).find(text)?.let {
                return setBrightness(it.groupValues[1].toIntOrNull() ?: -1)
            }
            if (lower.contains("sube") || lower.contains("más alto")) return setBrightness(-2)
            if (lower.contains("baja") || lower.contains("más bajo")) return setBrightness(-3)
        }

        // Llamadas y mensajes
        Regex("llama(?:le)?\\s+(?:al|a)?\\s*([+\\d][\\d\\s-]{5,})", RegexOption.IGNORE_CASE).find(text)?.let {
            return call(it.groupValues[1].trim())
        }
        Regex("manda(?:le)? un (?:sms|mensaje)(?: a)?\\s*([+\\d][\\d\\s-]{5,})?\\s*(?:que diga|diciendo)?\\s*(.*)", RegexOption.IGNORE_CASE).find(text)?.let {
            val number = it.groupValues[1].trim()
            val msg = it.groupValues[2].trim()
            if (number.isNotBlank()) return sms(number, msg)
            return null
        }
        Regex("whatsapp(?: a)?\\s*([+\\d][\\d\\s-]{5,})?", RegexOption.IGNORE_CASE).find(text)?.let {
            return whatsapp(it.groupValues[1].trim(), "")
        }

        // Búsquedas
        Regex("busca\\s+(.+?)\\s+en\\s+(google|maps|youtube|google maps)", RegexOption.IGNORE_CASE).find(text)?.let {
            val q = it.groupValues[1].trim()
            return when (it.groupValues[2].lowercase()) {
                "maps", "google maps" -> searchMaps(q)
                "youtube" -> searchYoutube(q)
                else -> searchWeb(q)
            }
        }
        Regex("(?:busca|buscame|encuentra)\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return searchWeb(it.groupValues[1].trim())
        }
        Regex("cómo llegar a\\s+(.+)|como llegar a\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            val q = it.groupValues[1].ifBlank { it.groupValues[2] }
            return searchMaps(q.trim())
        }

        // Música / foto / cámara
        if (lower.contains("pon música") || lower.contains("pon musica") || lower.contains("reproduce música")) {
            return playMusic("")
        }
        Regex("reproduce\\s+(.+)|pon\\s+(.+?)\\s+en\\s+(spotify|youtube)", RegexOption.IGNORE_CASE).find(text)?.let {
            val q = it.groupValues[1].ifBlank { it.groupValues[2] }
            return playMusic(q.trim())
        }
        if (lower.contains("haz una foto") || lower.contains("toma una foto") || lower.contains("saca una foto")) return takePhoto()
        if (lower.contains("graba un video") || lower.contains("graba un vídeo")) return recordVideo()
        if (lower.contains("abre la cámara") || lower.contains("abre la camara")) return takePhoto()

        // Copiar / compartir / calendario / ajustes
        Regex("copia\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let { return copyText(it.groupValues[1].trim()) }
        Regex("comparte\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let { return shareText(it.groupValues[1].trim()) }
        Regex("(?:añade|agrega)(?: al)? calendario\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return createCalendarEvent(it.groupValues[1].trim(), 60, "")
        }
        if (lower.contains("abre los ajustes") || lower.contains("abre ajustes") || lower == "ajustes") return openSettings("")
        if (lower.contains("vibra")) return vibrate(300)

        // Abrir apps (al final: es el patrón más genérico)
        Regex("(?:abre|abrir|lanza|inicia)(?: la app| la aplicación| la aplicacion)?\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            val app = it.groupValues[1].trim()
            // Evita capturar búsquedas ya tratadas arriba.
            if (app.isNotBlank() && !lower.contains("ajustes") && !lower.contains("cámara") && !lower.contains("camara")) {
                return openApp(app)
            }
        }

        return null
    }

    // ---------------- Implementación de acciones ----------------

    private fun openApp(name: String): ActionResult {
        val query = name.trim().lowercase(Locale("es", "ES"))
        if (query.isBlank()) return ActionResult(false, "Dime qué app quieres abrir.", "¿Qué aplicación quieres que abra?")
        val known = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "gmail" to "com.google.android.gm",
            "correo" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "facebook" to "com.facebook.katana",
            "cámara" to "com.android.camera",
            "camara" to "com.android.camera",
            "reloj" to "com.google.android.deskclock",
            "galería" to "com.google.android.apps.photos",
            "galeria" to "com.google.android.apps.photos",
            "fotos" to "com.google.android.apps.photos",
            "archivos" to "com.google.android.documentsui",
            "ajustes" to "com.android.settings",
            "configuración" to "com.android.settings",
            "configuracion" to "com.android.settings",
            "calculadora" to "com.google.android.calculator",
            "notas" to "com.google.android.keep",
            "keep" to "com.google.android.keep",
            "drive" to "com.google.android.apps.docs",
            "contactos" to "com.google.android.contacts",
            "teléfono" to "com.android.dialer",
            "telefono" to "com.android.dialer",
            "mensajes" to "com.google.android.apps.messaging",
            "netflix" to "com.netflix.mediaclient",
            "disney" to "com.disneyplus",
            "twitch" to "tv.twitch.android.app",
            "amazon" to "com.amazon.mShop.android.shopping",
            "mercado libre" to "com.mercadolibre",
            "mercadolibre" to "com.mercadolibre",
            "cabify" to "com.cabify",
            "uber" to "com.ubercab",
            "banco" to "com.google.android.apps.docs"
        )
        val pkg = known[query] ?: known.entries.firstOrNull { query.contains(it.key) }?.value
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val label = name.trim().replaceFirstChar { it.uppercase() }
                return ActionResult(true, "Abriendo $label.", "Abriendo $label.")
            }
        }
        // Búsqueda por nombre visible entre las apps instaladas.
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        val match = apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale("es", "ES")).contains(query)
        }
        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return ActionResult(true, "Abriendo ${match.loadLabel(pm)}.", "Abriendo ${match.loadLabel(pm)}.")
            }
        }
        return ActionResult(false, "No encontré la app «$name».", "No encontré la aplicación $name en este teléfono.")
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): ActionResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ActionResult(false, "Hora no válida.", "Dime la hora de la alarma, por ejemplo: pon una alarma a las 7.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Alarma JARVIS" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val hh = hour.toString().padStart(2, '0')
        val mm = minute.toString().padStart(2, '0')
        return ActionResult(true, "Alarma a las $hh:$mm.", "Alarma puesta para las $hh:$mm.")
    }

    private fun setTimer(seconds: Long, label: String): ActionResult {
        if (seconds <= 0) return ActionResult(false, "Duración no válida.", "¿De cuánto tiempo quieres el temporizador?")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Temporizador JARVIS" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val mins = seconds / 60
        val speak = if (mins >= 1) "Temporizador de $mins minutos en marcha." else "Temporizador de $seconds segundos en marcha."
        return ActionResult(true, speak, speak)
    }

    private fun createReminder(title: String, inMinutes: Long, atTime: String, note: String): ActionResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return ActionResult(false, "Dime qué quieres que te recuerde.", "¿Qué quieres que te recuerde?")
        val triggerAt: Long = when {
            atTime.isNotBlank() -> {
                val parts = atTime.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: -1
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (h !in 0..23) return ActionResult(false, "Hora no válida.", "Dime la hora del recordatorio.")
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                cal.timeInMillis
            }
            inMinutes > 0 -> System.currentTimeMillis() + inMinutes * 60_000L
            else -> System.currentTimeMillis() + 5 * 60_000L
        }
        val reminder = store.addReminder(cleanTitle, triggerAt, note)
        JarvisNotifications.schedule(context, reminder.id, cleanTitle, note, triggerAt)
        val whenText = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date(triggerAt))
        return ActionResult(true, "Recordatorio «$cleanTitle» a las $whenText.", "Te avisaré de $cleanTitle a las $whenText.")
    }

    private fun createProject(name: String, description: String): ActionResult {
        val clean = name.trim()
        if (clean.isBlank()) return ActionResult(false, "El proyecto necesita un nombre.", "¿Cómo se llama el proyecto?")
        val p = store.createProject(clean, description)
        return ActionResult(true, "Proyecto «${p.name}» creado.", "Proyecto ${p.name} creado. ¿Qué tarea le añado primero?")
    }

    private fun createTask(projectQuery: String, title: String): ActionResult {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return ActionResult(false, "Dime la tarea.", "¿Qué tarea quieres añadir?")
        val projects = store.getProjects()
        if (projects.isEmpty()) {
            return ActionResult(false, "Aún no hay proyectos.", "Primero crea un proyecto: dime «crea un proyecto llamado…».")
        }
        val project = if (projectQuery.isBlank()) projects.first()
        else store.findProject(projectQuery)
        if (project == null) {
            return ActionResult(false, "No encontré el proyecto «$projectQuery».", "No encontré ese proyecto. Tienes: " + projects.joinToString(", ") { it.name })
        }
        val task = store.addTask(project.id, cleanTitle)
        if (task == null) return ActionResult(false, "No pude añadir la tarea.", "No pude añadir esa tarea.")
        return ActionResult(true, "Tarea «${task.title}» añadida a ${project.name}.", "Tarea añadida a ${project.name}.")
    }

    private fun completeTask(query: String): ActionResult {
        return if (store.completeTaskByTitle(query)) {
            ActionResult(true, "Tarea completada: $query", "Hecha. Tarea completada.")
        } else {
            ActionResult(false, "No encontré esa tarea pendiente.", "No encontré esa tarea pendiente.")
        }
    }

    private fun listProjects(): ActionResult {
        val projects = store.getProjects()
        if (projects.isEmpty()) {
            return ActionResult(true, "No tienes proyectos todavía.", "Aún no tienes proyectos. Dime «crea un proyecto llamado…» y empezamos.")
        }
        val text = projects.joinToString(". ") {
            val pending = it.tasks.count { t -> !t.done }
            "${it.name}: $pending pendientes de ${it.tasks.size}"
        }
        return ActionResult(true, text, "Tienes ${projects.size} proyectos. $text.")
    }

    private fun listTasks(projectQuery: String): ActionResult {
        val projects = store.getProjects()
        if (projects.isEmpty()) return ActionResult(true, "No hay proyectos.", "Aún no tienes proyectos.")
        val project = if (projectQuery.isBlank()) projects.first() else store.findProject(projectQuery)
        if (project == null) return ActionResult(false, "Proyecto no encontrado.", "No encontré ese proyecto.")
        if (project.tasks.isEmpty()) return ActionResult(true, "${project.name} no tiene tareas.", "${project.name} no tiene tareas todavía.")
        val pending = project.tasks.filter { !it.done }
        val text = pending.joinToString(". ") { it.title }
        return ActionResult(true, text, "En ${project.name} tienes ${pending.size} pendientes: $text.")
    }

    private fun addNote(title: String, body: String): ActionResult {
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return ActionResult(false, "Dime qué quieres apuntar.", "¿Qué quieres que apunte?")
        val note = store.addNote(title.ifBlank { "Nota rápida" }, cleanBody)
        return ActionResult(true, "Nota guardada: ${note.body.take(60)}", "Nota guardada.")
    }

    private fun setVolume(level: Int, streamName: String): ActionResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName.lowercase()) {
            "alarm" -> AudioManager.STREAM_ALARM
            "ring", "ringtone" -> AudioManager.STREAM_RING
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(stream)
        val target = when (level) {
            -2 -> minOf(max, audio.getStreamVolume(stream) + 2)
            -3 -> 0
            in 0..100 -> (level * max / 100)
            else -> return ActionResult(false, "Nivel no válido.", "Dime el volumen del 0 al 100.")
        }
        audio.setStreamVolume(stream, target, AudioManager.FLAG_PLAY_SOUND)
        val pct = if (max > 0) target * 100 / max else 0
        return ActionResult(true, "Volumen al $pct%.", "Volumen al $pct por ciento.")
    }

    private fun setBrightness(level: Int): ActionResult {
        if (!Settings.System.canWrite(context)) {
            openSettings("display")
            return ActionResult(true, "Abriendo ajustes de pantalla para el brillo.", "Android pide permiso para cambiar el brillo: te abro los ajustes de pantalla.")
        }
        val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (level) {
            -2 -> minOf(255, current + 40)
            -3 -> maxOf(10, current - 40)
            in 0..100 -> maxOf(10, level * 255 / 100)
            else -> return ActionResult(false, "Nivel no válido.", "Dime el brillo del 0 al 100.")
        }
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
        return ActionResult(true, "Brillo ajustado.", "Brillo ajustado.")
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(on: Boolean): ActionResult {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            val ok = wifi.setWifiEnabled(on)
            if (ok) {
                ActionResult(true, if (on) "Wi-Fi activado." else "Wi-Fi desactivado.", if (on) "Wi-Fi activado." else "Wi-Fi desactivado.")
            } else {
                openSettings("wifi")
                ActionResult(true, "Abriendo ajustes de Wi-Fi.", "En este Android el Wi-Fi se cambia desde los ajustes: te los abro.")
            }
        } catch (e: Exception) {
            openSettings("wifi")
            ActionResult(true, "Abriendo ajustes de Wi-Fi.", "Te abro los ajustes de Wi-Fi.")
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth(on: Boolean): ActionResult {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return ActionResult(false, "Este teléfono no tiene Bluetooth.", "Este teléfono no tiene Bluetooth.")
            val ok = if (on) adapter.enable() else adapter.disable()
            if (ok) {
                ActionResult(true, if (on) "Bluetooth activado." else "Bluetooth desactivado.", if (on) "Bluetooth activado." else "Bluetooth desactivado.")
            } else {
                openSettings("bluetooth")
                ActionResult(true, "Abriendo ajustes de Bluetooth.", "Te abro los ajustes de Bluetooth.")
            }
        } catch (e: Exception) {
            openSettings("bluetooth")
            ActionResult(true, "Abriendo ajustes de Bluetooth.", "Te abro los ajustes de Bluetooth.")
        }
    }

    private fun toggleFlashlight(on: Boolean): ActionResult {
        return try {
            val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = camera.cameraIdList.firstOrNull() ?: return ActionResult(false, "No hay cámara.", "No encontré la cámara para la linterna.")
            camera.setTorchMode(id, on)
            ActionResult(true, if (on) "Linterna encendida." else "Linterna apagada.", if (on) "Linterna encendida." else "Linterna apagada.")
        } catch (e: Exception) {
            ActionResult(false, "No pude usar la linterna: ${e.message}", "No pude usar la linterna.")
        }
    }

    private fun toggleDnd(on: Boolean): ActionResult {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (on) audio.setInterruptionFilter(AudioManager.INTERRUPTION_FILTER_PRIORITY)
                else audio.setInterruptionFilter(AudioManager.INTERRUPTION_FILTER_ALL)
            }
            ActionResult(true, if (on) "No molestar activado." else "No molestar desactivado.", if (on) "Modo no molestar activado." else "Modo no molestar desactivado.")
        } catch (e: Exception) {
            openSettings("sound")
            ActionResult(true, "Abriendo ajustes de sonido.", "Te abro los ajustes de sonido.")
        }
    }

    private fun vibrate(ms: Long): ActionResult {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) vibrator.vibrate(ms.coerceIn(50, 5000))
        return ActionResult(true, "Vibrando.", "Vibrando.")
    }

    private fun call(number: String): ActionResult {
        val clean = number.trim()
        if (clean.isBlank()) return ActionResult(false, "Dime el número.", "¿A qué número quieres llamar?")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Abriendo el marcador para $clean.", "Te abro el marcador para llamar al $clean.")
    }

    private fun sms(number: String, message: String): ActionResult {
        val clean = number.trim()
        if (clean.isBlank()) return ActionResult(false, "Dime el número del SMS.", "¿A qué número mando el mensaje?")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Abriendo mensajes para $clean.", "Te abro los mensajes para el $clean.")
    }

    private fun whatsapp(number: String, message: String): ActionResult {
        val digits = number.filter { it.isDigit() || it == '+' }
        val url = if (digits.isNotBlank()) {
            val clean = digits.replace("+", "")
            if (message.isNotBlank()) "https://wa.me/$clean?text=${Uri.encode(message)}" else "https://wa.me/$clean"
        } else {
            "https://wa.me/"
        }
        return openUrl(url)
    }

    private fun openUrl(url: String): ActionResult {
        val clean = url.trim()
        if (clean.isBlank()) return ActionResult(false, "Falta el enlace.", "¿Qué enlace quieres que abra?")
        val full = if (clean.startsWith("http")) clean else "https://$clean"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(full)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Abriendo $full", "Abriendo el enlace.")
    }

    private fun searchWeb(query: String): ActionResult {
        val q = query.trim()
        if (q.isBlank()) return ActionResult(false, "Dime qué busco.", "¿Qué quieres que busque?")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Buscando «$q» en Google.", "Buscando $q en Google.")
    }

    private fun searchMaps(query: String): ActionResult {
        val q = query.trim()
        if (q.isBlank()) return ActionResult(false, "Dime el lugar.", "¿Qué lugar busco en Maps?")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(q)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Buscando «$q» en Maps.", "Buscando $q en Maps.")
    }

    private fun searchYoutube(query: String): ActionResult {
        val q = query.trim()
        if (q.isBlank()) return ActionResult(false, "Dime qué busco en YouTube.", "¿Qué busco en YouTube?")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Buscando «$q» en YouTube.", "Buscando $q en YouTube.")
    }

    private fun playMusic(query: String): ActionResult {
        val q = query.trim()
        val url = if (q.isBlank()) "https://music.youtube.com/"
        else "https://music.youtube.com/search?q=${Uri.encode(q)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val speak = if (q.isBlank()) "Abriendo tu música." else "Reproduciendo $q."
        return ActionResult(true, speak, speak)
    }

    private fun takePhoto(): ActionResult {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Abriendo la cámara.", "Abriendo la cámara.")
    }

    private fun recordVideo(): ActionResult {
        val intent = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Abriendo la grabación de vídeo.", "Abriendo la cámara de vídeo.")
    }

    private fun calculate(expression: String): ActionResult {
        val expr = expression.trim()
        if (expr.isBlank()) return ActionResult(false, "Dime la operación.", "¿Qué quieres que calcule?")
        return try {
            val value = Calculator.evaluate(expr)
            val pretty = if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale("es", "ES"), "%.4f", value).trimEnd('0').trimEnd('.')
            ActionResult(true, "$expr = $pretty", "El resultado es $pretty.")
        } catch (e: Exception) {
            ActionResult(false, "No pude calcular eso.", "No pude calcular eso. Prueba con algo como 120 por 3 más 50.")
        }
    }

    private fun getTime(): ActionResult {
        val time = SimpleDateFormat("HH:mm", Locale("es", "ES")).format(Date())
        return ActionResult(true, "Son las $time.", "Son las $time.")
    }

    private fun getDate(): ActionResult {
        val date = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES")).format(Date())
        return ActionResult(true, "Hoy es $date.", "Hoy es $date.")
    }

    private fun getBattery(): ActionResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val speak = if (charging) "Batería al $level por ciento, cargando." else "Batería al $level por ciento."
        return ActionResult(true, speak, speak)
    }

    private fun getDeviceInfo(): ActionResult {
        val text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        return ActionResult(true, text, "Este teléfono es un $text.")
    }

    private fun copyText(text: String): ActionResult {
        val clean = text.trim()
        if (clean.isBlank()) return ActionResult(false, "Nada que copiar.", "¿Qué quieres que copie?")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("jarvis", clean))
        return ActionResult(true, "Copiado.", "Copiado al portapapeles.")
    }

    private fun shareText(text: String): ActionResult {
        val clean = text.trim()
        if (clean.isBlank()) return ActionResult(false, "Nada que compartir.", "¿Qué quieres que comparta?")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, clean)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir con").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return ActionResult(true, "Compartiendo.", "Te abro el menú para compartir.")
    }

    private fun openSettings(section: String): ActionResult {
        val action = when (section.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "pantalla", "brillo" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "sonido" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "batería", "bateria" -> Settings.ACTION_BATTERY_SETTINGS
            "location", "ubicación", "ubicacion" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return ActionResult(true, "Abriendo ajustes.", "Abriendo los ajustes.")
    }

    private fun createCalendarEvent(title: String, inMinutes: Long, note: String): ActionResult {
        val clean = title.trim()
        if (clean.isBlank()) return ActionResult(false, "Dime el evento.", "¿Qué evento añado al calendario?")
        val start = System.currentTimeMillis() + inMinutes.coerceAtLeast(0) * 60_000L
        val end = start + 60 * 60_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = "vnd.android.cursor.item/event"
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            putExtra(CalendarContract.EXTRA_EVENT_TITLE, clean)
            putExtra(CalendarContract.EXTRA_EVENT_DESCRIPTION, note)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(true, "Abriendo el calendario para «$clean».", "Te abro el calendario para crear $clean.")
    }

    // ---------------- Utilidades JSON ----------------

    private fun JsonObject.str(key: String): String {
        if (!has(key)) return ""
        val v = get(key)
        return if (v.isJsonNull) "" else v.asString
    }

    private fun JsonObject.int(key: String, default: Int): Int {
        if (!has(key)) return default
        val v = get(key)
        return try {
            if (v.isJsonNull) default else v.asInt
        } catch (e: Exception) {
            v.asString.toIntOrNull() ?: default
        }
    }

    private fun JsonObject.long(key: String, default: Long): Long {
        if (!has(key)) return default
        val v = get(key)
        return try {
            if (v.isJsonNull) default else v.asLong
        } catch (e: Exception) {
            v.asString.toLongOrNull() ?: default
        }
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        if (!has(key)) return default
        val v = get(key)
        return try {
            if (v.isJsonNull) default else v.asBoolean
        } catch (e: Exception) {
            v.asString.equals("true", ignoreCase = true)
        }
    }

    /** Calculadora segura: + - * / % ^ paréntesis y «por»/«entre»/«más»/«menos». */
    object Calculator {
        fun evaluate(raw: String): Double {
            var expr = raw.lowercase(Locale("es", "ES"))
            expr = expr.replace("por", "*").replace("entre", "/")
            expr = expr.replace("más", "+").replace("mas", "+")
            expr = expr.replace("menos", "-")
            expr = expr.replace("x", "*").replace(",", ".")
            expr = expr.replace(Regex("[^0-9+\\-*/%^.()\\s]"), " ")
            val parser = Parser(expr)
            val value = parser.parseExpression()
            parser.skipSpaces()
            if (!parser.atEnd()) throw IllegalArgumentException("Expresión incompleta")
            return value
        }

        private class Parser(private val s: String) {
            private var pos = 0

            fun atEnd(): Boolean = pos >= s.length
            fun skipSpaces() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

            fun parseExpression(): Double {
                var value = parseTerm()
                while (true) {
                    skipSpaces()
                    if (atEnd()) return value
                    when (s[pos]) {
                        '+' -> { pos++; value += parseTerm() }
                        '-' -> { pos++; value -= parseTerm() }
                        else -> return value
                    }
                }
            }

            private fun parseTerm(): Double {
                var value = parseFactor()
                while (true) {
                    skipSpaces()
                    if (atEnd()) return value
                    when (s[pos]) {
                        '*' -> { pos++; value *= parseFactor() }
                        '/' -> { pos++; value /= parseFactor() }
                        '%' -> { pos++; value %= parseFactor() }
                        else -> return value
                    }
                }
            }

            private fun parseFactor(): Double {
                skipSpaces()
                if (atEnd()) throw IllegalArgumentException("Falta un número")
                if (s[pos] == '-') { pos++; return -parseFactor() }
                if (s[pos] == '+') { pos++; return parseFactor() }
                if (s[pos] == '(') {
                    pos++
                    val value = parseExpression()
                    skipSpaces()
                    if (atEnd() || s[pos] != ')') throw IllegalArgumentException("Falta )")
                    pos++
                    return parsePower(value)
                }
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (start == pos) throw IllegalArgumentException("Número no válido")
                val number = s.substring(start, pos).toDouble()
                return parsePower(number)
            }

            private fun parsePower(base: Double): Double {
                skipSpaces()
                if (!atEnd() && s[pos] == '^') {
                    pos++
                    return Math.pow(base, parseFactor())
                }
                return base
            }
        }
    }
}
