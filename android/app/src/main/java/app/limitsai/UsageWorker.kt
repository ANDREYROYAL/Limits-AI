package app.limitsai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Фоновое обновление лимитов по расписанию. */
class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UsageRepository.refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "usage-refresh"
        private const val PREFS = "settings"
        val INTERVALS = listOf(15, 30, 60)

        fun intervalMinutes(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("interval", 15)

        fun schedule(context: Context, minutes: Int = intervalMinutes(context), replace: Boolean = false) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("interval", minutes).apply()
            val request = PeriodicWorkRequestBuilder<UsageWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
