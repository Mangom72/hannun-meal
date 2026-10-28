package kr.han.meal.worker

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import kr.han.meal.core.graph
import kr.han.meal.data.remote.ErrorKind
import kr.han.meal.data.repository.SyncResult
import kr.han.meal.domain.model.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class MealSyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val g=applicationContext.graph
        val p=g.settings.settings.first()
        val widgets=g.settings.widgets.first()
        val schools=(listOfNotNull(p.school)+widgets.mapNotNull { it.school ?: p.school }).distinctBy { it.id }
        if(!g.configured(p) || schools.isEmpty()) { g.renderer.renderAll();return Result.success() }
        val today=LocalDate.now(g.clock)
        val from=inputData.getString("from")?.let(LocalDate::parse) ?: today.minusDays(7)
        val to=inputData.getString("to")?.let(LocalDate::parse) ?: today.plusDays(21)
        var retry=false
        for(school in schools) {
            val r=g.repository.sync(g.mealEndpoint(p),school,from,to,inputData.getBoolean("force",false))
            if(r is SyncResult.Stale) retry=true
            if(r is SyncResult.Failure && r.kind in setOf(ErrorKind.NETWORK,ErrorKind.SERVER,ErrorKind.RATE_LIMITED)) retry=true
        }
        g.renderer.renderAll()
        return if(retry && runAttemptCount<3) Result.retry() else Result.success()
    }
}
class LocalRefreshWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result { applicationContext.graph.renderer.renderAll();return Result.success() }
}
object WorkScheduler {
    fun install(context: Context,hours: Long) {
        val wm=WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork("meal-sync",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MealSyncWorker>(hours.coerceIn(1,24),TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
    fun localRefresh(context:Context,enabled:Boolean) {
        val wm=WorkManager.getInstance(context)
        if(enabled) wm.enqueueUniquePeriodicWork("widget-local-refresh",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<LocalRefreshWorker>(15,TimeUnit.MINUTES).build())
        else wm.cancelUniqueWork("widget-local-refresh")
    }
    fun request(context: Context, force: Boolean=false, from: LocalDate?=null,to: LocalDate?=null) {
        val data=workDataOf("force" to force,"from" to from?.toString(),"to" to to?.toString())
        val name=if(from==null) "meal-manual-sync" else "meal-range-$from-$to"
        WorkManager.getInstance(context).enqueueUniqueWork(name,ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MealSyncWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
}
