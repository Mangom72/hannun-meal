package kr.han.meal

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kr.han.meal.data.local.MealDatabase
import kr.han.meal.data.remote.*
import kr.han.meal.data.repository.*
import kr.han.meal.domain.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MealIntegrityTest {
    private lateinit var db:MealDatabase
    private val clock=Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),SEOUL)
    private val date=LocalDate.parse("2026-10-26")
    private val school=School("B10","7010057","학교 A")
    private val other=School("J10","7010058","학교 B")
    private val endpoint="https://meal.example/"

    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),MealDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun teardown() {db.close()}

    private fun row(day:LocalDate=date,type:MealType=MealType.LUNCH)=MonthlyMealRow(
        day.toString(),type,listOf(Dish("국",listOf(5)),Dish("국",listOf(6))),"국 (5.) / 국 (6.)")
    private fun response(s:School,rows:List<MonthlyMealRow>)=MonthlyMeals(
        1,s.office,s.code,"202610","2026-10-26T00:00:00Z",
        "2026-10-26T01:00:00Z","2026-10-27T01:00:00Z",rows)
    private class Source(var load:suspend (School)->MonthlyMeals):MonthlyMealSource {
        override suspend fun month(endpoint:String,school:School,month:YearMonth,force:Boolean)=load(school)
    }

    @Test fun identicalAndConflictingDuplicateTuplesFailBeforeReplacingRoomMonth()=runTest {
        val original=row()
        val source=Source {response(it,listOf(original))}
        val repo=MealRepository(db.dao(),source,clock)
        assertEquals(SyncResult.Success,repo.sync(endpoint,school,date,date))
        val saved=repo.snapshot()
        for(duplicate in listOf(original,original.copy(dishes=listOf(Dish("다른 국")),raw="다른 국"))) {
            source.load={response(it,listOf(original,duplicate))}
            val failure=repo.sync(endpoint,school,date,date,true) as SyncResult.Failure
            assertEquals(ErrorKind.INVALID,failure.kind)
            val cache=repo.snapshot()
            assertEquals(saved.meals,cache.meals)
            assertEquals(saved.months,cache.months)
            assertEquals(saved.lastSync(school.id),cache.lastSync(school.id))
            assertEquals(failure.message,cache.latestError(school.id))
            assertTrue(cache.availability(school.id,date,MealType.LUNCH) is Availability.Present)
            assertTrue(cache.availability(school.id,date,MealType.BREAKFAST) is Availability.Absent)
        }
        assertEquals(saved.meals,MealRepository(db.dao(),source,clock).snapshot().meals)
    }

    @Test fun datesSchoolsAndMealTypesRemainDistinctAndSameNameAllergensSurvive()=runTest {
        // Deliberately reverse wire order: presentation traverses MealType.entries, not SQL/wire order.
        val rows=listOf(date,date.plusDays(1)).flatMap {day->MealType.entries.reversed().map {row(day,it)}}
        val source=Source {response(it,rows)}
        val repo=MealRepository(db.dao(),source,clock)
        for(s in listOf(school,other)) assertEquals(SyncResult.Success,repo.sync(endpoint,s,date,date))
        val cache=MealRepository(db.dao(),source,clock).snapshot()
        assertEquals(listOf(MealType.BREAKFAST,MealType.LUNCH,MealType.DINNER),MealType.entries.toList())
        assertEquals(12,cache.meals.size)
        assertEquals(12,cache.meals.map {Triple(it.schoolId,it.date,it.type)}.distinct().size)
        for(s in listOf(school,other)) for(day in listOf(date,date.plusDays(1))) {
            val ordered=MealType.entries.map {type->
                val availability=cache.availability(s.id,day,type)
                assertFalse(availability is Availability.Absent)
                val meal=(availability as Availability.Present).meal
                assertEquals(s.id,meal.schoolId)
                assertEquals(day,meal.date)
                assertEquals(listOf(Dish("국",listOf(5)),Dish("국",listOf(6))),meal.dishes)
                meal.type
            }
            assertEquals(MealType.entries.toList(),ordered)
        }
    }

    @Test fun unknownSuccessfulEmptyAndFailedAreDifferentAndFailureKeepsOldMenus()=runTest {
        val source=Source {response(it,emptyList())}
        val repo=MealRepository(db.dao(),source,clock)
        assertTrue(repo.snapshot().availability(school.id,date,MealType.LUNCH) is Availability.Unknown)
        assertEquals(SyncResult.Success,repo.sync(endpoint,school,date,date))
        for(type in MealType.entries) assertTrue(repo.snapshot().availability(school.id,date,type) is Availability.Absent)
        source.load={throw IOException("offline")}
        val failure=repo.sync(endpoint,other,date,date) as SyncResult.Failure
        assertEquals(ErrorKind.NETWORK,failure.kind)
        assertTrue(repo.snapshot().availability(other.id,date,MealType.LUNCH) is Availability.Failed)
        assertTrue(repo.snapshot().availability(school.id,date,MealType.LUNCH) is Availability.Absent)
        source.load={response(it,listOf(row()))}
        assertEquals(SyncResult.Success,repo.sync(endpoint,school,date,date,true))
        val saved=repo.snapshot()
        source.load={throw IOException("offline")}
        val refresh=repo.sync(endpoint,school,date,date,true) as SyncResult.Failure
        val cache=repo.snapshot()
        assertEquals(saved.meals,cache.meals)
        assertEquals(saved.months,cache.months)
        assertEquals(refresh.message,cache.latestError(school.id))
        assertTrue(cache.availability(school.id,date,MealType.LUNCH) is Availability.Present)
    }

    @Test fun lateSchoolAResponseCannotReplaceSchoolBCacheAcrossRepositoryInstances()=runTest {
        // One repository serializes requests with a mutex. Separate instances exercise a genuinely
        // late completion against the same Room database without assuming concurrent calls within it.
        val started=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val source=Source {s->
            if(s.id==school.id) {started.complete(Unit);release.await()}
            response(s,listOf(row().copy(raw=s.name)))
        }
        val a=MealRepository(db.dao(),source,clock)
        val b=MealRepository(db.dao(),source,clock)
        val pending=async {a.sync(endpoint,school,date,date)}
        try {
            started.await()
            assertEquals(SyncResult.Success,b.sync(endpoint,other,date,date))
            val before=b.snapshot().meals.single()
            assertEquals(other.id,before.schoolId)
            release.complete(Unit)
            assertEquals(SyncResult.Success,pending.await())
            val after=b.snapshot()
            assertEquals(before,after.meals.single {it.schoolId==other.id})
            assertEquals(school.name,after.meals.single {it.schoolId==school.id}.raw)
            assertEquals(setOf(school.id,other.id),after.months.map {it.schoolId}.toSet())
        } finally {release.complete(Unit);pending.cancel()}
    }
}
