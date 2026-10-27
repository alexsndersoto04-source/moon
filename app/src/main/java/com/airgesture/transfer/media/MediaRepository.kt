package com.airgesture.transfer.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin, allocation-conscious MediaStore facade.
 *
 * Queries run on [Dispatchers.IO]; only the newest [MAX_ITEMS] entries are read
 * so the first screen appears instantly even on a 2 GB RAM device with 20 000
 * photos. No bitmaps are decoded here — thumbnails are lazy (see ThumbnailLoader).
 */
class MediaRepository(private val context: Context) {

    suspend fun load(kind: MediaKind, limit: Int = MAX_ITEMS): List<MediaEntry> =
        withContext(Dispatchers.IO) {
            runCatching { query(kind, limit) }.getOrElse { emptyList() }
        }

    private fun query(kind: MediaKind, limit: Int): List<MediaEntry> {
        val resolver = context.contentResolver
        return when (kind) {
            MediaKind.IMAGES -> queryMedia(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                kind = kind,
                withDuration = false,
                limit = limit,
                resolver = resolver
            )

            MediaKind.VIDEOS -> queryMedia(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                kind = kind,
                withDuration = true,
                limit = limit,
                resolver = resolver
            )

            MediaKind.AUDIO -> queryMedia(
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                kind = kind,
                withDuration = true,
                limit = limit,
                resolver = resolver,
                extraSelection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            )

            MediaKind.PDFS -> queryPdfs(resolver, limit)
        }
    }

    private fun queryMedia(
        collection: Uri,
        kind: MediaKind,
        withDuration: Boolean,
        limit: Int,
        resolver: ContentResolver,
        extraSelection: String? = null
    ): List<MediaEntry> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            if (withDuration) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()

        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val result = ArrayList<MediaEntry>(limit)

        resolver.query(collection, projection, extraSelection, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationCol =
                if (withDuration) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1

            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idCol)
                val name = cursor.getStringOrNull(nameCol) ?: "Archivo"
                val mime = cursor.getStringOrNull(mimeCol) ?: defaultMime(kind, name)
                val size = cursor.getLongOrNull(sizeCol) ?: 0L
                val duration = if (durationCol >= 0) cursor.getLongOrNull(durationCol) ?: 0L else 0L
                result += MediaEntry(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = name,
                    mimeType = mime,
                    sizeBytes = size,
                    kind = kind,
                    durationMs = duration
                )
            }
        }
        return result
    }

    private fun queryPdfs(resolver: ContentResolver, limit: Int): List<MediaEntry> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val args = arrayOf(PDF_MIME)
        val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        val result = ArrayList<MediaEntry>(limit)

        resolver.query(collection, projection, selection, args, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idCol)
                result += MediaEntry(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getStringOrNull(nameCol) ?: "Documento.pdf",
                    mimeType = PDF_MIME,
                    sizeBytes = cursor.getLongOrNull(sizeCol) ?: 0L,
                    kind = MediaKind.PDFS
                )
            }
        }
        return result
    }

    private fun defaultMime(kind: MediaKind, name: String): String = when (kind) {
        MediaKind.IMAGES -> "image/*"
        MediaKind.VIDEOS -> "video/*"
        MediaKind.AUDIO -> "audio/*"
        MediaKind.PDFS -> PDF_MIME
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (index < 0 || isNull(index)) null else getString(index)

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    companion object {
        const val MAX_ITEMS = 60
        const val PDF_MIME = "application/pdf"
    }
}
