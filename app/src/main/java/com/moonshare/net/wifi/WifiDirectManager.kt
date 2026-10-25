package com.moonshare.net.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWpsInfo
import android.os.Build
import com.moonshare.core.MoonLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wrapper sobre [WifiP2pManager] con la política de MoonShare:
 *
 *  - El teléfono que **recibe** levanta el grupo (es el *Group Owner* → IP fija
 *    192.168.49.1). Eso hace la conexión determinística: el que envía nunca tiene
 *    que adivinar quién manda el grupo.
 *  - El teléfono que **envía** descubres, hace `connect()` con WPS‑PBC y entra al
 *    grupo sin que el usuario toque el diálogo del sistema.
 *
 * Wi-Fi Direct no se puede usar "sin permiso del sistema" (Android exige que la
 * radio esté encendida y, desde Android 13, el permiso de *dispositivos cercanos*).
 * Lo que sí eliminamos es la fricción: un golpe de NFC reemplaza la lista de
 * dispositivos, el tap y la confirmación.
 */
class WifiDirectManager(private val context: Context) {

    private val tag = "WFD"

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _thisDevice = MutableStateFlow<WifiP2pDevice?>(null)
    val thisDevice = _thisDevice.asStateFlow()

    private val _group = MutableStateFlow<WifiP2pGroup?>(null)
    /** El grupo activo (ssid + passphrase), solo si somos el GO. */
    val group = _group.asStateFlow()

    private val _connection = MutableStateFlow<WifiP2pInfo?>(null)
    val connection = _connection.asStateFlow()

    private val _radioOn = MutableStateFlow(false)
    val radioOn = _radioOn.asStateFlow()

