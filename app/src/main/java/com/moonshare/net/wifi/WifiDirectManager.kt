package com.moonshare.net.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.moonshare.core.MoonLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wrapper sobre [WifiP2pManager] con la política de MoonShare:
 *
 *  - El teléfono que **recibe** levanta el grupo: es el *Group Owner*, y su IP en el
 *    enlace punto a punto es siempre 192.168.49.1. Fijar ese rol a propósito (en vez
 *    de dejar que la negociación WPS decida) es lo que hace que el que envía sepa de
 *    antemano a qué dirección conectarse.
 *  - El teléfono que **envía** descubre, hace `connect()` con WPS‑PBC y entra al grupo
 *    sin que el usuario toque el diálogo de confirmación del sistema.
 *
 * Android no permite usar Wi-Fi Direct desde segundo plano (12+) ni sin el permiso de
 * *dispositivos cercanos* (13+). Eso no se saltea; lo que sí se elimina es la fricción
 * de UI: un golpe de NFC reemplaza la lista de dispositivos, la selección y el "¿aceitás?".
 */
@Suppress("DEPRECATION") // initialize/requestPeers/…: deprecados pero vigentes desde API 14
class WifiDirectManager(private val context: Context) {

    private val tag = "WFD"
    private val appContext = context.applicationContext

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _thisDevice = MutableStateFlow<WifiP2pDevice?>(null)
    val thisDevice: StateFlow<WifiP2pDevice?> = _thisDevice.asStateFlow()

    private val _group = MutableStateFlow<WifiP2pGroup?>(null)
    /** Grupo activo (ssid + passphrase), solo cuando este teléfono es el GO. */
    val group: StateFlow<WifiP2pGroup?> = _group.asStateFlow()

    private val _connection = MutableStateFlow<WifiP2pInfo?>(null)
    val connection: StateFlow<WifiP2pInfo?> = _connection.asStateFlow()

    private val _radioOn = MutableStateFlow(false)
    val radioOn: StateFlow<Boolean> = _radioOn.asStateFlow()

    private val _events = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Avisos de radio para la UI, sin acoplar este archivo al ViewModel. */
    val events: Flow<String> = _events.asSharedFlow()

