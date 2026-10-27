package com.moonshare.data.media

import android.content.Context
import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.moonshare.core.MoonLog
import com.moonshare.data.model.FileItem
import com.moonshare.data.model.MediaCategory
import com.moonshare.util.Filesx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Catálogo de lo que se puede enviar.
 *
 * Todo pasa por el [MediaStore], que es el único camino que funciona en Android
 * 10+ sin permisos de "todos los archivos" y que además ya trae tamaño/mime
 * resueltos. La pestaña Carpetas usa DocumentsContract sobre el árbol que el
 * usuario autorizó con el picker del sistema (SAF), sin permisos extra.
 */
class MediaRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    suspend fun query(
        category: MediaCategory,
        search: String = "",
        limit: Int = 800,
    ): List<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            when (category) {
                MediaCategory.Photos -> queryImages(search, limit)
                MediaCategory.Videos -> queryVideos(search, limit)
                MediaCategory.Audio -> queryAudio(search, limit)
                MediaCategory.Docs -> queryByMimes(search, limit, DOC_MIMES)
                MediaCategory.Apps -> queryByMimes(search, limit, APK_MIMES)
                MediaCategory.All -> queryAll(search, limit)
            }
        }.getOrElse { err ->
            MoonLog.e(TAG, "consulta ${category.id} falló", err)
            emptyList()
        }
    }

    /** Convierte cualquier URI externo (picker, intent de otra app) en un ítem. */
    fun fromUri(uri: Uri, forcedCategory: String = MediaCategory.All.id): FileItem? {
        val name = Filesx.nameOf(context, uri)
        val size = Filesx.sizeOf(context, uri).coerceAtLeast(0L)
        val mime = Filesx.typeOf(context, uri)
        return FileItem(
            id = stableId(uri.toString()),
            name = name,
            size = size,
            mime = mime,
            uri = uri.toString(),
            category = forcedCategory,
        )
    }

    fun fromUris(uris: List<Uri>): List<FileItem> =
        uris.mapNotNull { fromUri(it) }

    // ------------------------------------------------------------- colecciones
    private fun queryImages(search: String, limit: Int): List<FileItem> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<FileItem>(64)
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj,
            selectionFor(search, MediaStore.Images.Media.DISPLAY_NAME),
            argsFor(search),
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = Filesx.cursorLong(c, 0)
                val mime = Filesx.cursorString(c, 3, "image/jpeg")
                if (mime !in IMAGE_MIMES && !mime.startsWith("image/")) continue
                out.add(
                    FileItem(
                        id = "img$id",
                        name = Filesx.cursorString(c, 1, "imagen_$id.jpg"),
                        size = Filesx.cursorLong(c, 2),
                        mime = mime,
                        uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                            .appendPath(id.toString()).build().toString(),
                        category = MediaCategory.Photos.id,
                        mtimeMs = Filesx.cursorLong(c, 4) * 1000L,
                        widthPx = Filesx.cursorInt(c, 5),
                        heightPx = Filesx.cursorInt(c, 6),
                    )
                )
            }
        }
        return out.take(limit)
    }

    private fun queryVideos(search: String, limit: Int): List<FileItem> {
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
        )
        val out = ArrayList<FileItem>(64)
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            proj,
            selectionFor(search, MediaStore.Video.Media.DISPLAY_NAME),
            argsFor(search),
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = Filesx.cursorLong(c, 0)
                out.add(
                    FileItem(
                        id = "vid$id",
                        name = Filesx.cursorString(c, 1, "video_$id.mp4"),
                        size = Filesx.cursorLong(c, 2),
                        mime = Filesx.cursorString(c, 3, "video/mp4"),
                        uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                            .appendPath(id.toString()).build().toString(),
                        category = MediaCategory.Videos.id,
                        mtimeMs = Filesx.cursorLong(c, 4) * 1000L,
                        widthPx = Filesx.cursorInt(c, 5),
                        heightPx = Filesx.cursorInt(c, 6),
                        durationMs = Filesx.cursorLong(c, 7),
                    )
                )
            }
        }
        return out.take(limit)
    }

    private fun queryAudio(search: String, limit: Int): List<FileItem> {
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
        )
        val out = ArrayList<FileItem>(64)
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            proj,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
                if (search.isNotBlank()) " AND ${MediaStore.Audio.Media.TITLE} LIKE ?" else "",
            argsFor(search),
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (Filesx.cursorInt(c, 7) == 0) continue
                val id = Filesx.cursorLong(c, 0)
                out.add(
                    FileItem(
                        id = "aud$id",
                        name = Filesx.cursorString(c, 1, "audio_$id.mp3"),
                        size = Filesx.cursorLong(c, 2),
                        mime = Filesx.cursorString(c, 3, "audio/mpeg"),
                        uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                            .appendPath(id.toString()).build().toString(),
                        category = MediaCategory.Audio.id,
                        mtimeMs = Filesx.cursorLong(c, 4) * 1000L,
                        durationMs = Filesx.cursorLong(c, 6),
                    )
                )
            }
        }
        return out.take(limit)
    }

    private fun queryByMimes(search: String, limit: Int, mimes: Array<String>): List<FileItem> {
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATA,
        )
        val placeholders = mimes.joinToString(",") { "?" }
        val sel = StringBuilder("${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders)")
        val args = mimes.toMutableList()
        if (search.isNotBlank()) {
            sel.append(" AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            args.add("%$search%")
        }
        val out = ArrayList<FileItem>(64)
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            proj, sel.toString(), args.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = Filesx.cursorLong(c, 0)
                val mime = Filesx.cursorString(c, 3)
                val name = Filesx.cursorString(c, 1)
                // APK: el MediaStore suele tener la fila pero el archivo ya no está.
                val dataPath = Filesx.cursorString(c, 5)
                if (dataPath.isNotBlank() && !java.io.File(dataPath).exists()) continue
                out.add(
                    FileItem(
                        id = "doc$id",
                        name = name.ifBlank { "archivo_$id" },
                        size = Filesx.cursorLong(c, 2),
                        mime = mime.ifBlank { Filesx.mimeOf(name) },
                        uri = MediaStore.Files.getContentUri("external").buildUpon()
                            .appendPath(id.toString()).build().toString(),
                        category = MediaCategory.Docs.id,
                        mtimeMs = Filesx.cursorLong(c, 4) * 1000L,
                    )
                )
            }
        }
        return out.take(limit)
    }

    private fun queryAll(search: String, limit: Int): List<FileItem> {
        val per = (limit / 4).coerceAtLeast(60)
        return (queryImages(search, per) + queryVideos(search, per) +
            queryAudio(search, per) + queryByMimes(search, per, DOC_MIMES + APK_MIMES))
            .distinctBy { it.uri }
            .sortedByDescending { it.mtimeMs }
            .take(limit)
    }

    // ------------------------------------------------------------------ SAF
    /**
     * Lista un árbol autorizado vía [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE].
     * Recursivo con tope de profundidad para no perderse en /sdcard entero.
     */
    suspend fun listTree(treeUri: Uri, depth: Int = 0): List<FileItem> = withContext(Dispatchers.IO) {
        if (depth > MAX_TREE_DEPTH) return@withContext emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(treeUri)
        )
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<FileItem>()
        runCatching {
            resolver.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = Filesx.cursorString(c, 0)
                    val name = Filesx.cursorString(c, 1)
                    val mime = Filesx.cursorString(c, 3, "application/octet-stream")
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        out.addAll(listTree(docUri, depth + 1))
                        continue
                    }
                    out.add(
                        FileItem(
                            id = stableId(id),
                            name = name,
                            size = Filesx.cursorLong(c, 2),
                            mime = mime.ifBlank { Filesx.mimeOf(name) },
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                            category = MediaCategory.All.id,
                            mtimeMs = Filesx.cursorLong(c, 4),
                        )
                    )
                }
            }
        }.onFailure { MoonLog.w(TAG, "listTree: ${it.message}") }
        out.sortedBy { it.name.lowercase() }
    }

    /** Talla real del ítem; para los ítems de SAF el SIZE puede venir mal. */
    fun refreshSize(item: FileItem): FileItem {
        val size = runCatching {
            resolver.query(Uri.parse(item.uri), arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else item.size } ?: item.size
        }.getOrDefault(item.size)
        return if (size > 0 && size != item.size) item.copy(size = size) else item
    }

    fun takePersistablePermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { MoonLog.d(TAG, "permiso ya tomado o no persistible: ${it.message}") }
    }

    private fun selectionFor(search: String, column: String): String? =
        if (search.isBlank()) null else "$column LIKE ?"

    private fun argsFor(search: String): Array<String>? =
        if (search.isBlank()) null else arrayOf("%$search%")

    private fun stableId(input: String): String =
        input.hashCode().toString(16) + "-" + input.length.toString(16)

    private companion object {
        const val TAG = "Media"
        const val MAX_TREE_DEPTH = 3
        val IMAGE_MIMES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif",
            "image/gif", "image/bmp", "image/avif",
        )
        val DOC_MIMES = arrayOf(
            "application/pdf", "application/epub+zip", "text/plain", "text/markdown",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/zip", "application/x-7z-compressed", "application/x-rar-compressed",
            "application/octet-stream", "image/svg+xml", "audio/flac",
        )
        val APK_MIMES = arrayOf("application/vnd.android.package-archive")
    }
}
