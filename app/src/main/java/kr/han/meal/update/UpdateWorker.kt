package kr.han.meal.update

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import kr.han.meal.core.graph
import java.util.concurrent.TimeUnit

class UpdateWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val g=applicationContext.graph
        val settings=g.updateStore.settings.first()
        if(!settings.config.automatic) return Result.success()
        val result=g.updates.check(settings.config.autoDownload)
        return if(result.retry && runAttemptCount<2) Result.retry() else if(result.success) Result.success() else Result.failure()
    }
}
object UpdateScheduler {
    fun install(context:Context,settings:UpdateSettings) {
        val work=WorkManager.getInstance(context)
        if(!settings.config.automatic || !UpdateRules.validRepository(settings.config.repository)) {work.cancelUniqueWork("app-update-check");return}
        val network=if(settings.config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        work.enqueueUniquePeriodicWork("app-update-check",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(12,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,5,TimeUnit.MINUTES).build())
    }
}
