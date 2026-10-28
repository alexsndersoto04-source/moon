package com.airgesture.transfer.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.airgesture.transfer.util.Permissions
import com.airgesture.transfer.util.Radios
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring buffer of transport events plus a full environment report.
 *
 * The report is what a user can copy with one tap when a transfer does not work:
 * it contains everything that can silently break Nearby discovery (permissions,
 * radios, Play Services, app version) and the last events with their status
 * codes. No account, no server, no telemetry — the text is generated locally.
 */
object Diag {

    private const val MAX_LINES = 40

    /**
     * Live camera numbers, refreshed by the analyzer. They are the first thing
     * to look at when "the hand is not detected": they separate a camera that
     * delivers no frames from a model that never loaded from a detector that
     * runs but sees nothing.
     */
    @Volatile
    private var pipeline: String = "sin datos"

    fun setPipeline(line: String) {
        pipeline = line
    }
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(line: String) {
        lines.addLast("${stamp.format(Date())}  $line")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    fun report(context: Context): String = buildString {
        appendLine("=== AirGesture Transfer · diagnóstico ===")
        appendLine("Generado: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine()
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App: ${versionName(context)}")
        appendLine("Play Services: ${playServicesVersion(context)}")
        appendLine()
        appendLine("--- Permisos ---")
        Permissions.required(context).forEach { permission ->
            val label = permission.substringAfterLast('.')
            appendLine("$label = ${if (Permissions.granted(context, permission)) "concedido" else "DENEGADO"}")
        }
        appendLine()
        appendLine("--- Radios ---")
        appendLine("Bluetooth encendido = ${Radios.bluetoothEnabled(context)}")
        appendLine("Ubicación del sistema = ${Radios.locationEnabled(context)}")
        appendLine("Wi-Fi Direct (feature) = ${hasFeature(context, PackageManager.FEATURE_WIFI_DIRECT)}")
        appendLine("Bluetooth LE (feature) = ${hasFeature(context, PackageManager.FEATURE_BLUETOOTH_LE)}")
        appendLine()
        appendLine("--- Cámara y modelo ---")
        appendLine(pipeline)
        appendLine()
        appendLine("--- Últimos eventos ---")
        val events = snapshot()
        if (events.isEmpty()) appendLine("(sin eventos)") else events.forEach { appendLine(it) }
        appendLine("=== fin ===")
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("desconocida")

    private fun playServicesVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo("com.google.android.gms", 0)
        info.versionName ?: "instalado"
    }.getOrElse { "NO INSTALADO" }

    private fun hasFeature(context: Context, feature: String): Boolean =
        context.packageManager.hasSystemFeature(feature)

    @Suppress("unused")
    private fun apiLevel(): Int = Build.VERSION.SDK_INT
}
