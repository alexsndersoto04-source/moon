package com.airgesture.transfer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Single source of truth for the runtime permission set.
 *
 * Android 10/11 need legacy location to scan BLE and Wi-Fi Direct, Android 12
 * needs the BLUETOOTH_* trio, Android 13 adds NEARBY_WIFI_DEVICES and splits
 * media into per-type permissions.
 */
object Permissions {

    fun required(context: Context): List<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        val sdk = Build.VERSION.SDK_INT

        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_IMAGES
            permissions += Manifest.permission.READ_MEDIA_VIDEO
            permissions += Manifest.permission.READ_MEDIA_AUDIO
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (sdk >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        // Only ask for what the platform actually declares.
        return permissions.filter { name ->
            runCatching {
                context.packageManager.getPermissionInfo(name, 0)
                true
            }.getOrDefault(false)
        }
    }

    fun missing(context: Context): List<String> = required(context).filterNot { granted(context, it) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Camera + transport are hard requirements; media access degrades gracefully. */
    fun essentialsGranted(context: Context): Boolean {
        if (!granted(context, Manifest.permission.CAMERA)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(context, Manifest.permission.BLUETOOTH_SCAN)) return false
            if (!granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)) return false
            if (!granted(context, Manifest.permission.BLUETOOTH_CONNECT)) return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        ) {
            return false
        }
        return true
    }

    fun mediaGranted(context: Context): Boolean {
        val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return mediaPermissions.any { granted(context, it) }
    }
}
