package com.airgesture.transfer.media

import android.net.Uri

/**
 * One transferable file. Immutable and cheap: the URI is the identity, so the
 * adapter and the transfer engine never hold open handles.
 */
data class MediaEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: MediaKind,
    val durationMs: Long = 0L
) {
    val key: String get() = uri.toString()

    /** Extensions help the receiver pick the right folder when MIME is generic. */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}
