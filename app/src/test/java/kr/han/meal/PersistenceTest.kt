package kr.han.meal

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kr.han.meal.data.local.*
import kr.han.meal.data.remote.*
import kr.han.meal.data.repository.*
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.MealSelector
import kr.han.meal.widget.WidgetNavigation
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PersistenceTest {
    private lateinit var db:MealDatabase
    private lateinit var context:Application
    private val clock=Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),SEOUL)
    @Before fun setup(){context=ApplicationProvider.getApplicationContext();db=Room.inMemoryDatabaseBuilder(context,MealDatabase::class.java).allowMainThreadQueries().build()}
    @After fun teardown(){db.close()}
    private class FakeApi:MonthlyMealSource {
        var calls=0;var failure=false
        override suspend fun month(endpoint:String,school:School,month:YearMonth,force:Boolean):MonthlyMeals {
            calls++;delay(20);if(failure) throw java.io.IOException("offline")
            return MonthlyMeals(1,school.office,school.code,month.toString().replace("-",""),"2026-10-26T00:00:00Z","2026-10-26T01:00:00Z","2026-10-27T01:00:00Z",emptyList())
        }
    }
    @Test fun schoolsCannotOverwriteAndUniqueMeals()=runTest {
        val d=LocalDate.parse("2026-10-26")
        val a=Meal("B10:1001",d,MealType.LUNCH,listOf(Dish("테스트 메뉴")),"raw")
        db.dao().putMeals(listOf(MealEntity.from(a,clock.instant()),MealEntity.from(a.copy(schoolId="J10:2001"),clock.instant())))
        db.dao().putMeals(listOf(MealEntity.from(a.copy(calories="700"),clock.instant())))
        assertEquals(1,db.dao().meals(a.schoolId,d.toString(),d.toString()).size)
        assertEquals("700",db.dao().meals(a.schoolId,d.toString(),d.toString()).single().calories)
        assertEquals(1,db.dao().meals("J10:2001",d.toString(),d.toString()).size)
    }
    @Test fun offlineFailurePreservesCachedMealAndCoverage()=runTest {
        val api=FakeApi();val repo=MealRepository(db.dao(),api,clock)
        val school=School("B10","1001","학교");val d=LocalDate.parse("2026-10-26")
        val m=Meal(school.id,d,MealType.LUNCH,listOf(Dish("테스트 메뉴")),"raw")
        db.dao().saveSuccess(school.id,d.toString(),d.toString(),listOf(MealEntity.from(m,clock.instant())),listOf(CoverageEntity(school.id,d.toString(),clock.millis(),clock.millis())))
        api.failure=true
        assertTrue(repo.sync("https://meal.example/",school,d,d,true) is SyncResult.Failure)
        val cache=repo.snapshot()
        assertTrue(cache.availability(school.id,d,MealType.LUNCH) is Availability.Present)
        assertTrue(cache.availability(school.id,d,MealType.BREAKFAST) is Availability.Absent)
        assertNotNull(cache.latestError(school.id))
    }
    @Test fun concurrentOverlappingSyncDeduplicated()=runTest {
        val api=FakeApi();val repo=MealRepository(db.dao(),api,clock)
        val school=School("B10","1001","학교");val d=LocalDate.parse("2026-10-26")
        coroutineScope {awaitAll(async {repo.sync("https://meal.example/",school,d,d.plusDays(7))},async {repo.sync("https://meal.example/",school,d.plusDays(1),d.plusDays(4))})}
        assertEquals(2,api.calls) // The first range crosses October and November.
        assertTrue(repo.snapshot().availability(school.id,d,MealType.LUNCH) is Availability.Absent)
        assertTrue(repo.snapshot().availability(school.id,d.plusMonths(2),MealType.LUNCH) is Availability.Unknown)
    }
    @Test fun localTransitionNeverCallsApi()=runTest {
        val api=FakeApi();val repo=MealRepository(db.dao(),api,clock)
        val cache=repo.snapshot()
        MealSelector(clock).select(Schedule()) {d,t->cache.availability("B10:1001",d,t)}
        assertEquals(0,api.calls)
    }
    @Test fun failedUnknownDateIsNotConfirmedEmpty()=runTest {
        val api=FakeApi().apply {failure=true};val repo=MealRepository(db.dao(),api,clock)
        val school=School("B10","1001","학교");val d=LocalDate.now(clock)
        repo.sync("https://meal.example/",school,d,d)
        assertTrue(repo.snapshot().availability(school.id,d,MealType.LUNCH) is Availability.Failed)
    }
    @Test fun widgetPersistenceIndependenceAndRemoval()=runTest {
        val file=File(context.cacheDir,"prefs-${System.nanoTime()}.preferences_pb")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val dataStore=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
        val store=SettingsStore(dataStore)
        try {
            store.saveWidget(WidgetConfig(101,WidgetKind.NEXT,School("B10","1","학교 A")))
            store.saveWidget(WidgetConfig(102,WidgetKind.WEEKLY_SELECTED,School("J10","2","학교 B")))
            store.updateWidget(101) {it.copy(opacity=50)}
            val reloaded=SettingsStore(dataStore).widgets.first()
            assertEquals(50,reloaded.first {it.id==101}.opacity);assertEquals(94,reloaded.first {it.id==102}.opacity)
            assertEquals("학교 B",reloaded.first {it.id==102}.school!!.name)
            store.removeWidget(101);assertEquals(listOf(102),store.widgets.first().map {it.id})
        } finally {scope.cancel();file.delete()}
    }
    @Test fun widgetManualNavigationAndAutoReturnAreIsolated() {
        val cfg=WidgetConfig(101)
        val date=LocalDate.now(clock)
        val lookup:(LocalDate,MealType)->Availability={d,t->Availability.Present(Meal("a",d,t,emptyList(),""))}
        val selection=Selection.Selected(date,MealType.LUNCH,null)
        val next=WidgetNavigation.move(cfg,selection,clock,1,lookup)
        assertEquals(date.toString(),next.manualDate);assertEquals(MealType.DINNER,next.manualType)
        assertNull(cfg.manualDate);assertNotNull(next.manualUntil)
        assertEquals(SelectionMode.AUTO,kr.han.meal.domain.selection.effectiveWidgetSchedule(next,Instant.ofEpochMilli(next.manualUntil!!)).mode)
        val prev=WidgetNavigation.move(cfg,selection,clock,-1,lookup)
        assertEquals(MealType.BREAKFAST,prev.manualType)
    }
    @Test fun monthCacheSurvivesRepositoryRestartAndEveryDayUsesTheSameMonth()=runTest {
        val school=School("B10","7010057","학교");val d=LocalDate.parse("2026-10-26")
        val first=FakeApi();val repo=MealRepository(db.dao(),first,clock)
        repo.sync("https://meal.example/",school,d,d)
        val second=FakeApi();val restarted=MealRepository(db.dao(),second,clock)
        assertEquals(SyncResult.Cached,restarted.sync("https://meal.example/",school,d.minusDays(20),d.plusDays(5)))
        assertEquals(1,first.calls);assertEquals(0,second.calls)
        val cached=restarted.snapshot()
        assertFalse(restarted.needsRefresh(cached,"https://meal.example/",school,d.minusDays(20),d))
        assertTrue(restarted.needsRefresh(cached,"https://other.example/",school,d,d))
        assertTrue(restarted.needsRefresh(cached,"https://meal.example/",school,d.plusMonths(1),d.plusMonths(1)))
    }
    @Test fun expiredPersistentMonthRefreshesAndConcurrentForceRequestsCoalesce()=runTest {
        val school=School("B10","7010057","학교");val d=LocalDate.parse("2026-10-26")
        val api=FakeApi();val repo=MealRepository(db.dao(),api,clock)
        coroutineScope {awaitAll(async {repo.sync("https://meal.example/",school,d,d,true)},async {repo.sync("https://meal.example/",school,d,d,true)})}
        assertEquals(1,api.calls)
        val expiredApi=FakeApi()
        val expired=MealRepository(db.dao(),expiredApi,Clock.offset(clock,Duration.ofHours(2)))
        assertTrue(expired.sync("https://meal.example/",school,d,d) is SyncResult.Stale)
        assertEquals(1,expiredApi.calls)
        assertEquals(clock.millis(),db.dao().month(school.id,"2026-10","https://meal.example/")!!.successAt)
    }
    @Test fun legacyApiKeyRemovalPreservesSchoolThemeScheduleAndWidget()=runTest {
        val file=File(context.cacheDir,"legacy-${System.nanoTime()}.preferences_pb")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val data=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
        try {
            val key=androidx.datastore.preferences.core.stringPreferencesKey("app")
            data.updateData {old->old.toMutablePreferences().apply {this[key]="""{"school":{"office":"B10","code":"7010057","name":"기존 학교"},"apiKey":"old-private-key","theme":"DARK","syncHours":12,"unknownSavedField":"keep"}"""}}
            val store=SettingsStore(data)
            store.saveWidget(WidgetConfig(301,WidgetKind.NEXT,School("J10","7010058","위젯 학교")))
            store.migrateLegacyCredentials()
            val raw=data.data.first()[key]!!
            assertFalse(raw.contains("old-private-key"));assertFalse(raw.contains("apiKey"));assertTrue(raw.contains("unknownSavedField"))
            assertEquals("기존 학교",store.settings.first().school!!.name);assertEquals("DARK",store.settings.first().theme);assertEquals(12L,store.settings.first().syncHours)
            assertEquals("위젯 학교",store.widgets.first().single().school!!.name)
        } finally {scope.cancel();file.delete()}
    }
    @Test fun roomVersionOneMigrationPreservesMealsAndCoverage() {
        val name="migration-${System.nanoTime()}.db"
        val old=context.openOrCreateDatabase(name,0,null)
        val schema=Json.parseToJsonElement(File("schemas/kr.han.meal.data.local.MealDatabase/1.json").readText()).jsonObject["database"]!!.jsonObject
        schema["entities"]!!.jsonArray.forEach {entry->val e=entry.jsonObject;old.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",e["tableName"]!!.jsonPrimitive.content))}
        old.execSQL("INSERT INTO meals VALUES ('B10:7010057','2026-10-26','LUNCH','[]','기존 메뉴','700','','',10)")
        old.execSQL("INSERT INTO coverage VALUES ('B10:7010057','2026-10-26',10,10,NULL,NULL)")
        old.version=1;old.close()
        val upgraded=Room.databaseBuilder(context,MealDatabase::class.java,name).addMigrations(MealDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {runBlocking {assertEquals("기존 메뉴",upgraded.dao().meals("B10:7010057","2026-10-26","2026-10-26").single().raw);assertEquals(10L,upgraded.dao().coverage("B10:7010057","2026-10-26","2026-10-26").single().successAt)}} finally {upgraded.close();context.deleteDatabase(name)}
    }

}
