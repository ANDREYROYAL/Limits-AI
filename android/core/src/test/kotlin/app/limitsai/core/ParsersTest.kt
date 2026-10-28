package app.limitsai.core

import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParsersTest {
    private fun assertError(expected: FetchError, block: () -> Unit) {
        try {
            block()
            fail("ожидалась ошибка $expected")
        } catch (e: FetchException) {
            assertEquals(expected, e.error)
        }
    }

    @Test fun claudeOrganizationPrefersChat() {
        val body = """[
          {"uuid":"api-org","capabilities":["api"]},
          {"uuid":"chat-org","capabilities":["chat","claude_pro"]}
        ]"""
        assertEquals("chat-org", ClaudeParser.parseOrganizationId(body))
    }

    @Test fun claudeOrganizationErrors() {
        assertError(FetchError.NOT_LOGGED_IN) {
            ClaudeParser.parseOrganizationId("""{"type":"error","error":{"type":"permission_error","message":"x"}}""")
        }
        assertError(FetchError.BLOCKED) {
            ClaudeParser.parseOrganizationId("<!DOCTYPE html><title>Just a moment...</title>")
        }
    }

    @Test fun claudeUsage() {
        val body = """{
          "seven_day": {"utilization": 31.0, "resets_at": "2026-11-02T10:00:00.123+00:00"},
          "five_hour": {"utilization": 87, "resets_at": "2026-10-28T12:00:00Z"},
          "seven_day_opus": null,
          "seven_day_sonnet": {"utilization": 5, "resets_at": null},
          "extra_usage": {"is_enabled": false}
        }"""
        val w = ClaudeParser.parseUsage(body)
        assertEquals(listOf("claude.five_hour", "claude.seven_day", "claude.seven_day_sonnet"), w.map { it.id })
        assertEquals("Сессия (5 ч)", w[0].label)
        assertEquals(87.0, w[0].usedPercent, 0.0)
        assertEquals(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), w[0].resetsAt)
        assertEquals(Instant.parse("2026-11-02T10:00:00.123Z").toEpochMilli(), w[1].resetsAt)
        assertNull(w[2].resetsAt)
    }

    @Test fun jsonWrappedInViewerText() {
        val w = ClaudeParser.parseUsage("Pretty-print\n{\"five_hour\":{\"utilization\":10,\"resets_at\":null}}")
        assertEquals(10.0, w.single().usedPercent, 0.0)
        assertError(FetchError.BLOCKED) { ClaudeParser.parseUsage("Verify you are human by completing the action below.") }
    }

    @Test fun claudeUsageUnexpected() {
        assertError(FetchError.UNEXPECTED) { ClaudeParser.parseUsage("""{"foo": 1}""") }
    }

    @Test fun chatGptSession() {
        assertError(FetchError.NOT_LOGGED_IN) { ChatGptParser.parseSession("{}") }
        val s = ChatGptParser.parseSession("""{"accessToken":"tok","account":{"id":"acc-1"}}""")
        assertEquals(ChatGptParser.Session("tok", "acc-1"), s)
    }

    @Test fun chatGptAccountIdFromJwt() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acc-jwt"}}""".toByteArray())
        val s = ChatGptParser.parseSession("""{"accessToken":"h.$payload.sig"}""")
        assertEquals("acc-jwt", s.accountId)
    }

    @Test fun chatGptUsage() {
        val now = 1_000_000L
        val body = """{
          "plan_type": "plus",
          "rate_limit": {
            "allowed": true, "limit_reached": false,
            "primary_window": {"used_percent": 42, "limit_window_seconds": 18000, "reset_after_seconds": 600, "reset_at": 1761660000},
            "secondary_window": {"used_percent": 12, "limit_window_seconds": 604800, "reset_after_seconds": 120}
          },
          "additional_rate_limits": [
            {"limit_name": "GPT-5-Codex-Spark", "rate_limit": {"primary_window": {"used_percent": 100, "limit_window_seconds": 18000, "reset_at": 1761661000}, "secondary_window": null}}
          ]
        }"""
        val (plan, w) = ChatGptParser.parseUsage(body, now)
        assertEquals("plus", plan)
        assertEquals(3, w.size)
        assertEquals("Codex · 5 ч", w[0].label)
        assertEquals(1_761_660_000_000L, w[0].resetsAt)
        assertEquals("Codex · неделя", w[1].label)
        assertEquals(now + 120_000, w[1].resetsAt)
        assertEquals("GPT-5-Codex-Spark · 5 ч", w[2].label)
        assertEquals(100.0, w[2].usedPercent, 0.0)
    }

    @Test fun chatGptUsageNoLimitsYet() {
        val (_, w) = ChatGptParser.parseUsage("""{"plan_type":"free","rate_limit":{"primary_window":null}}""", 0)
        assertTrue(w.isEmpty())
        assertError(FetchError.NOT_LOGGED_IN) { ChatGptParser.parseUsage("""{"detail":"Unauthorized"}""", 0) }
    }

    @Test fun windowLabels() {
        assertEquals("5 ч", ChatGptParser.windowLabel(18_000))
        assertEquals("неделя", ChatGptParser.windowLabel(604_800))
        assertEquals("30 дн", ChatGptParser.windowLabel(2_592_000))
    }
}
