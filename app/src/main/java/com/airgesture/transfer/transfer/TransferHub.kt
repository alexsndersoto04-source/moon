package com.airgesture.transfer.transfer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.airgesture.transfer.core.Diag
import com.airgesture.transfer.media.MediaEntry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The one and only transfer session in the process.
 *
 * Before 2.0 the Nearby session and the outgoing queue lived inside
 * [com.airgesture.transfer.ui.MainViewModel], which meant a transfer existed only
 * while that screen did: closing the app killed the link, and a file shared from
 * the system gallery had nowhere to go. The hub fixes both problems by owning the
 * session at application scope:
 *
 *  - the UI **observes** it ([state], [events]) instead of owning it;
 *  - [com.airgesture.transfer.transfer.TransferService] keeps the process alive
 *    with a foreground notification and observes the very same flows.
 *
 * All public functions and every callback run on the main thread (the manager
 * posts its callbacks there), so the queue needs no lock beyond [Synchronized]
 * for the rare cross-thread caller.
 */
object TransferHub : NearbyTransferManager.Listener {

    /** Immutable view of the session for any observer (UI, service, widgets). */
    data class Snapshot(
        val state: NearbyState = NearbyState.IDLE,
        val peerName: String? = null,
        val transfer: TransferProgress? = null,
        /** Files still waiting for the link. */
        val queue: Int = 0,
        /** Rolling trace, newest last. */
        val logs: List<String> = emptyList()
    )

    /** One-shot facts worth a notification or a UI message. */
    sealed interface Event {
        data class Sent(val name: String) : Event
        data class Received(val name: String, val uri: Uri) : Event
        data class Failed(val name: String?, val reason: String) : Event
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { shutdownIfIdle() }

    private var manager: NearbyTransferManager? = null
    private var started = false
    private var serviceActive = false
    private val queue = ArrayDeque<MediaEntry>()

    val isConnected: Boolean get() = manager?.isConnected == true
    val isBusy: Boolean get() = manager?.isBusy == true
    val isRunning: Boolean get() = started

    // ------------------------------------------------------------- lifecycle

    /**
     * Starts (or reuses) the session. Called from the camera screen and from the
     * background service, whichever comes first: there is never a second
     * advertising/discovering pair fighting for the radio.
     */
    @Synchronized
    fun ensureStarted(context: Context) {
        mainHandler.removeCallbacks(idleStop)
        val existing = manager
        if (existing != null) {
            if (!started) {
                started = true
                existing.start()
            }
            return
        }
        val created = NearbyTransferManager(context.applicationContext, this)
        manager = created
        started = true
        _state.update { it.copy(state = NearbyState.SEARCHING) }
        created.start()
    }

    /** The foreground service says hello: the session must survive the UI. */
    @Synchronized
    fun onServiceStarted() {
        serviceActive = true
        mainHandler.removeCallbacks(idleStop)
    }

    /** The service is gone: stop the radios if nothing is in flight. */
    @Synchronized
    fun onServiceStopped() {
        serviceActive = false
        // Keep the link for one idle window: the other phone often replies right
        // after receiving, and reconnecting for a single photo feels broken.
        armIdleStop()
    }

    /**
     * Tears the session down only when nothing can be lost. A transfer in flight
     * or a waiting queue keeps it alive: a file must never disappear because the
     * user swiped the app away.
     */
    @Synchronized
    fun shutdownIfIdle() {
        if (serviceActive) return
        if (queue.isNotEmpty()) return
        if (_state.value.transfer != null) return
        val current = manager ?: return
        started = false
        manager = null
        runCatching { current.stop() }
        _state.update { it.copy(state = NearbyState.IDLE, peerName = null) }
    }

    // --------------------------------------------------------------- sending

    /**
     * Queues files (one or many, in order) and sends whatever the link allows
     * right now. Returns how many were actually added, so the caller can tell
     * the user "ya estaba en la cola" instead of lying.
     */
    @Synchronized
    fun enqueue(entries: List<MediaEntry>): Int {
        if (entries.isEmpty()) return 0
        var added = 0
        val batch = HashSet<String>()
        for (entry in entries) {
            if (!batch.add(entry.key)) continue
            if (queue.any { it.key == entry.key }) continue
            queue.addLast(entry)
            added++
        }
        if (added == 0) return 0
        mainHandler.removeCallbacks(idleStop)
        _state.update { it.copy(queue = queue.size) }
        Diag.add("En cola: $added archivo(s) · total ${queue.size}")
        pump()
        return added
    }

    /** Cancel button / notification action: stop the payload and drop the queue. */
    @Synchronized
    fun cancelAll(): Boolean {
        val cancelled = runCatching { manager?.cancelCurrent() == true }.getOrDefault(false)
        queue.clear()
        _state.update { it.copy(queue = 0, transfer = null) }
        armIdleStop()
        return cancelled
    }

    /** Sends the next queued file when the channel is free. */
    @Synchronized
    private fun pump() {
        val session = manager ?: return
        if (_state.value.transfer != null) return
        if (!session.isConnected) return
        val next = queue.removeFirstOrNull() ?: return
        _state.update { it.copy(queue = queue.size) }
        if (session.isBusy) {
            // The previous payload is still closing; keep it at the head.
            queue.addFirst(next)
            _state.update { it.copy(queue = queue.size) }
            return
        }
        if (!session.send(next)) {
            queue.addFirst(next)
            _state.update { it.copy(queue = queue.size) }
            return
        }
        _state.update {
            it.copy(
                transfer = TransferProgress(
                    fileName = next.name,
                    bytesTransferred = 0L,
                    totalBytes = next.sizeBytes,
                    outgoing = true
                )
            )
        }
    }

    // ------------------------------------------------- manager callbacks

    override fun onStateChanged(state: NearbyState, detail: String?) {
        _state.update { snapshot ->
            snapshot.copy(
                state = state,
                peerName = if (state == NearbyState.CONNECTED) manager?.peerName else snapshot.peerName
            )
        }
        if (state == NearbyState.CONNECTED) pump()
        if (state == NearbyState.IDLE) armIdleStop()
    }

    override fun onTransferProgress(progress: TransferProgress) {
        _state.update { it.copy(transfer = progress) }
    }

    override fun onFileSent(fileName: String) {
        Diag.add("Entregado: $fileName")
        _state.update { it.copy(transfer = null) }
        _events.tryEmit(Event.Sent(fileName))
        pump()
        if (queue.isEmpty()) armIdleStop()
    }

    override fun onFileReceived(fileName: String, uri: Uri) {
        Diag.add("Recibido: $fileName")
        _state.update { it.copy(transfer = null) }
        _events.tryEmit(Event.Received(fileName, uri))
    }

    override fun onTransferFailed(fileName: String?, reason: String) {
        _state.update { it.copy(transfer = null) }
        _events.tryEmit(Event.Failed(fileName, reason))
        pump()
        if (queue.isEmpty()) armIdleStop()
    }

    override fun onLog(line: String) {
        _state.update { snapshot ->
            snapshot.copy(logs = (snapshot.logs + line).takeLast(MAX_LOG_LINES))
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A finished queue does not mean "stop immediately": the peer link is useful
     * for a few more seconds (a second file, the delivery ack). The session is
     * released shortly after, so a phone left on a table is not pumping BLE all
     * afternoon.
     */
    private fun armIdleStop() {
        mainHandler.removeCallbacks(idleStop)
        mainHandler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private const val MAX_LOG_LINES = 6
    private const val IDLE_STOP_MS = 20_000L
}
