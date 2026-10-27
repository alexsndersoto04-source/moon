package com.moonshare.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.moonshare.MoonApplication
import com.moonshare.core.MoonLog
import com.moonshare.util.Notifx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene vivo el [TransferEngine] mientras hay una
 * transferencia en curso o el teléfono está "abierto a recibir".
 *
 * Sin esto, Android 12+ mata los sockets y MIUI corta Wi-Fi Direct en cuanto la
 * Activity pasa a segundo plano. Es el precio de la promesa "paso y ya está":
 * la app necesita un ancla de proceso.
 */
class TransferService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observe: Job? = null

    /**
     * El contrato de `startForegroundService()` no es negociable: el servicio tiene que
     * llamar a `startForeground()` dentro de ~5 s **por cada** arranque, en cualquier
     * rama, aunque ese arranque termine en `stopSelf()` inmediato. Si no, el sistema no
     * lanza la excepción dentro del servicio: la inyecta en el `Looper` del proceso
     * (`ActivityThread$H`) y la app muere con
     * `RemoteServiceException: Context.startForegroundService() did not then call
     * Service.startForeground()`. Ese era el cierre en el Redmi: la pantalla llegaba a
     * dibujar y ~5 s después se cortaba.
     */
    @Volatile private var foregrounded = false

    /** Expuesto para el test de regresion: el contrato tiene que estar cumplido tras onCreate. */
    internal val isForegroundRequested: Boolean get() = foregrounded

    /** Puede ser null si el motor no arranco: el servicio entonces no hace nada. */
    private val engine get() = (application as MoonApplication).engineOrNull()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Notifx.ensureChannels(this)
        // ANTES de cualquier otra cosa, y no en onStartCommand: `onCreate` corre una sola
        // vez por creación del proceso pero `onStartCommand` puede llegar con intents que
        // no piden nada (ACTION_ACCEPT/CANCEL desde la notificación) y ahí el reloj de 5 s
        // ya estaría corriendo.
        goForeground()
        observe = scope.launch {
            // Cada cambio de estado re-pinta la notificación: el usuario ve MB y
            // velocidad sin tener que abrir la app.
            engine?.state?.collectLatest { state ->
                if (state.busy || state.isHosting) {
                    runCatching {
                        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                            .notify(Notifx.ID_TRANSFER, Notifx.transferNotification(this@TransferService, state))
                    }
                } else {
                    // Ojo con la prisa: la primerísima emisión del StateFlow es Idle
                    // (el motor todavía no procesó ACTION_START). Cortar ahí deja al
                    // servicio sin foreground y arma el RemoteServiceException. Se espera
                    // un latido y se re-lee el estado antes de rendirse.
                    kotlinx.coroutines.delay(2_500)
                    val now = engine?.state?.value
                    if (now == null || (!now.busy && !now.isHosting)) stopSelfSafely()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotente (vease `foregrounded`): cubre el caso de servicio resucitado por
        // START_STICKY, donde onCreate no vuelve a correr pero el sistema si espera el
        // startForeground() de este arranque.
        goForeground()
        when (intent?.action) {
            ACTION_START -> {
                setAvailabilityEnabled(true)
                engine?.startReceiving()
            }

            // Solo mantener vivo el proceso durante un envío. NO debe ponerse a
            // recibir ni crear un grupo propio: eso competía con el grupo del receptor.
            ACTION_ENSURE -> Unit

            // Android puede resucitar un servicio START_STICKY con intent null. Si la
            // persona había dejado la disponibilidad encendida, se restaura.
            null -> if (isAvailabilityEnabled()) engine?.startReceiving()

            ACTION_STOP -> {
                setAvailabilityEnabled(false)
                engine?.stopReceiving()
                stopSelfSafely()
            }

            ACTION_ACCEPT -> engine?.respondToOffer(accept = true)
            ACTION_DECLINE -> engine?.respondToOffer(accept = false)
            ACTION_CANCEL -> engine?.cancelAll()
            else -> goForeground()
        }
        // START_STICKY: si el sistema se come el proceso por memoria, Android lo
        // resucita y volvemos a quedar "escuchando". El usuario no tiene que re-abrir.
        return START_STICKY
    }

    private fun goForeground() {
        if (foregrounded) return
        // Si construir la notificacion de progreso falla (canal borrado a mano desde
        // Ajustes de MIUI, por ejemplo) se usa una minima: quedandonos sin notificacion
        // el sistema no nos avisa, nos mata.
        val notification = runCatching {
            Notifx.transferNotification(this, engine?.state?.value ?: EngineState())
        }.getOrElse {
            MoonLog.w(TAG, "notificacion de progreso no se pudo armar: ${it.message}")
            Notifx.plainNotification(this)
        }
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        var ok = runCatching {
            ServiceCompat.startForeground(this, Notifx.ID_TRANSFER, notification, type)
        }.isSuccess
        if (!ok) {
            // Android 14+ puede rechazar startForeground() si falta el permiso de
            // notificaciones: la transferencia igual sigue, solo perdemos el progreso visible.
            MoonLog.w(TAG, "startForeground con tipo rechazado, reintentando sin tipo")
            ok = runCatching { startForeground(Notifx.ID_TRANSFER, notification) }.isSuccess
        }
        // Y si aun asi no se pudo: se corta el servicio DESPUES de haber llamado a
        // startForeground, o con la notificacion minima. Nunca dejar el contrato
        // pendiente, que es lo que cerraba la app.
        if (!ok) {
            MoonLog.e(TAG, "startForeground imposible: el servicio se corta antes de matar la app")
            runCatching { startForeground(Notifx.ID_TRANSFER, Notifx.plainNotification(this)) }
            runCatching { stopSelf() }
            return
        }
        foregrounded = true
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moonshare:transfer").apply {
            setReferenceCounted(false)
            // techo duro: 2 h. Sin esto, un bug de "nunca termina" drenaría la batería del usuario.
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun setAvailabilityEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AVAILABLE, enabled).apply()
    }

    private fun isAvailabilityEnabled(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AVAILABLE, false)

    private fun stopSelfSafely() {
        // Cortarse sin haber hecho foreground es la violacion exacta del contrato: el
        // sistema igual espera su startForeground(). Se satisface primero y se corta despues.
        if (!foregrounded) runCatching { goForeground() }
        releaseWakeLock()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(Notifx.ID_TRANSFER)
        }
        stopSelf()
    }

    override fun onDestroy() {
        observe?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Se lo invoca desde el ViewModel cuando empieza un envío. Idempotente. */
    fun ensureRunning() = goForeground()

    companion object {
        private const val TAG = "Svc"
        private const val PREFS = "moon_availability"
        private const val KEY_AVAILABLE = "enabled"
        const val ACTION_START = "com.moonshare.action.START"
        const val ACTION_ENSURE = "com.moonshare.action.ENSURE"
        const val ACTION_STOP = "com.moonshare.action.STOP"
        const val ACTION_ACCEPT = "com.moonshare.action.ACCEPT"
        const val ACTION_DECLINE = "com.moonshare.action.DECLINE"
        const val ACTION_CANCEL = "com.moonshare.action.CANCEL"

        fun start(context: Context) {
            val i = Intent(context, TransferService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }.onFailure { MoonLog.w(TAG, "no se pudo levantar el servicio: ${it.message}") }
        }

        /** Mantiene vivo un envío sin activar la recepción en este teléfono. */
        fun ensureForSending(context: Context) {
            val i = Intent(context, TransferService::class.java).setAction(ACTION_ENSURE)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }.onFailure { MoonLog.w(TAG, "no se pudo asegurar el servicio de envío: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP))
            }.onFailure {
                // Con el servicio ya muerto, stopService no tiene efecto: se avisa nada mas.
                MoonLog.w(TAG, "stop con servicio caido: ${it.message}")
            }
        }

        /**
         * Acciones que no mueven datos (aceptar/rechazar/cancelar): van con
         * `startService()`. Pedirlas con `startForegroundService()` arma el reloj de 5 s
         * para un servicio que no tiene nada que mostrar en primer plano, y ese es el
         * RemoteServiceException que se veia. Si el servicio ya esta corriendo, el intent
         * llega igual; si el proceso murio, `IllegalStateException` se atrapa y la accion
         * se pierde (mejor eso que cerrar la app).
         */
        fun sendAction(context: Context, action: String) {
            val i = Intent(context, TransferService::class.java).setAction(action)
            runCatching { context.startService(i) }
                .onFailure { MoonLog.w(TAG, "accion $action sin servicio vivo: ${it.message}") }
        }
    }
}
