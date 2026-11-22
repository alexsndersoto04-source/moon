package com.airgesture.transfer.jarvis.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Recordatorios de JARVIS con notificación y alarma exacta cuando es posible. */
object JarvisNotifications {

    const val CHANNEL_ID = "jarvis_reminders"
    const val EXTRA_TITLE = "jarvis_reminder_title"
    const val EXTRA_NOTE = "jarvis_reminder_note"
    const val EXTRA_ID = "jarvis_reminder_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recordatorios JARVIS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Avisos y recordatorios de tu asistente" }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showNow(context: Context, title: String, note: String, notificationId: Int = 1001) {
        ensureChannel(context)
        val intent = Intent(context, com.airgesture.transfer.jarvis.ui.JarvisActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(note.ifBlank { "Toca para abrir JARVIS" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.ifBlank { title }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Sin permiso POST_NOTIFICATIONS: el recordatorio queda en la app.
        }
    }

    fun schedule(context: Context, reminderId: String, title: String, note: String, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_NOTE, note)
            putExtra(EXTRA_ID, reminderId)
        }
        val requestCode = (reminderId.hashCode() and 0x7fffffff)
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMs, pending
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMs, pending
                )
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMs, pending
            )
        }
    }

    fun cancel(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val requestCode = (reminderId.hashCode() and 0x7fffffff)
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
        pending.cancel()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(JarvisNotifications.EXTRA_TITLE).orEmpty()
            .ifBlank { "Recordatorio de JARVIS" }
        val note = intent.getStringExtra(JarvisNotifications.EXTRA_NOTE).orEmpty()
        val id = intent.getStringExtra(JarvisNotifications.EXTRA_ID).orEmpty()
        JarvisNotifications.showNow(context, title, note, id.hashCode() and 0x7fffffff)
    }
}
