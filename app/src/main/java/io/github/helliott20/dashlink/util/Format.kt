package io.github.helliott20.dashlink.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bps: Long) = bytes(bps) + "/s"

    fun duration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
    fun time(t: LocalDateTime): String = t.format(timeFmt)
    fun day(t: LocalDateTime): String = t.format(dayFmt)

    /** "MOVIE_REC_SIZE" / "movie_rec_size" -> "Movie rec size" */
    fun humanize(key: String): String =
        key.replace('_', ' ').trim().lowercase().replaceFirstChar { it.titlecase() }
}
