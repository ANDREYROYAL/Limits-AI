package app.limitsai.core

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

// Эндпоинты неофициальные (их использует сам сайт), поэтому разбор максимально терпимый
// к лишним и отсутствующим полям.

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseJson(body: String): JsonElement {
    val text = body.trim()
    if (looksLikeChallenge(text)) throw FetchException(FetchError.BLOCKED)
    return try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        // Просмотрщик JSON в WebView может добавить вокруг текста свои надписи — вырезаем сам JSON.
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = text.indexOfLast { it == '}' || it == ']' }
        if (start < 0 || end <= start) throw FetchException(FetchError.UNEXPECTED, e)
        runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }
            .getOrElse { throw FetchException(FetchError.UNEXPECTED, e) }
    }
}

/** Страница проверки Cloudflare — как HTML, так и как видимый текст страницы. */
fun looksLikeChallenge(body: String): Boolean =
    body.contains("Just a moment", true) || body.contains("Verify you are human", true) ||
        body.contains("cf-chl", true) || body.contains("challenge-platform")

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

private fun parseInstant(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

object ClaudeParser {
    private val labels = mapOf(
        "five_hour" to "Сессия (5 ч)",
        "seven_day" to "Неделя",
        "seven_day_opus" to "Неделя · Opus",
        "seven_day_sonnet" to "Неделя · Sonnet",
        "seven_day_oauth_apps" to "Неделя · приложения",
        "seven_day_cowork" to "Неделя · Cowork",
        "seven_day_routines" to "Неделя · рутины",
    )
    private val order = labels.keys.toList()

    /** Ответ /api/organizations — массив организаций; берём ту, где есть чат. */
    fun parseOrganizationId(body: String): String {
        val root = parseJson(body)
        checkError(root)
        val orgs = (root as? JsonArray)?.mapNotNull { it.obj() } ?: throw FetchException(FetchError.UNEXPECTED)
        if (orgs.isEmpty()) throw FetchException(FetchError.NOT_LOGGED_IN)
        val chat = orgs.firstOrNull { org ->
            (org["capabilities"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "chat" } == true
        }
        return (chat ?: orgs.first()).str("uuid") ?: throw FetchException(FetchError.UNEXPECTED)
    }

    /** Ответ /api/organizations/{id}/usage: {"five_hour": {"utilization": 42, "resets_at": "..."}, ...}. */
    fun parseUsage(body: String): List<UsageWindow> {
        val root = parseJson(body).obj() ?: throw FetchException(FetchError.UNEXPECTED)
        checkError(root)
        val windows = root.entries.mapNotNull { (key, value) ->
            val o = value.obj() ?: return@mapNotNull null
            val used = o.num("utilization") ?: return@mapNotNull null
            UsageWindow(
                id = "claude.$key",
                label = labels[key] ?: key.replace('_', ' '),
                usedPercent = used.coerceIn(0.0, 100.0),
                resetsAt = parseInstant(o.str("resets_at")),
            )
        }
        if (windows.isEmpty()) throw FetchException(FetchError.UNEXPECTED)
        return windows.sortedBy { w -> order.indexOf(w.id.removePrefix("claude.")).let { if (it < 0) order.size else it } }
    }

    private fun checkError(root: JsonElement) {
        val o = root.obj() ?: return
        if (o.str("type") != "error") return
        val type = o["error"].obj()?.str("type")
        throw FetchException(
            if (type in setOf("authentication_error", "permission_error", "not_found_error")) FetchError.NOT_LOGGED_IN
            else FetchError.UNEXPECTED,
        )
    }
}

object ChatGptParser {
    data class Session(val accessToken: String, val accountId: String?)

    /** Ответ /api/auth/session. Без входа сайт отдаёт пустой объект {}. */
    fun parseSession(body: String): Session {
        val root = parseJson(body).obj() ?: throw FetchException(FetchError.UNEXPECTED)
        val token = root.str("accessToken") ?: throw FetchException(FetchError.NOT_LOGGED_IN)
        val accountId = root["account"].obj()?.str("id") ?: accountIdFromJwt(token)
        return Session(token, accountId)
    }

    internal fun accountIdFromJwt(token: String): String? = runCatching {
        val payload = token.split('.')[1]
        val decoded = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        json.parseToJsonElement(decoded).jsonObject["https://api.openai.com/auth"].obj()?.str("chatgpt_account_id")
    }.getOrNull()

    /** Ответ /backend-api/wham/usage (тот же, что использует Codex CLI). */
    fun parseUsage(body: String, now: Long): Pair<String?, List<UsageWindow>> {
        val root = parseJson(body).obj() ?: throw FetchException(FetchError.UNEXPECTED)
        if (root["detail"] != null && root["rate_limit"] == null) throw FetchException(FetchError.NOT_LOGGED_IN)
        val plan = root.str("plan_type")
        val windows = mutableListOf<UsageWindow>()
        windows += windowsOf(root["rate_limit"].obj(), "codex", null, now)
        (root["additional_rate_limits"] as? JsonArray)?.forEachIndexed { i, el ->
            val o = el.obj() ?: return@forEachIndexed
            val name = o.str("limit_name") ?: o.str("metered_feature") ?: "доп. лимит ${i + 1}"
            windows += windowsOf(o["rate_limit"].obj(), "codex.extra$i", name, now)
        }
        if (windows.isEmpty() && root["rate_limit"] !is JsonObject) throw FetchException(FetchError.UNEXPECTED)
        return plan to windows
    }

    private fun windowsOf(rateLimit: JsonObject?, idPrefix: String, name: String?, now: Long): List<UsageWindow> {
        if (rateLimit == null) return emptyList()
        return listOf("primary_window", "secondary_window").mapNotNull { key ->
            val w = rateLimit[key].takeUnless { it is JsonNull }.obj() ?: return@mapNotNull null
            val used = w.num("used_percent") ?: return@mapNotNull null
            val resetsAt = w.num("reset_at")?.let { (it * 1000).toLong() }
                ?: w.num("reset_after_seconds")?.let { now + (it * 1000).toLong() }
            val span = windowLabel(w.num("limit_window_seconds")?.toLong())
            UsageWindow(
                id = "$idPrefix.$key",
                label = if (name != null) "$name · $span" else "Codex · $span",
                usedPercent = used.coerceIn(0.0, 100.0),
                resetsAt = resetsAt,
            )
        }
    }

    internal fun windowLabel(seconds: Long?): String = when {
        seconds == null -> "лимит"
        seconds % 86_400 == 0L && seconds >= 86_400 -> when (val d = seconds / 86_400) {
            7L -> "неделя"
            1L -> "сутки"
            else -> "$d дн"
        }
        else -> "${(seconds + 1799) / 3600} ч"
    }
}
