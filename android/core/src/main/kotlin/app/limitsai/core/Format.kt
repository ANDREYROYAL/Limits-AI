package app.limitsai.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val ru = Locale.forLanguageTag("ru")
    private val time = DateTimeFormatter.ofPattern("HH:mm", ru)
    private val dateTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", ru)

    fun duration(ms: Long): String {
        if (ms <= 0) return "сейчас"
        val totalMin = (ms + 59_999) / 60_000
        val d = totalMin / 1440
        val h = (totalMin % 1440) / 60
        val m = totalMin % 60
        return when {
            d > 0 -> "$d д $h ч"
            h > 0 -> "$h ч $m мин"
            else -> "$m мин"
        }
    }

    fun clock(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val sameDay = t.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (if (sameDay) time else dateTime).format(t)
    }
}