    private val _events = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Errores y avisos de radio para mostrar en la UI sin tirarse de cabeza al ViewModel. */
    val events = _events.asSharedFlow()

    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    // ---------------------------------------------------------------- lifecycle
    fun open(): Boolean {
        if (channel != null) return true
        val mgr = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE)
            as? WifiP2pManager
        if (mgr == null) {
            MoonLog.w(tag, "WIFI_P2P_SERVICE no disponible en este equipo")
            _events.tryEmit("Este teléfono no soporta Wi-Fi Direct")
            return false
        }
        @Suppress("DEPRECATION")
        val ch = try {
            mgr.initialize(context.applicationContext, context.mainLooper, null)
        } catch (e: SecurityException) {
            MoonLog.e(tag, "initialize sin permiso de cercanos", e)
            _events.tryEmit("Falta el permiso de dispositivos cercanos")
            null
        } catch (e: Exception) {
            MoonLog.e(tag, "initialize fallo", e)
            null
        }
        if (ch == null) {
            _events.tryEmit("No se pudo abrir el canal de Wi-Fi Direct")
            return false
        }
        manager = mgr
        channel = ch
        if (!registered) {
            context.applicationContext.registerReceiver(receiver, intentFilter())
            registered = true
        }
        MoonLog.i(tag, "canal WFD abierto")
        return true
    }

    fun close() {
        if (registered) {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
            registered = false
        }
        removeGroup(silent = true)
        runCatching { channel?.close() }
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
                        WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    _radioOn.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val m = manager ?: return
                    val c = channel ?: return
                    runCatching {
                        m.requestConnectionInfo(c) { info: WifiP2pInfo? ->
                            _connection.value = info
                            if (info != null && info.groupFormed) {
                                MoonLog.i(
                                    tag, "grupo formado role=${info.isGroupOwner} " +
                                        "gc=${info.groupOwnerAddress}"
                                )
                            }
                        }
                    }.onFailure { MoonLog.w(tag, "requestConnectionInfo: ${it.message}") }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val m = manager ?: return
                    val c = channel ?: return
                    runCatching {
                        m.requestThisDevice(c) { dev -> _thisDevice.value = dev }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- acciones
    fun discover() {
        val m = manager ?: return
        val c = channel ?: return
        _peers.value = emptyList()
        runCatching {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) {
                    MoonLog.w(tag, "discoverPeers fallo reason=$reason")
                    _events.tryEmit(describeReason(reason))
                }
            })
        }.onFailure { _events.tryEmit("Wi-Fi Direct no está disponible: ${it.message}") }
    }

    fun stopDiscovery() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching { m.stopPeerDiscovery(c, null) }
    }

    private fun requestPeers() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching {
            m.requestPeers(c) { list: WifiP2pDeviceList? ->
                val devices = list?.deviceList?.toList().orEmpty()
                    // orden estable: conectados primero, luego por nombre
                    .sortedWith(
                        compareByDescending<WifiP2pDevice> { it.isConnected() }
                            .thenBy { it.deviceName.lowercase() }
                    )
                if (devices.size != _peers.value.size || devices != _peers.value) {
                    _peers.value = devices
                }
            }
        }.onFailure { MoonLog.w(tag, "requestPeers: ${it.message}") }
    }

    /** Levanta el grupo P2P (este teléfono pasa a ser el GO y escucha). */
    fun startGroup(prefer5GHz: Boolean, onReady: (WifiP2pGroup?) -> Unit) {
        val m = manager
        val c = channel
        if (m == null || c == null) {
            onReady(null)
            return
        }
        val existing = _group.value
        if (existing != null) {
            onReady(existing)
            return
        }
        val config = WifiP2pConfig().apply {
            // Intent alto = este teléfono gana la elección de GO.
            groupOwnerIntent = 15
            wps.setup = WifiP2pWpsInfo.PBC
            if (prefer5GHz) applyBandPreference(this)
        }
        @Suppress("DEPRECATION")
        runCatching {
            m.createGroup(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    MoonLog.i(tag, "grupo creado (GO)")
                    m.requestGroupInfo(c) { g ->
                        _group.value = g
                        // Ventana PBC abierta: el otro entra sin diálogo de confirmación.
                        runCatching { m.startWps(c, WifiP2pManager.WPS_DISPLAY, null) }
                        onReady(g)
                    }
                }

                override fun onFailure(reason: Int) {
                    MoonLog.w(tag, "createGroup fallo reason=$reason")
                    _events.tryEmit(describeReason(reason))
                    onReady(null)
                }
            })
        }.onFailure {
            _events.tryEmit("No se pudo crear el grupo: ${it.message}")
            onReady(null)
        }
    }

    fun removeGroup(silent: Boolean = false) {
        val m = manager ?: return
        val c = channel ?: return
        runCatching {
            @Suppress("DEPRECATION")
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _group.value = null
                    if (!silent) MoonLog.i(tag, "grupo eliminado")
                }

                override fun onFailure(reason: Int) {
                    if (!silent) MoonLog.w(tag, "removeGroup reason=$reason")
                }
            })
        }
    }

    /** Se une al grupo de otro teléfono usando WPS‑PBC (sin intervención del usuario). */
    fun join(deviceAddress: String, onResult: (Boolean, String?) -> Unit) {
        val m = manager
        val c = channel
        if (m == null || c == null) {
            onResult(false, "Canal de Wi-Fi Direct cerrado")
            return
        }
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WifiP2pWpsInfo.PBC
            groupOwnerIntent = 0 // que el otro sea el GO
        }
        runCatching {
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    MoonLog.i(tag, "connect() aceptado, esperando enlace")
                }

                override fun onFailure(reason: Int) {
                    val msg = describeReason(reason)
                    MoonLog.w(tag, "connect fallo: $msg")
                    onResult(false, msg)
                }
            })
        }.onFailure { onResult(false, it.message) }
    }

    fun cancelConnect() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching { m.cancelConnect(c, null) }
    }

    /**
     * Suspend hasta que exista un enlace P2P formado (o null si expira). El timeout
     * cubre el peor caso real de negociación WPS en hardware modesto
     * (Redmi 9C ≈ 10-15 s).
     */
    suspend fun awaitLink(timeoutMs: Long = 30_000): WifiP2pInfo? =
        withTimeoutOrNull(timeoutMs) {
            connection.first { it != null && it.groupFormed }
        }

    // ------------------------------------------------------------------ utils
    /**
     * Intenta fijar 5 GHz. Los setters de banda cambiaron entre API 30 y 34 y
     * algunos están ocultos o deprecados según la versión, así que se resuelven
     * por reflexión y se degradan a "que elija la radio" si no está disponible.
     * Nunca rompe el build ni el runtime.
     */
    private fun applyBandPreference(config: WifiP2pConfig) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                // WifiP2pConfig.BAND_5_GHZ == 2 en API 33+
                config.javaClass.getMethod("setGroupOperatingBand", Int::class.javaPrimitiveType)
                    .invoke(config, 2)
                return
            }
        }
        runCatching {
            // Fallback clásico: campo público groupOperatingFrequency (canal 36 = 5180 MHz)
            config.javaClass.getField("groupOperatingFrequency").setInt(config, 5180)
        }.onFailure {
            MoonLog.d(tag, "banda 5GHz no fijable aquí, la elige la radio")
        }
    }

    private fun WifiP2pDevice.isConnected() =
        status == WifiP2pDevice.CONNECTED

    private fun describeReason(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "Error interno de Wi-Fi Direct"
        WifiP2pManager.PENDING -> "La operación anterior sigue pendiente"
        WifiP2pManager.BUSY -> "El otro teléfono está ocupado"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "Servicio de Wi-Fi Direct no registrado"
        0xFFFFFFFE -> "Permiso o estado de radio insuficiente"
        else -> "Wi-Fi Direct rechazó la operación (reason=$reason)"
    }
}
