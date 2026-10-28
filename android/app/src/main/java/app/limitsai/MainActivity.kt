package app.limitsai

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.limitsai.core.FetchError
import app.limitsai.core.Format
import app.limitsai.core.Provider
import app.limitsai.core.UsageSnapshot
import app.limitsai.core.UsageWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LimitsTheme { Surface(Modifier.fillMaxSize()) { MainScreen() } } }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { UsageRepository.refreshAll(applicationContext) }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshots by UsageRepository.snapshots.collectAsState()
    val refreshing by UsageRepository.refreshing.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var interval by remember { mutableIntStateOf(UsageWorker.intervalMinutes(context)) }
    // Состояние входа хранится в cookie WebView, поэтому перечитываем его при каждом изменении снимков.
    val loggedIn = remember(snapshots, now) { Provider.entries.associateWith { UsageRepository.isLoggedIn(it) } }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        now = System.currentTimeMillis()
        scope.launch { UsageRepository.refreshAll(context.applicationContext, notify = false) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Limits AI", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = !refreshing,
                    onClick = { scope.launch { UsageRepository.refreshAll(context.applicationContext) } },
                ) { Text(if (refreshing) "Обновление…" else "Обновить") }
            }
        }
        items(Provider.entries) { provider ->
            ProviderCard(
                provider = provider,
                snapshot = snapshots[provider],
                loggedIn = loggedIn.getValue(provider),
                now = now,
                onLogin = { login.launch(LoginActivity.intent(context, provider)) },
                onLogout = {
                    UsageRepository.logout(context, provider)
                    now = System.currentTimeMillis()
                },
            )
        }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("Проверять в фоне каждые", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageWorker.INTERVALS.forEach { minutes ->
                        FilterChip(
                            selected = interval == minutes,
                            onClick = {
                                interval = minutes
                                UsageWorker.schedule(context, minutes, replace = true)
                            },
                            label = { Text("$minutes мин") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Уведомления приходят при расходе 80 %, при исчерпании и когда лимит восстановился. " +
                        "Данные берутся со страниц claude.ai и chatgpt.com под вашим входом и хранятся только на телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    snapshot: UsageSnapshot?,
    loggedIn: Boolean,
    now: Long,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(provider.title, style = MaterialTheme.typography.titleMedium)
                    val sub = listOfNotNull(
                        snapshot?.plan?.replaceFirstChar { it.uppercase() },
                        snapshot?.takeIf { it.windows.isNotEmpty() }?.let { "обновлено ${Format.clock(it.fetchedAt, now)}" },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loggedIn) TextButton(onClick = onLogout) { Text("Выйти") }
            }

            when {
                !loggedIn -> {
                    Text("Войдите в аккаунт, и приложение само будет следить за лимитами.")
                    Button(onClick = onLogin) { Text("Войти в ${provider.title}") }
                }
                snapshot == null -> Text("Загрузка…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    snapshot.windows.forEach { WindowRow(it, now) }
                    if (snapshot.windows.isEmpty() && snapshot.error == null) {
                        Text("Лимитов пока нет: сервис ещё ничего не посчитал.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    snapshot.error?.let { error ->
                        Text(error.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        if (error == FetchError.NOT_LOGGED_IN) Button(onClick = onLogin) { Text("Войти заново") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowRow(w: UsageWindow, now: Long) {
    // Если время сброса прошло, а свежих данных ещё нет — показываем, что лимит уже должен был обнулиться.
    val resetsAt = w.resetsAt
    val expired = resetsAt != null && resetsAt <= now
    val used = if (expired) 0.0 else w.usedPercent
    val color = when {
        used >= 100 -> MaterialTheme.colorScheme.error
        used >= 80 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(w.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${used.toInt()} %", style = MaterialTheme.typography.titleMedium, color = color)
        }
        LinearProgressIndicator(
            progress = { (used / 100).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        val reset = when {
            resetsAt == null -> null
            expired -> "Лимит должен был сброситься, обновите"
            else -> "Сброс через ${Format.duration(resetsAt - now)} · ${Format.clock(resetsAt, now)}"
        }
        reset?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
