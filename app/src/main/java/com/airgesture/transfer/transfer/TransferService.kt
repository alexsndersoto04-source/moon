package com.airgesture.transfer.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.airgesture.transfer.R
import com.airgesture.transfer.media.MediaEntry
import com.airgesture.transfer.media.MediaResolver
import com.airgesture.transfer.ui.MainActivity
import com.airgesture.transfer.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a transfer alive when the screen is not.
 *
 * The whole point of 2.0: pick photos in *your* gallery, share them with
 * AirGesture, lock the phone — and the file still leaves. This service owns the
 * foreground notification (progress, peer, cancel) while [TransferHub] owns the
 * session, so the UI is optional rather than required.
 *
 * It is deliberately short-lived: it starts when there is work, and stops a few
 * seconds after the queue is empty instead of leaving a notification pinned to
 * the shade forever.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var stopJob: Job? = null
    private var resolving = false
    private var waitStartedAt = 0L
    @Volatile private var finishing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        TransferHub.onServiceStarted()
        // On Android 14 a foreground service can be refused if the transport
        // permissions are gone; a crash here would take the whole send with it.
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(TransferHub.state.value),
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
            )
        }.isSuccess
        if (!started) {
            finishWith(getString(R.string.notif_permissions))
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (finishing) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_SEND_URIS -> handleSend(intent)
            ACTION_CANCEL -> {
                TransferHub.cancelAll()
                finishWith(getString(R.string.notif_cancelled))
            }
            ACTION_ENSURE -> TransferHub.ensureStarted(this)
        }
        watch()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        stopJob?.cancel()
        stopJob = null
        scope.cancel()
        TransferHub.onServiceStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- actions

    private fun handleSend(intent: Intent) {
        if (finishing) return
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
            .mapNotNull { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
        if (uris.isEmpty()) {
            finishWith(getString(R.string.notif_nothing_shared))
            return
        }

        if (Permissions.missingForSending(this).isNotEmpty()) {
            finishWith(getString(R.string.notif_permissions))
            return
        }

        resolving = true
        scope.launch {
            val entries: List<MediaEntry> = MediaResolver.fromUris(this@TransferService, uris)
            resolving = false
            if (entries.isEmpty()) {
                finishWith(getString(R.string.notif_nothing_shared))
                return@launch
            }
            TransferHub.ensureStarted(this@TransferService)
            if (TransferHub.enqueue(entries) == 0) {
                finishWith(getString(R.string.notif_already_queued))
            }
        }
    }

    private fun watch() {
        if (watcher != null) return
        watcher = scope.launch {
            TransferHub.state.collect { snapshot ->
                if (!finishing) render(snapshot)
            }
        }
        scope.launch {
            TransferHub.events.collect { event ->
                // A send of *one* file out of five must not shut the service
                // down: announce it and let the idle timer decide when to stop.
                when (event) {
                    is TransferHub.Event.Sent -> postCompletion(
                        getString(R.string.notif_sent_title),
                        getString(R.string.notif_sent_body, event.name)
                    )
                    is TransferHub.Event.Received -> postCompletion(
                        getString(R.string.notif_received_title),
                        getString(R.string.notif_received_body, event.name)
                    )
                    is TransferHub.Event.Failed -> postCompletion(
                        getString(R.string.notif_failed_title),
                        event.reason
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ notification

    private fun render(snapshot: TransferHub.Snapshot) {
        if (!finishing) notify(buildNotification(snapshot))

        val transfer = snapshot.transfer
        val idle = transfer == null && snapshot.queue == 0
        if (idle) {
            if (resolving) return
            if (stopJob == null) {
                stopJob = scope.launch {
                    delay(IDLE_LINGER_MS)
                    stopJob = null
                    val latest = TransferHub.state.value
                    if (latest.transfer == null && latest.queue == 0) stopSelf()
                }
            }
            return
        }

        stopJob?.cancel()
        stopJob = null

        // Something is queued but no peer appeared: never hang silently in the
        // shade forever. After this long, say so and let the app take over.
        if (transfer == null && snapshot.queue > 0 && snapshot.state != NearbyState.CONNECTED) {
            if (waitStartedAt == 0L) waitStartedAt = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - waitStartedAt > MAX_WAIT_MS) {
                finishWith(getString(R.string.notif_wait_timeout))
            }
        } else {
            waitStartedAt = 0L
        }
    }

    private fun buildNotification(snapshot: TransferHub.Snapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val transfer = snapshot.transfer
        val connected = snapshot.state == NearbyState.CONNECTED

        var title = getString(R.string.notif_idle_title)
        var text = getString(R.string.notif_idle_text)
        var percent = -1
        var indeterminate = false

        when {
            transfer != null -> {
                title = getString(R.string.notif_sending_title, transfer.fileName)
                if (transfer.totalBytes > 0L) {
                    percent = (transfer.fraction * 100f).toInt().coerceIn(0, 100)
                    text = getString(R.string.notif_sending_text, percent)
                } else {
                    indeterminate = true
                    text = getString(R.string.notif_sending_unknown)
                }
            }
            snapshot.queue > 0 && connected -> {
                title = getString(R.string.notif_connected_title, snapshot.peerName ?: getString(R.string.app_name))
                text = getString(R.string.notif_queued_text, snapshot.queue)
                indeterminate = true
            }
            snapshot.queue > 0 -> {
                title = getString(R.string.notif_waiting_title)
                text = getString(R.string.notif_waiting_text, snapshot.queue)
                indeterminate = true
            }
            else -> {
                title = getString(R.string.notif_idle_title)
                text = getString(R.string.notif_idle_text)
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (percent in 0..100) builder.setProgress(100, percent, false)
        else if (indeterminate) builder.setProgress(0, 0, true)

        if (transfer != null || snapshot.queue > 0) {
            builder.addAction(0, getString(R.string.cancel_transfer), cancel)
        }
        return builder.build()
    }

    private fun notify(notification: Notification) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    /** A finished file leaves one line in the shade; the service stops when idle. */
    private fun postCompletion(title: String, body: String) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val done = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(false)
            .build()
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(COMPLETION_ID, done) }
        }
    }

    private fun finishWith(message: String) {
        if (finishing) return
        finishing = true
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(false)
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(ATTENTION_ID, builder.build()) }
        }
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "airgesture_transfer_bg"
        private const val NOTIFICATION_ID = 1201
        private const val COMPLETION_ID = 1202
        private const val ATTENTION_ID = 1203

        private const val IDLE_LINGER_MS = 4000L
        private const val MAX_WAIT_MS = 3 * 60 * 1000L

        const val ACTION_SEND_URIS = "com.airgesture.transfer.action.SEND_URIS"
        const val ACTION_CANCEL = "com.airgesture.transfer.action.CANCEL"
        const val ACTION_ENSURE = "com.airgesture.transfer.action.ENSURE"
        const val EXTRA_URIS = "com.airgesture.transfer.extra.URIS"

        /** Entry point used by the share sheet. */
        fun sendUris(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_SEND_URIS
                putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Keeps the session warm without sending anything (used by the UI). */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_ENSURE)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
        }
    }
}
