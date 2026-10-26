package com.moonshare.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Atrapacrasos para diagnóstico "de campo".
 *
 * El caso que justifica estos ~100 archivos de líneas: una app que revienta al
 * arrancar en el teléfono de un usuario sin cable ni `adb` es imposible de
 * depurar. Con esto, el stack completo queda en `filesDir/crash-<n>.log`, se ve en
 * un cartel dentro de la app al siguiente arranque, y se copia al portapapeles con
 * un toque. Quien reporta solo tiene que pegar.
 *
 * No sube nada a ningún lado: es un archivo local.
 */
object CrashReport {

    private const val DIR = "crash"
    private const val MARKER = "crash_pending"
    private const val MAX_REPORTS = 5
    private const val PREFS = "moon_crash"
    private const val SESSION = "session_open"
    private const val PHASE_KEY = "last_phase"
    private const val WARN = "boot_warnings"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Instala el handler. Se llama lo primero en `Application.onCreate`, antes de
     * tocar cualquier otro subsistema: si lo que rompe la app es un subsistema,
     * esto ya está operativo y lo va a poder contar.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        Startup.bind(appContext)
        // (1) Antes de nada: la sesion anterior termino sin marcarse como sana?
        // Un proceso muerto por el sistema (lmkd, MIUI) o por un crash NATIVO nunca
        // pasa por el uncaught handler, asi que sin esta bandera no habria ni prueba
        // de donde murio. Acá se reconstruye con la ultima fase persistida.
        runCatching { auditAbandonedSession(appContext) }
        // (2) La sesion nueva queda marcada como "en curso" hasta que la UI demuestre
        //     que levanto (markHealthy). commit(), no apply(): tiene que sobrevivir
        //     a que el proceso muera en los proximos 200 ms.
        runCatching { prefs(appContext).edit().putBoolean(SESSION, true).commit() }
        // (3) y recien ahora el handler de excepciones Java.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // Se delega en el handler original para que el sistema cierre el
            // proceso igual que siempre (ANR dialog, clean-up, etc.).
            previous?.uncaughtException(thread, error)
        }
    }

    private fun auditAbandonedSession(context: Context) {
        val sp = prefs(context)
        if (!sp.getBoolean(SESSION, false)) return
        val phase = sp.getString(PHASE_KEY, Startup.Phase.NONE.name) ?: "NONE"
        // Si el handler ya dejo un reporte, ese es el bueno: no lo pisamos.
        if (sp.getString(MARKER, null) == null) {
            val dir = File(context.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            val name = "crash-${System.currentTimeMillis()}.log"
            File(dir, name).writeText(
                buildString {
                    appendLine("=== MoonShare: el proceso murio sin excepcion ===")
                    appendLine("fase alcanzada: $phase")
                    appendLine("device   : ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
                    appendLine()
                    appendLine("No hay stack porque no hubo excepcion Java: esto es un crash nativo")
                    appendLine("(RenderThread/Skia) o el sistema matando el proceso.")
                },
            )
            sp.edit().putString(MARKER, name).commit()
        }
    }

    /**
     * Marca que la app ya arranco bien: a partir de aca, una bandera de sesion
     * abandonada significa "murió durante el uso", no "murió al abrir".
     */
    fun markHealthy(context: Context) {
        runCatching {
            prefs(context).edit().putBoolean(SESSION, false).putString(PHASE_KEY, Startup.Phase.READY.name).commit()
        }
    }

    internal fun debugPhase(context: Context, name: String) {
        runCatching { prefs(context).edit().putString(PHASE_KEY, name).commit() }
    }

    /**
     * Avisos de subsistemas que no arrancaron. Se guardan en SharedPreferences con
     * commit() porque sirven justo en el caso en que la app no llegó a dibujar: si el
     * motor o el sonido mueren y eso a su vez tumba la Activity, el aviso tiene que
     * estar en disco para poder contarlo al arranque siguiente.
     */
    fun addBootWarning(context: Context, line: String) {
        runCatching {
            val sp = prefs(context)
            val prev = sp.getString(WARN, "").orEmpty()
            sp.edit().putString(WARN, (prev + "\n" + line).trim().lines().takeLast(6).joinToString("\n")).commit()
        }
    }

    fun bootWarnings(context: Context): String =
        prefs(context).getString(WARN, "").orEmpty()

    fun lastPhase(context: Context): String =
        prefs(context).getString(PHASE_KEY, "NONE") ?: "NONE"

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== MoonShare crash ===")
            pw.println("time     : ${System.currentTimeMillis()}")
            pw.println("thread   : ${thread.name}")
            pw.println("app      : ${com.moonshare.BuildConfig.VERSION_NAME} (${com.moonshare.BuildConfig.VERSION_CODE})")
            pw.println("android  : API ${Build.VERSION.SDK_INT} ${Build.VERSION.RELEASE}")
            pw.println("device   : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            pw.println("init     : fase=${Startup.phase.get()} (ultimo en disco: ${lastPhase(context)})")
            pw.println()
            error.printStackTrace(pw)
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 8) {
                pw.println("\n--- caused by ---")
                cause.printStackTrace(pw)
                cause = cause.cause
                depth++
            }
        }

        val stamp = System.currentTimeMillis()
        File(dir, "crash-$stamp.log").writeText(sw.toString())
        // último, para leerlo rápido al abrir la app
        File(dir, "latest.log").writeText(sw.toString())
        prefs(context).edit()
            .putString(MARKER, "crash-$stamp.log")
            .putBoolean(SESSION, false)
            .putString(PHASE_KEY, Startup.phase.get().name)
            .commit()

        // recorte: no llenar el storage del usuario de reportes
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)
            ?.forEach { it.delete() }

        MoonLog.e("Crash", "registrado en crash-$stamp.log")
    }

    /** true si hay un reporte pendiente de mostrar. */
    fun hasPending(context: Context): Boolean = pendingFile(context)?.exists() == true

    /** Texto del reporte pendiente, con los avisos de subsistemas abajo. */
    fun pendingText(context: Context): String {
        val trace = pendingFile(context)?.readText().orEmpty()
        val warn = bootWarnings(context)
        return if (warn.isBlank()) trace else trace + "\n\n=== avisos del arranque ===\n" + warn
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("MoonShare crash", text))
    }

    /** Se llama cuando el usuario ya vio el reporte. */
    fun acknowledge(context: Context) {
        prefs(context).edit().remove(MARKER).remove(WARN).apply()
    }

    fun deleteAll(context: Context) {
        runCatching { File(context.filesDir, DIR).deleteRecursively() }
        acknowledge(context)
    }

    private fun pendingFile(context: Context): File? {
        val name = prefs(context).getString(MARKER, null) ?: return null
        return File(File(context.filesDir, DIR), name)
    }
}

/**
 * Marcador de en qué paso está el arranque. Ante un crash temprano esto distingue
 * "murió antes de la UI" de "murió en el primer frame de Compose", que son dos
 * familias de causas distintas.
 */
object Startup {

    /** Orden del arranque; sirve para acotar dónde murió el proceso. */
    enum class Phase { NONE, APP_BEGIN, NOTIF, SFX, ENGINE, ACTIVITY, FIRST_FRAME, READY }

    private var app: Context? = null
    val phase = java.util.concurrent.atomic.AtomicReference(Phase.NONE)

    fun bind(context: Context) {
        app = context.applicationContext
    }

    fun set(p: Phase) {
        phase.set(p)
        MoonLog.i("Boot", "fase → ${p.name}")
        // commit() sincronico: si el proceso muere 5 ms despues, igual tiene que
        // quedar escrito hasta donde llegamos. Son ~7 escrituras en todo el arranque.
        runCatching {
            app?.let { CrashReport.debugPhase(it, p.name) }
        }
    }
}
