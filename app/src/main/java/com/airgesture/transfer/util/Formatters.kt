package com.airgesture.transfer.util

import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {

    /** 1.4 MB, 812 KB, 2.1 GB — compact, human, never scientific. */
    fun bytes(value: Long): String {
        if (value <= 0L) return "0 KB"
        val kb = value / 1024.0
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) {
            return if (mb < 10.0) String.format(Locale.getDefault(), "%.1f MB", mb)
            else String.format(Locale.getDefault(), "%.0f MB", mb)
        }
        return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
    }

    /** 1:07, 12:04 — media duration. */
    fun duration(millis: Long): String {
        if (millis <= 0L) return ""
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes >= 60) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /** Keeps filenames readable in a 120 dp card. */
    fun shorten(name: String, max: Int = 22): String {
        if (name.length <= max) return name
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot > max - 2) return name.take(max - 1) + "…"
        val ext = name.substring(dot)
        val base = name.substring(0, max - ext.length - 1)
        return base + "…" + ext
    }
}
