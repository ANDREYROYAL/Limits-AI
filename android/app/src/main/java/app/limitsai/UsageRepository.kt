package app.limitsai

import android.content.Context
import android.webkit.CookieManager
import app.limitsai.core.Alerts
import app.limitsai.core.ChatGptParser
import app.limitsai.core.ClaudeParser
import app.limitsai.core.FetchError
import app.limitsai.core.FetchException
import app.limitsai.core.Provider
import app.limitsai.core.UsageSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Загрузка лимитов, хранение последних снимков и уведомления об изменениях. */
object UsageRepository {
    private const val PREFS = "usage"
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _snapshots = MutableStateFlow<Map<Provider, UsageSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<Provider, UsageSnapshot>> = _snapshots.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _snapshots.value = Provider.entries.mapNotNull { p ->
            prefs.getString("snapshot.${p.name}", null)
                ?.let { runCatching { json.decodeFromString<UsageSnapshot>(it) }.getOrNull() }
                ?.let { p to it }
        }.toMap()
    }

    fun isLoggedIn(provider: Provider): Boolean {
        val cookies = CookieManager.getInstance().getCookie(provider.homeUrl).orEmpty()
        return when (provider) {
            Provider.CLAUDE -> "sessionKey=" in cookies
            Provider.CHATGPT -> "__Secure-next-auth.session-token" in cookies
        }
    }

    suspend fun refreshAll(context: Context, notify: Boolean = true) = mutex.withLock {
        _refreshing.value = true
        try {
            if (_snapshots.value.isEmpty()) load(context)
            val fetcher = WebFetcher(context.applicationContext)
            for (provider in Provider.entries) {
                if (!isLoggedIn(provider)) {
                    if (provider in _snapshots.value) save(context, provider, null)
                    continue
                }
                val previous = _snapshots.value[provider]
                val now = System.currentTimeMillis()
                val current = try {
                    fetch(context, fetcher, provider, now)
                } catch (e: FetchException) {
                    // Оставляем последние известные значения и показываем ошибку рядом.
                    previous?.copy(error = e.error) ?: UsageSnapshot(provider, now, error = e.error)
                }
                save(context, provider, current)
                if (notify) Alerts.diff(previous, current).forEach { Notifier.show(context, it) }
            }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun fetch(context: Context, fetcher: WebFetcher, provider: Provider, now: Long): UsageSnapshot =
        when (provider) {
            Provider.CLAUDE -> {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                suspend fun orgId(): String =
                    ClaudeParser.parseOrganizationId(fetcher.get("https://claude.ai/api/organizations"))
                        .also { prefs.edit().putString("claude.org", it).apply() }
                suspend fun usage(org: String) =
                    ClaudeParser.parseUsage(fetcher.get("https://claude.ai/api/organizations/$org/usage"))

                val cached = prefs.getString("claude.org", null)
                val windows = if (cached == null) usage(orgId()) else try {
                    usage(cached)
                } catch (e: FetchException) {
                    // Организация могла смениться после повторного входа.
                    if (e.error != FetchError.NOT_LOGGED_IN) throw e
                    usage(orgId())
                }
                UsageSnapshot(provider, now, windows)
            }
            Provider.CHATGPT -> {
                val session = ChatGptParser.parseSession(fetcher.get("https://chatgpt.com/api/auth/session"))
                val headers = buildMap {
                    put("Authorization", "Bearer ${session.accessToken}")
                    session.accountId?.let { put("ChatGPT-Account-Id", it) }
                }
                val (plan, windows) = ChatGptParser.parseUsage(
                    fetcher.get("https://chatgpt.com/backend-api/wham/usage", headers), now,
                )
                UsageSnapshot(provider, now, windows, plan)
            }
        }

    private fun save(context: Context, provider: Provider, snapshot: UsageSnapshot?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (snapshot == null) prefs.remove("snapshot.${provider.name}") else prefs.putString("snapshot.${provider.name}", json.encodeToString(snapshot))
        prefs.apply()
        _snapshots.value = if (snapshot == null) _snapshots.value - provider else _snapshots.value + (provider to snapshot)
    }

    /** Выход: удаляем cookie сервиса и сохранённые данные. */
    fun logout(context: Context, provider: Provider) {
        val cm = CookieManager.getInstance()
        val host = provider.homeUrl.removePrefix("https://").trimEnd('/')
        cm.getCookie(provider.homeUrl).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            .forEach { name ->
                cm.setCookie(provider.homeUrl, "$name=; Max-Age=0; Path=/")
                cm.setCookie(provider.homeUrl, "$name=; Max-Age=0; Path=/; Domain=.$host")
            }
        cm.flush()
        if (provider == Provider.CLAUDE) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("claude.org").apply()
        save(context, provider, null)
    }
}
