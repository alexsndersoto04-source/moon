package com.airgesture.transfer.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns arbitrary `content://` URIs (what the system share sheet hands us) into
 * [MediaEntry] values the transfer engine already understands.
 *
 * Nothing here assumes MediaStore: a file shared from Google Photos, Files,
 * WhatsApp or a USB stick arrives with its own provider, Name and Size come from
 * [OpenableColumns], and when the provider refuses to answer we still derive a
 * usable label from the URI itself instead of failing the send.
 */
object MediaResolver {

    suspend fun fromUris(context: Context, uris: List<Uri>): List<MediaEntry> =
        withContext(Dispatchers.IO) { uris.mapNotNull { entry(context, it) } }

    private fun entry(context: Context, uri: Uri): MediaEntry? {
        val resolver = context.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: guessMime(uri.lastPathSegment.orEmpty())

        var name: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            name = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
        }

        val display = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "Archivo" }

        // A provider that cannot report a size is still sendable: the receiver
        // trusts the stream length, and the progress bar turns indeterminate.
        return MediaEntry(
            uri = uri,
            name = display,
            mimeType = mime,
            sizeBytes = size.coerceAtLeast(0L),
            kind = kindOf(mime)
        )
    }

    private fun kindOf(mime: String): MediaKind = when {
        mime.startsWith("image/") -> MediaKind.IMAGES
        mime.startsWith("video/") -> MediaKind.VIDEOS
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.PDFS
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "gif", "bmp" -> "image/*"
        "mp4", "mkv", "mov", "3gp", "webm" -> "video/*"
        "mp3", "m4a", "ogg", "wav", "flac", "aac" -> "audio/*"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** Public MIME for a URI without throwing on a dead provider. */
    fun mimeOf(resolver: ContentResolver, uri: Uri): String =
        runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
}
