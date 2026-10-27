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

    @Volatile
    var lastError: String? = null
        private set

    val isSupported: Boolean
        get() = appContext.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    private fun reportError(message: String) {
        lastError = message
        _events.tryEmit(message)
    }

    /** Causa concreta por la que este teléfono no puede usar Wi-Fi Direct ahora. */
    fun preflightError(): String? = when {
        !isSupported -> "Este teléfono no trae Wi-Fi Direct."
        !wifiEnabled() -> "El Wi-Fi está apagado. Encendelo y volvé a intentar."
        !hasNearbyPermission() ->
            if (Build.VERSION.SDK_INT >= 33) {
                "Falta el permiso de Dispositivos cercanos para Wi-Fi Direct."
            } else {
                "Falta el permiso de Ubicación para Wi-Fi Direct. Android 10 a 12 lo exige aunque MoonShare no te ubique."
            }
        else -> null
    }

    private fun wifiEnabled(): Boolean = runCatching {
        (appContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.isWifiEnabled == true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- lifecycle
    /** @return true si el canal quedó usable. */
    fun open(): Boolean {
        if (channel != null) return true
        if (!isSupported) {
            // No es un error del usuario: el hardware no lo trae. Se avisa y la app
            // sigue sirviendo para recibir por red local si el SO lo permite.
            reportError("Este teléfono no trae Wi-Fi Direct")
            return false
        }
        val mgr = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            MoonLog.w(tag, "WIFI_P2P_SERVICE no disponible en este equipo")
            reportError("El sistema no expone Wi-Fi Direct en este teléfono")
            return false
        }
        // Precondición que en Android NADIE te avisa: sin el Wi-Fi encendido,
        // initialize() devuelve canal pero discover() nunca encuentra a nadie.
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifi != null && !wifi.isWifiEnabled) {
            reportError("El Wi-Fi está apagado. Encendelo y volvé a intentar.")
            return false
        }
        val ch = acquireChannel(mgr)
        if (ch == null) {
            reportError(
                "Android no abrió el canal de Wi-Fi Direct. Revisá el permiso de Dispositivos cercanos o Ubicación.",
            )
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

    /**
     * `WifiP2pManager.initialize` tiene 3 sobrecargas según el nivel de API y la de
     * dos argumentos —la única que sirve en Android 10 a 12— está deprecada y en los
     * stubs recientes se resuelve ambigua contra la forma con listener. Para no
     * depender de qué SDK esté compilando ni romper Android 10 en runtime, el método
     * se busca por reflexión: funciona en 29, 33, 34 y 35 sin condiciones.
     */
    /**
     * Registro ante el framework. La firma es la pública de siempre, documentada desde
     * API 14: `initialize(Context, Looper, ChannelListener)` y **devuelve** el canal.
     *
     * Acá estaba el bug que rompía todo en el Redmi: se buscaba por reflejo una
     * sobrecarga de dos argumentos (inexistente) y, en el rebote, se esperaba el canal
     * como parámetro de un callback. `initialize` devuelve el Channel; nadie lo pasa por
     * el listener. Resultado: null garantizado, "No se pudo abrir el canal de Wi-Fi
     * Direct", y una app que no puede transferir nada. Se reemplaza por la llamada real.
     */
    @Suppress("DEPRECATION")
    private fun acquireChannel(mgr: WifiP2pManager): WifiP2pManager.Channel? = runCatching {
        mgr.initialize(
            appContext,
            android.os.Looper.getMainLooper(),
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // El framework nos cerró el canal (se apagó el Wi-Fi, cambió el
                    // usuario, MIUI reciclando). Sin esto quedaríamos con un channel
                    // muerto y TODAS las operaciones fallarían en silencio para siempre.
                    MoonLog.w(tag, "canal WFD perdido: se va a re-abrir al próximo uso")
                    channel = null
                    manager = null
                }
            },
        )
    }.onFailure {
        MoonLog.e(tag, "initialize falló: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()


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

                // El dispositivo local NO se pide: viene en el intent del broadcast.
                // (WifiP2pManager no expone un requestThisDevice(); el extra es la vía.)
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<WifiP2pDevice>(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                    )
                    if (dev != null) _thisDevice.value = dev
                }
            }
        }
    }

    private fun hasNearbyPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun locationServicesOn(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) true else runCatching {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            // Si el sistema no expone el servicio, no lo hacemos bloqueante: que intente.
            lm == null || lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)

    /** true si el telefono puede, de verdad, usar Wi-Fi Direct ahora mismo. */
    fun radioReady(): Boolean = isSupported && runCatching {
        (appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.isWifiEnabled != false
    }.getOrDefault(true) && hasNearbyPermission()

    /** Par (manager, channel) solo cuando ambos están vivos. */
    private fun connectionListener(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m = manager ?: return null
        val c = channel ?: return null
        return m to c
    }

    // ---------------------------------------------------------------- acciones
    fun discover() {
        val (m, c) = connectionListener() ?: return
        // Android 10-12: no alcanza con el permiso, los SERVICIOS de ubicación tienen
        // que estar encendidos o discoverPeers() responde onSuccess con lista vacia.
        // Es la causa #1 de "no encuentra a nadie" en estos ROMs y el sistema no avisa.
        if (!locationServicesOn()) {
            reportError("Activá la Ubicación del sistema: Android 10 a 12 la exige para descubrir teléfonos por Wi-Fi Direct.")
            return
        }
        // Android 10..12 no devuelve pares si falta ACCESS_FINE_LOCATION, y lo hace en
        // silencio: discoverPeers() tiene onSuccess y la lista llega vacia. Se avisa
        // explicitamente porque si no, el usuario cree que no hay nadie cerca.
        if (!hasNearbyPermission()) {
            reportError("Falta el permiso de Ubicación para ver otros teléfonos. Android 10 a 12 lo exige para Wi-Fi Direct.")
            return
        }
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
    fun startGroup(
        @Suppress("UNUSED_PARAMETER") prefer5GHz: Boolean,
        onReady: (WifiP2pGroup?, String?) -> Unit,
    ) {
        preflightError()?.let { error ->
            reportError(error)
            onReady(null, error)
            return
        }
        val pair = connectionListener()
        if (pair == null) {
            val error = "Android no abrió el canal de Wi-Fi Direct."
            reportError(error)
            onReady(null, error)
            return
        }
        val (m, c) = pair
        _group.value?.let { existing ->
            onReady(existing, null)
            return
        }

        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(group: WifiP2pGroup?, error: String?) {
            if (finished.compareAndSet(false, true)) onReady(group, error)
        }

        // Si Android dejó un grupo formado de un intento/proceso anterior, se reutiliza.
        // Antes se llamaba createGroup() igual y muchos equipos responden BUSY/ERROR.
        try {
            m.requestGroupInfo(c, object : WifiP2pManager.GroupInfoListener {
                override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
                    if (group != null) {
                        _group.value = group
                        MoonLog.i(tag, "se reutiliza el grupo Wi-Fi Direct ya formado")
                        finish(group, null)
                    } else {
                        createGroupAttempt(m, c, retryWithoutConfig = true, finish = ::finish)
                    }
                }
            })
        } catch (e: Throwable) {
            MoonLog.w(tag, "requestGroupInfo antes de crear: ${e.message}")
            createGroupAttempt(m, c, retryWithoutConfig = true, finish = ::finish)
        }
    }

    private fun createGroupAttempt(
        m: WifiP2pManager,
        c: WifiP2pManager.Channel,
        retryWithoutConfig: Boolean,
        finish: (WifiP2pGroup?, String?) -> Unit,
    ) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                MoonLog.i(tag, "grupo Wi-Fi Direct creado")
                try {
                    m.requestGroupInfo(c, object : WifiP2pManager.GroupInfoListener {
                        override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
                            if (group != null) {
                                _group.value = group
                                finish(group, null)
                            } else {
                                finish(null, "Android creó el grupo, pero no devolvió sus datos. Apagá y encendé el Wi-Fi y reintentá.")
                            }
                        }
                    })
                } catch (e: Throwable) {
                    finish(null, "Android creó el grupo, pero no se pudo confirmar: ${e.message ?: "error desconocido"}")
                }
            }

            override fun onFailure(reason: Int) {
                val detail = describeReason(reason)
                MoonLog.w(tag, "createGroup falló reason=$reason ($detail), reintentoSinConfig=$retryWithoutConfig")
                if (retryWithoutConfig) {
                    // Segunda oportunidad sin WifiP2pConfig: elimina de la ecuación la
                    // banda, el intent de propietario y cualquier campo que una ROM
                    // concreta no acepte. Primero se limpia un posible grupo a medias.
                    removeGroup(silent = true)
                    createGroupAttempt(m, c, retryWithoutConfig = false, finish = finish)
                } else {
                    val error = "No se pudo activar Wi-Fi Direct: $detail"
                    reportError(error)
                    finish(null, error)
                }
            }
        }

        try {
            if (retryWithoutConfig) {
                // Configuración mínima. En particular NO se fuerza 5 GHz: un teléfono
                // solo 2,4 GHz (muy común) puede rechazar un grupo creado a 5180 MHz.
                val config = WifiP2pConfig().apply {
                    groupOwnerIntent = 15
                    wps.setup = WpsInfo.PBC
                }
                m.createGroup(c, config, listener)
            } else {
                @Suppress("DEPRECATION")
                m.createGroup(c, listener)
            }
        } catch (e: Throwable) {
            // NoSuchMethodError y SecurityException no son Exception en todos los casos.
            MoonLog.e(tag, "createGroup lanzó ${e.javaClass.simpleName}", e as? Exception)
            if (retryWithoutConfig) {
                createGroupAttempt(m, c, retryWithoutConfig = false, finish = finish)
            } else {
                val error = when (e) {
                    is SecurityException -> preflightError() ?: "Android negó el permiso para crear el grupo Wi-Fi Direct."
                    else -> "No se pudo activar Wi-Fi Direct: ${e.message ?: e.javaClass.simpleName}"
                }
                reportError(error)
                finish(null, error)
            }
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
    /** Códigos de fallo de WifiP2pManager. Se comparan por valor para no depender de
     *  constantes que no todas las versiones exponen igual. */
    private fun describeReason(reason: Int): String = when (reason) {
        ERROR -> "Android devolvió un error interno (reason=0). Suele ser un grupo anterior atascado o una restricción de la ROM."
        P2P_UNSUPPORTED -> "la ROM de este teléfono no admite Wi-Fi Direct (reason=1)."
        BUSY -> "Wi-Fi Direct está ocupado (reason=2). Puede haber un grupo anterior sin cerrar."
        else -> "Android rechazó la operación (reason=$reason)."
    }

    private companion object {
        const val ERROR = 0
        const val P2P_UNSUPPORTED = 1
        const val BUSY = 2
    }
}
