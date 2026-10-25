package com.moonshare.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.moonshare.MainActivity
import com.moonshare.R
import com.moonshare.core.MoonLog
import com.moonshare.data.model.TransferDirection
import com.moonshare.data.model.TransferTask
import com.moonshare.transfer.EngineState

/**
 * Notificaciones. El progreso tiene que vivir afuera de la Activity por dos razones:
 * la transferencia se ve aunque se minimice la app, y Android 12+ exige un servicio
 * en primer plano para mantener Wi-Fi Direct y los sockets vivos.
 */
object Notifx {

    const val CHANNEL_TRANSFER = "moon_transfer"
    const val CHANNEL_REQUEST = "moon_request"
    const val ID_TRANSFER = 1001
    const val ID_REQUEST = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val silent = NotificationManager.IMPORTANCE_LOW
        mgr.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_TRANSFER, context.getString(R.string.notif_channel_transfer), silent).apply {
                    description = context.getString(R.string.notif_channel_transfer_desc)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                },
                NotificationChannel(
                    CHANNEL_REQUEST, context.getString(R.string.notif_channel_request),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_channel_request_desc)
                    enableVibration(true)
                },
            )
        )
    }

    fun transferNotification(context: Context, state: EngineState): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            flagsImmutable(),
        )
        val progress = (state.progress * 1000).toInt()
        val done = state.tasks.count { it.state.name == "Done" }
        val total = state.tasks.size.coerceAtLeast(1)

        val title = when {
            state.mode.name == "Receiving" -> context.getString(R.string.notif_title_receiving)
            state.mode.name == "Sending" -> context.getString(R.string.notif_title_sending)
            state.isHosting -> context.getString(R.string.notif_title_ready)
            else -> context.getString(R.string.app_name)
        }
        val bytes = state.tasks.sumOf { it.transferred }
        val size = state.tasks.sumOf { it.size }
        val text = when {
            state.mode.name == "Idle" && state.isHosting -> context.getString(R.string.notif_waiting)
            size > 0 -> "$done/$total · ${MoonLog.human(bytes)} de ${MoonLog.human(size)}" +
                (if (state.speedBps > 0) " · ${MoonLog.rate(state.speedBps.toDouble())}" else "")
            else -> state.statusText
        }

        return NotificationCompat.Builder(context, CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(1000, progress, state.tasks.none { it.state.name == "Done" } && progress == 0)
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    state.tasks.takeLast(4).forEach { t: TransferTask ->
                        style.addLine("${if (t.state.name == "Done") "✓" else "·"} ${t.name}")
                    }
                }
            )
            .setContentIntent(open)
            .build()
    }

    /** Notificación de "te quieren mandar archivos", con Aceptar/Rechazar directos. */
    fun requestNotification(
        context: Context,
        fromName: String,
        tasks: List<TransferTask>,
        accept: PendingIntent,
        decline: PendingIntent,
    ): Notification {
        val size = tasks.sumOf { it.size }
        return NotificationCompat.Builder(context, CHANNEL_REQUEST)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("$fromName quiere enviarte ${tasks.size} archivo(s)")
            .setContentText(MoonLog.human(size))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setStyle(
                NotificationCompat.InboxStyle().also { s ->
                    tasks.take(5).forEach { s.addLine("${it.name} · ${MoonLog.human(it.size)}") }
                    if (tasks.size > 5) s.setBigContentLine("y ${tasks.size - 5} más")
                }
            )
            .addAction(0, context.getString(R.string.notif_action_open), accept)
            .addAction(0, context.getString(R.string.notif_action_stop), decline)
            .build()
    }

    fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, com.moonshare.transfer.TransferService::class.java).setAction(action),
            flagsImmutable(),
        )

    private fun flagsImmutable(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    fun directionLabel(dir: TransferDirection) = when (dir) {
        TransferDirection.Send -> "Enviado"
        TransferDirection.Receive -> "Recibido"
    }
}
