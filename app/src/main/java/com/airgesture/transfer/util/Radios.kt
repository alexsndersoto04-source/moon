package com.airgesture.transfer.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.os.Build

/**
 * Preflight checks for the radios Nearby Connections needs.
 *
 * These two conditions fail **silently** at the API level — `startDiscovery()`
 * returns success and simply never reports an endpoint — so the app has to look
 * at them itself and tell the user what to switch on.
 */
object Radios {

    /** Bluetooth must be on: it carries advertising and the initial handshake. */
    fun bluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    /**
     * Location services switch. Android requires it for BLE scanning on every
     * release when fine location is the permission in play.
     */
    fun locationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
