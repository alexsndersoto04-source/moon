package com.moonshare.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.moonshare.core.MoonLog
import java.io.File
import java.io.OutputStream

/**
 * Destino de los archivos que entran.
 *
 * Android 10+ (que es el piso de la app) no permite escribir en la SD "a mano":
 * el camino correcto es insertar en el MediaStore con `IS_PENDING`, volcar los
 * bytes y publicarlo. Si el usuario prefiere que lo recibido no aparezca en la
 * galería, se cae al directorio privado de la app, que no necesita permisos.
 */
object SaveSink {

    private const val TAG = "Sink"
    const val FOLDER = "MoonShare"

    class Opened(val uri: Uri?, val file: File?, val size: Long)

    /**
     * Abre el destino. [MediaStore] para lo visible en galería, File para el
     * sandbox de la app.
     */
    fun open(context: Context, name: String, mime: String, size: Long, saveToGallery: Boolean): Opened {
        val safe = Filesx.sanitize(name)
        if (!saveToGallery) {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER)
            if (!dir.exists()) dir.mkdirs()
            val out = Filesx.uniqueIn(dir, safe)
            return Opened(FileProviderCompat.uriFor(context, out), out, 0L)
        }
        return runCatching { openMediaStore(context, safe, mime, size) }
            .getOrElse { err ->
                MoonLog.w(TAG, "MediaStore rechazó el insert, caigo a la app dir", err)
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER)
                val out = Filesx.uniqueIn(dir, safe)
                Opened(FileProviderCompat.uriFor(context, out), out, 0L)
            }
    }

    private fun openMediaStore(context: Context, name: String, mime: String, size: Long): Opened {
        val collection = pickCollection(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // SIZE es read-only desde Android 10: lo informa el propio MediaStore al publicar.
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: error("insert() devolvió null para $name")
        return Opened(uri, null, size)
    }

    private fun pickCollection(mime: String): Uri = when {
        mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
    }

    fun streamFor(context: Context, opened: Opened): OutputStream {
        // Si hay File, es destino privado: se escribe el File directamente. La Uri
        // de FileProvider solo sirve para compartirlo después, no como destino.
        opened.file?.let { return it.outputStream() }
        val uri = opened.uri ?: error("destino sin Uri ni archivo")
        return context.contentResolver.openOutputStream(uri, "w")
            ?: error("no se pudo abrir el OutputStream de $uri")
    }

    /** Quita IS_PENDING → el archivo ya es visible para la galería y otros apps. */
    fun publish(context: Context, opened: Opened, bytesWritten: Long) {
        if (opened.file != null) return // File privado: ya quedó guardado.
        val uri = opened.uri ?: return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.SIZE, bytesWritten)
                }
            }
            context.contentResolver.update(uri, values, null, null)
        }.onFailure { MoonLog.w(TAG, "publish falló", it) }
    }

    /** Se llama cuando el usuario cancela a mitad: no dejamos archivos huérfanos. */
    fun discard(context: Context, opened: Opened) {
        if (opened.file != null) {
            runCatching { opened.file.delete() }
            return
        }
        opened.uri?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
}

/** Indirection para que [SaveSink] no dependa del id de autoridad en tiempo de compilación. */
object FileProviderCompat {
    fun uriFor(context: Context, file: File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    }.getOrNull()

}
