package app.limitsai

import android.app.Application

class LimitsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        UsageRepository.load(this)
        UsageWorker.schedule(this)
    }
}
