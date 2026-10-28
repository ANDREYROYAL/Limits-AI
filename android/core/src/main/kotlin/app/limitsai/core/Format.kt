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
        // Округляем вниз, как официальные приложения: 4 ч 59 мин, а не 5 ч 1 мин.
        val totalMin = ms / 60_000
        if (totalMin == 0L) return "меньше минуты"
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
