package com.moonshare.core

import android.util.Log
import com.moonshare.BuildConfig

/**
 * Log con un solo TAG raíz ([MoonShare]) para que filtrar en `adb logcat` sea
 * `adb logcat -s MoonShare:*`. En release se queda mudo salvo los warnings/errores.
 */
object MoonLog {

    private const val ROOT = "MoonShare"
    private const val MAX_LEN = 3800 // logcat corta más que esto y parte el renglón

    @Volatile
    var verbose: Boolean = BuildConfig.DEBUG
        set(value) {
            field = value
        }

    fun d(sub: String, msg: String) {
        if (verbose) log(Log.DEBUG, sub, msg)
    }

    fun i(sub: String, msg: String) = log(Log.INFO, sub, msg)

    fun w(sub: String, msg: String, t: Throwable? = null) = log(Log.WARN, sub, msg, t)

    fun e(sub: String, msg: String, t: Throwable? = null) = log(Log.ERROR, sub, msg, t)

    private fun log(priority: Int, sub: String, msg: String, t: Throwable? = null) {
        if (!verbose && priority < Log.WARN) return
        val line = "[$sub] $msg"
        val text = if (t != null && verbose) "$line\n${Log.getStackTraceString(t)}" else line
        text.chunked(MAX_LEN).forEach { chunk ->
            Log.println(priority, "$ROOT/$sub", chunk)
        }
    }

    /** Bytes legibles, sin Redondeos raros: 1.4 GB, 812 MB, 34 KB, 902 B. */
    fun human(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        val dec = if (v >= 100) 0 else if (v >= 10) 1 else 2
        return String.format("%.${dec}f %s", v, units[i])
    }

    /** Velocidad en human. */
    fun rate(bytesPerSec: Double): String =
        if (bytesPerSec < 1) "—" else "${human(bytesPerSec.toLong())}/s"
}
