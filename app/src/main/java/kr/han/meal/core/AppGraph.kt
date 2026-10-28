package kr.han.meal.core

import android.app.Application
import androidx.room.Room
import kr.han.meal.BuildConfig
import kr.han.meal.data.local.*
import kr.han.meal.data.remote.*
import kr.han.meal.data.repository.*
import kr.han.meal.domain.model.*
import kr.han.meal.widget.WidgetRenderer
import kr.han.meal.worker.WorkScheduler
import kr.han.meal.update.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit

class MealApplication: Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
    lateinit var graph: AppGraph; private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
class AppGraph(val app: Application, val clock: Clock = Clock.system(SEOUL), startBackground: Boolean = true) {
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val online = connectivity(app).stateIn(scope,SharingStarted.Eagerly,app.isOnline())
    val settings = SettingsStore(app)
    val preferences = settings.settings.stateIn(scope,SharingStarted.Eagerly,AppSettings())
    val database = Room.databaseBuilder(app,MealDatabase::class.java,"meals.db").addMigrations(MealDatabase.MIGRATION_1_2).build()
    private val json = Json { ignoreUnknownKeys=true; isLenient=false }
    private val http = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val transport=PublicJson(http)
    val schools=SchoolDirectory(transport,File(app.filesDir,"school-directory"),clock)
    val repository = MealRepository(database.dao(),MealApiClient(transport,clock),clock)
    val cache = repository.cache.stateIn(scope,SharingStarted.Eagerly,CacheSnapshot())
    val updateStore = UpdateStore(app)
    val updates = UpdateRepository(updateStore,GitHubTransport(),File(app.cacheDir,"updates"),AndroidApkTrust(app),clock)
    val updateState = updates.state.stateIn(scope,SharingStarted.Eagerly,UpdateState())
    val renderer by lazy { WidgetRenderer(this) }
    fun mealEndpoint(p:AppSettings)=p.mealApiUrl.ifBlank {BuildConfig.MEAL_API_BASE_URL}
    fun directoryEndpoint(p:AppSettings)=p.schoolDirectoryUrl.ifBlank {BuildConfig.SCHOOL_DIRECTORY_BASE_URL}
    fun configured(p:AppSettings)=runCatching {publicEndpoint(mealEndpoint(p));true}.getOrDefault(false)
    init {
        scope.launch {settings.migrateLegacyCredentials();updateStore.migrateLegacyCredentials()}
        if(startBackground) scope.launch {updateStore.settings.collect {UpdateScheduler.install(app,it)}}
        if(startBackground) scope.launch {
            var previousIdentity: Pair<String?,String>? = null
            settings.settings.collect { p ->
                WorkScheduler.install(app,p.syncHours)
                renderer.renderAll()
                val identity=p.school?.id to mealEndpoint(p)
                // A cold process started by a local widget transition must not enqueue network work.
                if(previousIdentity!=null && identity!=previousIdentity && p.school!=null && configured(p)) WorkScheduler.request(app)
                previousIdentity=identity
            }
        }
    }
}
val android.content.Context.graph: AppGraph get() = (applicationContext as MealApplication).graph
