package app.limitsai

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.limitsai.core.FetchError
import app.limitsai.core.FetchException
import app.limitsai.core.looksLikeChallenge
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Загружает URL в невидимом WebView и возвращает текст ответа.
 *
 * Запрос идёт тем же браузерным движком и с теми же cookie, что и вход в сервис,
 * поэтому сервис видит обычный браузер, а не скрипт.
 */
class WebFetcher(private val context: Context) {

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.Main) {
        var sawChallenge = false
        try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val web = createWebView(context)
                    var done = false
                    fun finish(result: Result<String>) {
                        if (done) return
                        done = true
                        web.stopLoading()
                        web.destroy()
                        cont.resume(result)
                    }
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (done) return
                            view.evaluateJavascript(READ_BODY) { raw ->
                                val text = runCatching { Json.decodeFromString<String?>(raw) }.getOrNull().orEmpty()
                                // На странице проверки Cloudflare ждём: после неё браузер сам перейдёт к ответу.
                                if (looksLikeChallenge(text)) sawChallenge = true else finish(Result.success(text))
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) finish(Result.failure(FetchException(FetchError.NETWORK)))
                        }
                    }
                    cont.invokeOnCancellation { if (!done) { done = true; web.destroy() } }
                    web.loadUrl(url, headers)
                }
            }.getOrThrow()
        } catch (e: TimeoutCancellationException) {
            throw FetchException(if (sawChallenge) FetchError.BLOCKED else FetchError.NETWORK, e)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 45_000L
        private const val READ_BODY =
            "(function(){var p=document.querySelector('pre');return p?p.innerText:(document.body?document.body.innerText:'');})()"

        /** WebView с настройками, общими для входа и фоновых запросов. */
        @SuppressLint("SetJavaScriptEnabled")
        fun createWebView(context: Context): WebView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            // Без пометки "wv" сайты (и вход через Google) реже отказывают встроенному браузеру.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }
}
