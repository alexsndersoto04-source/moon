package com.airgesture.transfer.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture.
 *
 * A side-loaded APK has no Play Console and no ADB attached, so an uncaught
 * exception would otherwise be invisible. This handler writes the full stack
 * trace to a file inside the app's external files dir; the main screen shows it
 * on next launch with a one-tap "copiar" action.
 *
 * It never swallows the crash: the previous handler runs afterwards so the
 * process still dies the way Android expects.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter()
        PrintWriter(stack).use { writer -> error.printStackTrace(writer) }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            append("AirGesture Transfer · crash\n")
            append("Hora: $timestamp\n")
            append("Hilo: ${thread.name}\n")
            append("Dispositivo: android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n")
            append("Pantalla: último crash registrado\n\n")
            append(stack.toString())
        }
        crashFile(context).writeText(body)
        Log.e(TAG, body)
    }

    private fun crashFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    /** Returns the stored trace, or null when the last run was clean. */
    fun readAndClear(context: Context): String? {
        val file = crashFile(context)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }
}
