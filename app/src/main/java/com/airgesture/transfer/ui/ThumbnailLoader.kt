package com.airgesture.transfer.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.airgesture.transfer.R
import com.airgesture.transfer.media.MediaEntry
import com.airgesture.transfer.media.MediaKind
import java.util.concurrent.Executors

private const val THUMB_WIDTH = 300
private const val THUMB_HEIGHT = 300
private const val CACHE_BYTES = 6 * 1024 * 1024

/**
 * Two-thread thumbnail pool with a bounded LRU bitmap cache.
 *
 * Uses MediaStore's own thumbnail pipeline (hardware accelerated, already
 * cached by the system) instead of decoding full-size files, so scrolling the
 * strip never allocates a 12 MP bitmap on a 2 GB device.
 */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "airgesture-thumbs").apply { priority = Thread.MIN_PRIORITY }
    }

    fun load(context: Context, entry: MediaEntry, target: ImageView) {
        val token = entry.key
        target.tag = token
        target.imageAlpha = 255

        val cached = synchronized(cache) { cache.get(token) }
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }

        val fallback = iconFor(entry.kind)
        target.setImageResource(fallback)
        target.scaleType = ImageView.ScaleType.CENTER_INSIDE
        target.setPadding(26, 26, 26, 26)

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = runCatching {
                appContext.contentResolver.loadThumbnail(
                    entry.uri,
                    Size(THUMB_WIDTH, THUMB_HEIGHT),
                    null
                )
            }.getOrNull()

            if (bitmap != null) {
                synchronized(cache) { cache.put(token, bitmap) }
            }
            target.post {
                if (target.tag == token && bitmap != null) {
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setPadding(0, 0, 0, 0)
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun clear(target: ImageView) {
        target.tag = null
        target.setImageDrawable(null)
    }

    @DrawableRes
    fun iconFor(kind: MediaKind): Int = when (kind) {
        MediaKind.IMAGES -> R.drawable.ic_image
        MediaKind.VIDEOS -> R.drawable.ic_video
        MediaKind.PDFS -> R.drawable.ic_pdf
        MediaKind.AUDIO -> R.drawable.ic_audio
    }

}
