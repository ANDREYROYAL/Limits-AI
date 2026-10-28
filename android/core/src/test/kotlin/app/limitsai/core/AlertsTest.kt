package app.limitsai.core

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private fun snap(vararg used: Pair<String, Double>, error: FetchError? = null) = UsageSnapshot(
        provider = Provider.CLAUDE,
        fetchedAt = 0,
        windows = used.map { (id, p) -> UsageWindow(id, id, p) },
        error = error,
    )

    @Test fun thresholds() {
        val alerts = Alerts.diff(snap("a" to 50.0, "b" to 90.0, "c" to 10.0), snap("a" to 85.0, "b" to 100.0, "c" to 20.0))
        assertEquals(listOf("a", "b"), alerts.map { it.windowId })
        assertTrue(alerts[0].text.startsWith("Израсходовано 85"))
        assertTrue(alerts[1].text.startsWith("Лимит исчерпан"))
    }

    @Test fun jumpOverBothThresholdsGivesOneAlert() {
        val alerts = Alerts.diff(snap("a" to 10.0), snap("a" to 100.0))
        assertEquals(1, alerts.size)
        assertTrue(alerts[0].text.startsWith("Лимит исчерпан"))
    }

    @Test fun restored() {
        val alerts = Alerts.diff(snap("a" to 100.0), snap("a" to 0.0))
        assertEquals("Лимит восстановлен, можно продолжать", alerts.single().text)
    }

    @Test fun noAlertsWithoutHistoryOrOnError() {
        assertTrue(Alerts.diff(null, snap("a" to 100.0)).isEmpty())
        assertTrue(Alerts.diff(snap("a" to 10.0), snap(error = FetchError.NETWORK)).isEmpty())
    }

    @Test fun format() {
        assertEquals("сейчас", Format.duration(0))
        assertEquals("2 мин", Format.duration(90_000))
        assertEquals("1 ч 30 мин", Format.duration(5_400_000))
        assertEquals("1 д 2 ч", Format.duration(93_600_000))
        val now = 1_761_652_800_000L // 2025-10-28T12:00Z
        assertEquals("15:30", Format.clock(now + 12_600_000, now, ZoneOffset.UTC))
        assertEquals("29 окт., 12:00", Format.clock(now + 86_400_000, now, ZoneOffset.UTC))
    }
}
