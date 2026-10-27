package com.airgesture.transfer.media

import androidx.annotation.StringRes
import com.airgesture.transfer.R

/** The four shelves of the strip, backed directly by MediaStore. */
enum class MediaKind(
    @StringRes val labelRes: Int,
    @StringRes val emptyRes: Int
) {
    IMAGES(R.string.tab_images, R.string.empty_images),
    VIDEOS(R.string.tab_videos, R.string.empty_videos),
    PDFS(R.string.tab_pdfs, R.string.empty_pdfs),
    AUDIO(R.string.tab_audio, R.string.empty_audio);

    companion object {
        fun at(index: Int): MediaKind = entries[index.coerceIn(0, entries.lastIndex)]
    }
}
