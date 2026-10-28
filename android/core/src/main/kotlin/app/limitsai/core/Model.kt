package app.limitsai.core

import kotlinx.serialization.Serializable

enum class Provider(val title: String, val loginUrl: String, val homeUrl: String) {
    CLAUDE("Claude", "https://claude.ai/login", "https://claude.ai/"),
    CHATGPT("ChatGPT / Codex", "https://chatgpt.com/auth/login", "https://chatgpt.com/"),
}

/** Одно окно лимита: процент израсходованного и время сброса (epoch millis). */
@Serializable
data class UsageWindow(
    val id: String,
    val label: String,
    val usedPercent: Double,
    val resetsAt: Long? = null,
)

@Serializable
data class UsageSnapshot(
    val provider: Provider,
    val fetchedAt: Long,
    val windows: List<UsageWindow> = emptyList(),
    val plan: String? = null,
    val error: FetchError? = null,
)

@Serializable
enum class FetchError(val message: String) {
    NOT_LOGGED_IN("Нужно войти в аккаунт"),
    BLOCKED("Сервис временно не пускает (Cloudflare). Откройте приложение, чтобы обновить"),
    UNEXPECTED("Сервис ответил в неожиданном формате"),
    NETWORK("Нет связи с сервисом"),
}

class FetchException(val error: FetchError, cause: Throwable? = null) : Exception(error.message, cause)
