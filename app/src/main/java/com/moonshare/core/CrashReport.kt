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

    /**
     * Instala el handler. Se llama lo primero en `Application.onCreate`, antes de
     * tocar cualquier otro subsistema: si lo que rompe la app es un subsistema,
     * esto ya está operativo y lo va a poder contar.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // Se delega en el handler original para que el sistema cierre el
            // proceso igual que siempre (ANR dialog, clean-up, etc.).
            previous?.uncaughtException(thread, error)
        }
    }

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
            pw.println("init     : fase=${Startup.phase.get()}")
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
        context.getSharedPreferences("moon_crash", Context.MODE_PRIVATE)
            .edit().putString(MARKER, "crash-$stamp.log").apply()

        // recorte: no llenar el storage del usuario de reportes
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)
            ?.forEach { it.delete() }

        MoonLog.e("Crash", "registrado en crash-$stamp.log")
    }

    /** true si hay un reporte pendiente de mostrar. */
    fun hasPending(context: Context): Boolean = pendingFile(context)?.exists() == true

    fun pendingText(context: Context): String =
        pendingFile(context)?.readText().orEmpty()

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("MoonShare crash", text))
    }

    /** Se llama cuando el usuario ya vio el reporte. */
    fun acknowledge(context: Context) {
        context.getSharedPreferences("moon_crash", Context.MODE_PRIVATE)
            .edit().remove(MARKER).apply()
    }

    fun deleteAll(context: Context) {
        runCatching { File(context.filesDir, DIR).deleteRecursively() }
        acknowledge(context)
    }

    private fun pendingFile(context: Context): File? {
        val name = context.getSharedPreferences("moon_crash", Context.MODE_PRIVATE)
            .getString(MARKER, null) ?: return null
        return File(File(context.filesDir, DIR), name)
    }
}

/**
 * Marcador de en qué paso está el arranque. Ante un crash temprano esto distingue
 * "murió antes de la UI" de "murió en el primer frame de Compose", que son dos
 * familias de causas distintas.
 */
object Startup {
    enum class Phase { NONE, APP_BEGIN, NOTIF, SFX, ENGINE, ACTIVITY, FIRST_FRAME, READY }

    val phase = java.util.concurrent.atomic.AtomicReference(Phase.NONE)

    fun set(p: Phase) {
        phase.set(p)
        MoonLog.i("Boot", "fase → ${p.name}")
    }
}
