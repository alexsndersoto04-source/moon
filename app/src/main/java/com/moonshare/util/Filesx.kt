package com.moonshare.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Utilidades de archivo: nombres, mimes, hashes y lecturas de tamaño. */
object Filesx {

    /** Caracteres que un filesystem de Android rechaza o que rompen el shell. */
    private val ILLEGAL = CharArray(32) { if (it < 32) it.toChar() else ' ' }
        .filter { it != ' ' && it != '\n' }.toCharArray()

    private val ILLEGAL_REGEX = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")

    fun sanitize(name: String, fallback: String = "archivo"): String {
        val base = name.trim().ifBlank { fallback }
        val cleaned = base.replace(ILLEGAL_REGEX, "_")
            .replace(Regex("\\s+"), " ")
            .trim('.')
        if (cleaned.isBlank()) return fallback
        // 180 bytes UTF-8 + ".ext" sin pasar el límite de 255 del ext4
        return if (cleaned.toByteArray(Charsets.UTF_8).size <= 180) cleaned
        else cleaned.toByteArray(Charsets.UTF_8).let { bytes ->
            String(bytes, 0, 175, Charsets.UTF_8) + "…"
        }
    }

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.US)

    fun mimeOf(name: String, fallback: String = "application/octet-stream"): String {
        val ext = extensionOf(name)
        if (ext.isEmpty()) return fallback
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "heic", "heif" -> "image/heif"
            "apk" -> "application/vnd.android.package-archive"
            "aar" -> "application/x-archive"
            "7z" -> "application/x-7z-compressed"
            "ipa", "ttf", "otf" -> "application/octet-stream"
            "epub" -> "application/epub+zip"
            "mobi" -> "application/x-mobipocket-ebook"
            else -> fallback
        }
    }

    fun isImage(mime: String) = mime.startsWith("image/")
    fun isVideo(mime: String) = mime.startsWith("video/")
    fun isAudio(mime: String) = mime.startsWith("audio/")
    fun isApk(mime: String) = mime == "application/vnd.android.package-archive"

    /** "foto.jpg" + "foto (1).jpg" si ya existe. Nunca pisa un archivo del usuario. */
    fun uniqueIn(dir: File, desired: String): File {
        if (!dir.exists()) dir.mkdirs()
        val base = desired.substringBeforeLast('.', desired)
        val ext = desired.substringAfterLast('.', "")
        var candidate = File(dir, desired)
        var n = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) " ($n)" else " ($n).$ext"
            candidate = File(dir, base + suffix)
            n++
            if (n > 999) break
        }
        return candidate
    }

    /** Nombre único dentro de un directorio de MediaStore (relative path), sin File API. */
    fun uniqueDisplayName(existing: Set<String>, desired: String): String {
        if (desired !in existing) return desired
        val base = desired.substringBeforeLast('.', desired)
        val ext = desired.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            if (candidate !in existing) return candidate
            if (++n > 999) return "$base-${System.currentTimeMillis()}"
        }
    }

    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    fun nameOf(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    fun typeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: mimeOf(uri.lastPathSegment ?: "")

    /**
     * SHA-256 de un archivo para integridad. Se calcula en streaming y solo si el
     * usuario lo pidió en Ajustes → Avanzado (en un Redmi 9C costa ~1 s por GB).
     */
    fun sha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun cursorLong(c: Cursor, idx: Int, default: Long = 0L): Long =
        if (idx < 0 || c.isNull(idx)) default else runCatching { c.getLong(idx) }.getOrDefault(default)

    fun cursorInt(c: Cursor, idx: Int, default: Int = 0): Int =
        if (idx < 0 || c.isNull(idx)) default else runCatching { c.getInt(idx) }.getOrDefault(default)

    fun cursorString(c: Cursor, idx: Int, default: String = ""): String =
        if (idx < 0 || c.isNull(idx)) default else runCatching { c.getString(idx) }.getOrDefault(default)
}