    val isSupported: Boolean
        get() = appContext.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    // ---------------------------------------------------------------- lifecycle
    /** @return true si el canal quedó usable. */
    fun open(): Boolean {
        if (channel != null) return true
        val mgr = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            MoonLog.w(tag, "WIFI_P2P_SERVICE no disponible en este equipo")
            _events.tryEmit("Este teléfono no soporta Wi-Fi Direct")
            return false
        }
        val ch = try {
            mgr.initialize(appContext, android.os.Looper.getMainLooper())
        } catch (e: Exception) {
            MoonLog.e(tag, "initialize falló", e)
            _events.tryEmit("No se pudo abrir el canal de Wi-Fi Direct: ${e.message}")
            null
        }
        if (ch == null) {
            _events.tryEmit("No se pudo abrir el canal de Wi-Fi Direct")
            return false
        }
        manager = mgr
        channel = ch
        if (!registered) {
            try {
                appContext.registerReceiver(receiver, intentFilter())
                registered = true
            } catch (e: Exception) {
                MoonLog.w(tag, "registerReceiver: ${e.message}")
            }
        }
        MoonLog.i(tag, "canal WFD abierto")
        return true
    }

    fun close() {
        if (registered) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            registered = false
        }
        removeGroup(silent = true)
        try {
            channel?.close()
        } catch (_: Exception) {
        }
        manager = null
        channel = null
        _peers.value = emptyList()
        _group.value = null
        _connection.value = null
    }

    private fun intentFilter() = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    )
                    _radioOn.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> connectionListener()?.let { (m, c) ->
                    m.requestConnectionInfo(c, object : WifiP2pManager.ConnectionInfoListener {
                        override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
                            if (info == null) return
                            _connection.value = info
                            if (info.groupFormed) {
                                MoonLog.i(
                                    tag,
                                    "grupo formado go=${info.isGroupOwner} ip=${info.groupOwnerAddress}",
                                )
                            }
                        }
                    })
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> connectionListener()?.let { (m, c) ->
                    m.requestThisDevice(c, object : WifiP2pManager.ThisDeviceListener {
                        override fun onThisDeviceUpdated(device: WifiP2pDevice?) {
                            _thisDevice.value = device
                        }
                    })
                }
            }
        }
    }

    /** Par (manager, channel) solo cuando ambos están vivos. */
    private fun connectionListener(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m = manager ?: return null
        val c = channel ?: return null
        return m to c
    }

    // ---------------------------------------------------------------- acciones
    fun discover() {
        val (m, c) = connectionListener() ?: return
        _peers.value = emptyList()
        try {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit

                override fun onFailure(reason: Int) {
                    MoonLog.w(tag, "discoverPeers fallo reason=$reason")
                    _events.tryEmit(describeReason(reason))
                }
            })
        } catch (e: Exception) {
            _events.tryEmit("Wi-Fi Direct no está disponible: ${e.message}")
        }
    }

    fun stopDiscovery() {
        val (m, c) = connectionListener() ?: return
        try {
            m.stopPeerDiscovery(c, null)
        } catch (_: Exception) {
        }
    }

    private fun requestPeers() {
        val (m, c) = connectionListener() ?: return
        try {
            m.requestPeers(c, object : WifiP2pManager.PeerListListener {
                override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
                    val devices = peers?.deviceList?.toList().orEmpty()
                        .sortedWith(
                            compareByDescending<WifiP2pDevice> { it.status == WifiP2pDevice.CONNECTED }
                                .thenBy { it.deviceName?.lowercase() ?: "" },
                        )
                    if (devices != _peers.value) _peers.value = devices
                }
            })
        } catch (e: Exception) {
            MoonLog.w(tag, "requestPeers: ${e.message}")
        }
    }

    /** Levanta el grupo P2P (este teléfono pasa a ser el GO y escucha). */
    fun startGroup(prefer5GHz: Boolean, onReady: (WifiP2pGroup?) -> Unit) {
        val pair = connectionListener()
        if (pair == null) {
            onReady(null)
            return
        }
        val (m, c) = pair
        _group.value?.let { existing ->
            onReady(existing)
            return
        }
        val config = WifiP2pConfig().apply {
            // Intent alto: este teléfono gana la elección de Group Owner.
            groupOwnerIntent = 15
            wps.setup = WpsInfo.PBC
            if (prefer5GHz) applyBandPreference(this)
        }
        try {
            m.createGroup(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    MoonLog.i(tag, "grupo creado (GO)")
                    m.requestGroupInfo(c, object : WifiP2pManager.GroupInfoListener {
                        override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
                            _group.value = group
                            // Ventana WPS abierta ~2 min: el otro entra sin diálogo.
                            try {
                                m.startWps(
                                    c,
                                    WifiP2pManager.WPS_DISPLAY,
                                    object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() = Unit
                                        override fun onFailure(reason: Int) {
                                            MoonLog.d(tag, "startWps reason=$reason")
                                        }
                                    },
                                )
                            } catch (e: Exception) {
                                MoonLog.d(tag, "startWps no disponible: ${e.message}")
                            }
                            onReady(group)
                        }
                    })
                }

                override fun onFailure(reason: Int) {
                    MoonLog.w(tag, "createGroup fallo reason=$reason")
                    _events.tryEmit(describeReason(reason))
                    onReady(null)
                }
            })
        } catch (e: Exception) {
            MoonLog.e(tag, "createGroup reventó", e)
            _events.tryEmit("No se pudo crear el grupo: ${e.message}")
            onReady(null)
        }
    }

    fun removeGroup(silent: Boolean = false) {
        val (m, c) = connectionListener() ?: return
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _group.value = null
                    if (!silent) MoonLog.i(tag, "grupo eliminado")
                }

                override fun onFailure(reason: Int) {
                    if (!silent) MoonLog.w(tag, "removeGroup reason=$reason")
                }
            })
        } catch (e: Exception) {
            if (!silent) MoonLog.w(tag, "removeGroup: ${e.message}")
        }
    }

    /**
     * Se une al grupo de otro teléfono con WPS‑PBC.
     *
     * [onResult] llega cuando el *request* se aceptó o rechazó, no cuando el enlace
     * está listo: para el enlace hay que esperar con [awaitLink]. Confundir esos dos
     * momentos es el bug clásico de esta API.
     */
    fun join(deviceAddress: String, onResult: (Boolean, String?) -> Unit) {
        val pair = connectionListener()
        if (pair == null) {
            onResult(false, "Canal de Wi-Fi Direct cerrado")
            return
        }
        val (m, c) = pair
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // que el GO sea el otro
        }
        try {
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    MoonLog.i(tag, "connect() aceptado, esperando enlace")
                    onResult(true, null)
                }

                override fun onFailure(reason: Int) {
                    val msg = describeReason(reason)
                    MoonLog.w(tag, "connect fallo: $msg")
                    onResult(false, msg)
                }
            })
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }

    fun cancelConnect() {
        val (m, c) = connectionListener() ?: return
        try {
            m.cancelConnect(c, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Suspende hasta que exista un enlace P2P formado (o null si expira). El timeout
     * cubre el peor caso real de negociación WPS en hardware modesto
     * (Redmi 9C ≈ 10-15 s).
     */
    suspend fun awaitLink(timeoutMs: Long = 30_000): WifiP2pInfo? =
        withTimeoutOrNull(timeoutMs) {
            connection.first { it != null && it.groupFormed }
        }

    // ------------------------------------------------------------------ utils
    /**
     * Intenta fijar 5 GHz. Los setters de banda cambiaron entre API 30 y 34 y parte
     * están ocultos o deprecados según la versión, así que se resuelven por reflexión
     * y se degradan a "que elija la radio" si no están. Nunca rompe el build ni el
     * runtime, y se nota en logs.
     */
    private fun applyBandPreference(config: WifiP2pConfig) {
        if (Build.VERSION.SDK_INT >= 33) {
            val done = try {
                // WifiP2pConfig.BAND_5_GHZ == 2 en API 33+
                config.javaClass
                    .getMethod("setGroupOperatingBand", Int::class.javaPrimitiveType)
                    .invoke(config, 2)
                true
            } catch (e: Exception) {
                false
            }
            if (done) return
        }
        try {
            // Fallback clásico: frecuencia explícita (canal 36 = 5180 MHz)
            config.javaClass.getField("groupOperatingFrequency").setInt(config, 5180)
        } catch (e: Exception) {
            MoonLog.d(tag, "banda 5GHz no fijable acá; la elige la radio")
        }
    }

    /** Códigos de fallo de WifiP2pManager. Se comparan por valor para no depender de
     *  constantes que no todas las versiones exponen igual. */
    private fun describeReason(reason: Int): String = when (reason) {
        ERROR -> "Error interno de Wi-Fi Direct"
        PENDING -> "La operación anterior sigue pendiente"
        BUSY -> "El otro teléfono está ocupado"
        NO_SERVICE -> "Servicio de Wi-Fi Direct no registrado"
        else -> "Wi-Fi Direct rechazó la operación (reason=$reason)"
    }

    private companion object {
        const val ERROR = 0
        const val PENDING = 1
        const val BUSY = 2
        const val NO_SERVICE = 3
    }
}
