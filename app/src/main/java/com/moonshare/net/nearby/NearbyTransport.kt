package com.moonshare.net.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.moonshare.core.MoonLog

/** Telefono cercano encontrado por Nearby Connections. */
data class NearbyDevice(
    val endpointId: String,
    val deviceName: String,
    val sessionNonce: String,
)

/** Eventos de bajo nivel. TransferEngine decide la interfaz y el consentimiento. */
interface NearbyEvents {
    fun onAdvertisingStarted()
    fun onDiscoveryStarted()
    fun onEndpointFound(device: NearbyDevice)
    fun onEndpointLost(endpointId: String)
    fun onConnectionInitiated(endpointId: String, peerName: String, tapNonce: String, incoming: Boolean)
    fun onConnectionEstablished(endpointId: String)
    fun onConnectionRejected(endpointId: String)
    fun onDisconnected(endpointId: String)
    fun onPayloadReceived(endpointId: String, payload: Payload)
    fun onPayloadUpdate(endpointId: String, update: PayloadTransferUpdate)
    fun onOperationFailed(operation: String, message: String)
}

/**
 * Transporte principal de MoonShare, basado en Nearby Connections de Android.
 *
 * Esta es la diferencia con la implementacion anterior: el sistema negocia el
 * medio fisico (Wi-Fi Direct, Wi-Fi LAN o Bluetooth para control/datos segun el
 * equipo), asigna extremos y mueve los Payload.File. MoonShare ya no inventa un
 * grupo, una IP propietaria ni un socket sobre una direccion supuesta.
 */
class NearbyTransport(
    context: Context,
    private val events: NearbyEvents,
) {
    private val appContext = context.applicationContext

    // Perezoso y tolerante: construir el motor en Application/tests no debe tocar
    // Google Play Services hasta que el usuario realmente comparte.
    private val client: ConnectionsClient? by lazy {
        runCatching { Nearby.getConnectionsClient(appContext) }
            .onFailure { MoonLog.e(TAG, "Nearby Connections no disponible", it) }
            .getOrNull()
    }

    @Volatile var advertising: Boolean = false
        private set

    @Volatile var discovering: Boolean = false
        private set

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val (name, nonce) = decodeEndpointInfo(info.endpointName)
            events.onConnectionInitiated(endpointId, name, nonce, info.isIncomingConnection)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                events.onConnectionEstablished(endpointId)
            } else {
                events.onConnectionRejected(endpointId)
                events.onOperationFailed(
                    "conectar",
                    "El otro teléfono no completó la conexión (${resolution.status.statusCode}).",
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            events.onDisconnected(endpointId)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val (name, nonce) = decodeEndpointInfo(info.endpointName)
            events.onEndpointFound(NearbyDevice(endpointId, name, nonce))
        }

        override fun onEndpointLost(endpointId: String) {
            events.onEndpointLost(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            events.onPayloadReceived(endpointId, payload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            events.onPayloadUpdate(endpointId, update)
        }
    }

    fun startAdvertising(localName: String, sessionNonce: String) {
        val c = requireClient("activar la disponibilidad") ?: return
        if (advertising) return
        c.startAdvertising(
            encodeEndpointInfo(localName, sessionNonce),
            SERVICE_ID,
            lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnSuccessListener {
            advertising = true
            events.onAdvertisingStarted()
        }.addOnFailureListener { error ->
            advertising = false
            events.onOperationFailed("activar la disponibilidad", readableError(error))
        }
    }

    fun stopAdvertising() {
        advertising = false
        runCatching { client?.stopAdvertising() }
    }

    fun startDiscovery() {
        val c = requireClient("buscar teléfonos cercanos") ?: return
        if (discovering) return
        c.startDiscovery(
            SERVICE_ID,
            discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnSuccessListener {
            discovering = true
            events.onDiscoveryStarted()
        }.addOnFailureListener { error ->
            discovering = false
            events.onOperationFailed("buscar teléfonos cercanos", readableError(error))
        }
    }

    fun stopDiscovery() {
        discovering = false
        runCatching { client?.stopDiscovery() }
    }

    fun requestConnection(endpointId: String, localName: String, tapNonce: String) {
        val c = requireClient("conectar con el otro teléfono") ?: return
        stopDiscovery()
        c.requestConnection(
            encodeEndpointInfo(localName, tapNonce),
            endpointId,
            lifecycle,
        ).addOnFailureListener { error ->
            events.onOperationFailed("conectar con el otro teléfono", readableError(error))
        }
    }

    fun acceptConnection(endpointId: String) {
        val c = requireClient("aceptar la conexión") ?: return
        c.acceptConnection(endpointId, payloads)
            .addOnFailureListener { error -> events.onOperationFailed("aceptar la conexión", readableError(error)) }
    }

    fun rejectConnection(endpointId: String) {
        runCatching { client?.rejectConnection(endpointId) }
    }

    fun sendBytes(endpointId: String, text: String) {
        val c = requireClient("enviar el mensaje de control") ?: return
        c.sendPayload(endpointId, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { error -> events.onOperationFailed("enviar el mensaje de control", readableError(error)) }
    }

    fun sendPayload(endpointId: String, payload: Payload) {
        val c = requireClient("enviar el archivo") ?: return
        c.sendPayload(endpointId, payload)
            .addOnFailureListener { error -> events.onOperationFailed("enviar el archivo", readableError(error)) }
    }

    fun disconnect(endpointId: String?) {
        if (endpointId.isNullOrBlank()) return
        runCatching { client?.disconnectFromEndpoint(endpointId) }
    }

    fun stopAll() {
        advertising = false
        discovering = false
        runCatching { client?.stopAllEndpoints() }
        runCatching { client?.stopAdvertising() }
        runCatching { client?.stopDiscovery() }
    }

    private fun requireClient(operation: String): ConnectionsClient? {
        val c = client
        if (c == null) {
            events.onOperationFailed(
                operation,
                "Nearby Connections no está disponible en este teléfono. MoonShare necesita los servicios de Google para compartir sin configurar una red.",
            )
        }
        return c
    }

    private fun readableError(error: Exception): String {
        val api = error as? com.google.android.gms.common.api.ApiException
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return if (api != null) "$detail (${api.statusCode})" else detail
    }

    companion object {
        private const val TAG = "Nearby"
        const val SERVICE_ID = "com.moonshare.SHARE"

        /** Formato compacto del nombre visible: MS1 + nombre + nonce efímero. */
        fun encodeEndpointInfo(name: String, nonce: String): String {
            val cleanName = name.replace('|', ' ').trim().ifBlank { "Teléfono" }.take(20)
            return "MS1|$cleanName|${nonce.take(64)}"
        }

        fun decodeEndpointInfo(raw: String?): Pair<String, String> {
            if (raw.isNullOrBlank()) return "Teléfono" to ""
            val parts = raw.split('|', limit = 3)
            return if (parts.size == 3 && parts[0] == "MS1") {
                parts[1].ifBlank { "Teléfono" } to parts[2]
            } else {
                raw to ""
            }
        }
    }
}
