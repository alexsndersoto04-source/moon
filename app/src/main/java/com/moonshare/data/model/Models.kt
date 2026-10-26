package com.moonshare.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Categorías que la app sabe navegar en el MediaStore. */
enum class MediaCategory(val id: String, val label: String) {
    Photos("photos", "Fotos"),
    Videos("videos", "Videos"),
    Audio("audio", "Música"),
    Docs("docs", "Documentos"),
    Apps("apps", "APKs"),
    All("all", "Todo");

    companion object {
        fun from(id: String?): MediaCategory =
            entries.firstOrNull { it.id == id } ?: Photos
    }
}

/**
 * Un archivo elegible para transferir. [uri] es lo único que necesita el lector
 * (content:// del MediaStore o SAF); [id] es la clave de la sesión de transferencia.
 */
@Serializable
data class FileItem(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val uri: String,
    val category: String,
    val mtimeMs: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val durationMs: Long = 0L,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
    val isImage: Boolean get() = mime.startsWith("image/")
    val isAudio: Boolean get() = mime.startsWith("audio/")
    val isApk: Boolean get() = mime == "application/vnd.android.package-archive"
}

/** Estado de un ítem dentro de la cola. */
enum class TaskState {
    Queued, Streaming, AwaitingConsent, Done, Rejected, Failed, Canceled;

    val isFinished: Boolean get() = this == Done || this == Rejected || this == Failed || this == Canceled
    val isActive: Boolean get() = this == Streaming || this == AwaitingConsent
}

@Serializable
data class TransferTask(
    val itemId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val uri: String,
    var transferred: Long = 0L,
    var state: TaskState = TaskState.Queued,
    var error: String? = null,
) {
    val progress: Float
        get() = if (size <= 0L) 0f else (transferred.toFloat() / size.toFloat()).coerceIn(0f, 1f)
}

enum class TransferDirection { Send, Receive }

/** Un par de tareas + contexto, lo que la UI dibuja como "una transferencia". */
data class TransferSession(
    val id: String,
    val direction: TransferDirection,
    val peerName: String,
    val startedAtMs: Long,
    val tasks: List<TransferTask>,
    val finished: Boolean = false,
) {
    val totalBytes: Long get() = tasks.sumOf { it.size }
    val doneBytes: Long get() = tasks.sumOf { it.transferred }
    val okCount: Int get() = tasks.count { it.state == TaskState.Done }
    val failedCount: Int get() = tasks.count { it.state == TaskState.Failed || it.state == TaskState.Rejected }
    val overallProgress: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val bytesPerSec: Long get() = 0L // lo calcula el engine, no el snapshot
}

/** Registro de lo que entró/salió, persistido en DataStore como JSON. */
@Serializable
data class HistoryEntry(
    @SerialName("t") val timestampMs: Long,
    @SerialName("d") val direction: String,
    @SerialName("n") val name: String,
    @SerialName("s") val size: Long,
    @SerialName("p") val peer: String,
    @SerialName("o") val ok: Boolean,
)
