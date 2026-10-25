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

    private val engine get() = (application as MoonApplication).engine

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Notifx.ensureChannels(this)
        observe = scope.launch {
            // Cada cambio de estado re-pinta la notificación: el usuario ve MB y
            // velocidad sin tener que abrir la app.
            engine.state.collectLatest { state ->
                if (state.busy || state.isHosting) {
                    runCatching {
                        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                            .notify(Notifx.ID_TRANSFER, Notifx.transferNotification(this@TransferService, state))
                    }
                } else {
                    stopSelfSafely()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                engine.startReceiving()
            }

            ACTION_STOP -> {
                engine.stopReceiving()
                stopSelfSafely()
            }

            ACTION_ACCEPT -> engine.respondToOffer(accept = true)
            ACTION_DECLINE -> engine.respondToOffer(accept = false)
            ACTION_CANCEL -> engine.cancelAll()
            else -> goForeground()
        }
        // START_STICKY: si el sistema se come el proceso por memoria, Android lo
        // resucita y volvemos a quedar "escuchando". El usuario no tiene que re-abrir.
        return START_STICKY
    }

    private fun goForeground() {
        val notification = Notifx.transferNotification(this, engine.state.value)
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, Notifx.ID_TRANSFER, notification, type)
        } catch (e: Exception) {
            // Android 14+ puede rechazar startForeground() si no hay permiso de
            // notificaciones: la transferencia igual sigue, solo perdemos el progreso visible.
            MoonLog.w(TAG, "startForeground rechazado: ${e.message}")
            runCatching { startForeground(Notifx.ID_TRANSFER, notification) }
        }
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

    private fun stopSelfSafely() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

    /** Se lo invoca desde el ViewModel cuando empieza un envío. */
    fun ensureRunning() = goForeground()

    companion object {
        private const val TAG = "Svc"
        const val ACTION_START = "com.moonshare.action.START"
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

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP))
            }
        }

        fun sendAction(context: Context, action: String) {
            val i = Intent(context, TransferService::class.java).setAction(action)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }
    }
}
