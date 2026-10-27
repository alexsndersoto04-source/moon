package com.moonshare.core.perm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Mapa de permisos de MoonShare por nivel de API. Está centralizado acá porque es
 * el único punto donde Android cambia las reglas (10→11→12→13→14) y la app tiene
 * que seguir funcionando en todas.
 */
object MoonPermissions {

    /** Necesarios para que exista la red Wi-Fi Direct (sin ellos, discover() revienta). */
    fun nearby(): List<String> = buildList {
        // Nearby Connections combina Bluetooth y Wi-Fi según el equipo. Pedimos el
        // conjunto real por versión en vez de descubrir el faltante al primer error.
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT <= 32) {
            // API 29..32: descubrimiento cercano y Wi-Fi Direct piden ubicación
            // aunque MoonShare no geolocalice nada.
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** Necesarios para leer el carrete / música / documentos. */
    fun media(): List<String> = if (Build.VERSION.SDK_INT >= 33) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Notificaciones: solo son obligatorias desde 13 para el progreso en curso. */
    fun notifications(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyList()

    /** Lo que hay que pedir en el onboarding, en el orden en que se explica. */
    fun requiredForFirstRun(): List<String> = (nearby() + media() + notifications()).distinct()

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAll(context: Context, permissions: List<String>): Boolean =
        permissions.all { has(context, it) }

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filterNot { has(context, it) }

    /**
     * "Acceso a todos los archivos" (opt-in, para la pestaña Carpetas). No es
     * obligatorio: sin esto, la app igual lee MediaStore y el picker SAF.
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun allFilesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:" + context.packageName))

    /** En Android < 30 no existe el toggle: se explica y se lleva a la ficha de la app. */
    fun storageSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + context.packageName))

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** Pantalla de la app (desde ahí: batería sin optimizaciones en MIUI). */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + context.packageName))

    /** Ajustes de Wi-Fi: el usuario necesita la radio encendida para que exista WFD. */
    fun wifiSettingsIntent(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

    /** Ajustes de NFC. */
    fun nfcSettingsIntent(): Intent = Intent(Settings.ACTION_NFC_SETTINGS)
}
