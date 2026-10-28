package app.limitsai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.limitsai.core.Provider

/** Вход в сервис во встроенном браузере. Cookie остаются в WebView и используются для запросов. */
class LoginActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val provider = Provider.valueOf(intent.getStringExtra(EXTRA_PROVIDER)!!)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = webView
                if (web != null && web.canGoBack()) web.goBack() else finish()
            }
        })

        setContent {
            LimitsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Вход в ${provider.title}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            TextButton(onClick = { done() }) { Text("Готово") }
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebFetcher.createWebView(ctx).also { web ->
                                    webView = web
                                    web.webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, url: String) {
                                            // Как только появилась сессия и мы ушли со страниц входа — закрываемся сами.
                                            if (UsageRepository.isLoggedIn(provider) && !isAuthPage(url)) done()
                                        }
                                    }
                                    web.loadUrl(provider.loginUrl)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun isAuthPage(url: String) = listOf("login", "auth", "magic-link", "verify", "onboarding").any { it in url }

    private fun done() {
        if (isFinishing) return
        CookieManager.getInstance().flush()
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROVIDER = "provider"
        fun intent(context: Context, provider: Provider) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_PROVIDER, provider.name)
    }
}
