package kr.han.meal.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kr.han.meal.domain.model.*
import java.time.*

@Entity(tableName="meals", primaryKeys=["schoolId","date","type"])
data class MealEntity(val schoolId: String, val date: String, val type: String, val dishes: String, val raw: String, val calories: String, val nutrition: String, val origin: String, val syncedAt: Long) {
    fun domain() = Meal(schoolId,LocalDate.parse(date),MealType.valueOf(type),Json.decodeFromString(dishes),raw,calories,nutrition,origin,Instant.ofEpochMilli(syncedAt))
    companion object { fun from(m: Meal, at: Instant) = MealEntity(m.schoolId,m.date.toString(),m.type.name,Json.encodeToString(m.dishes),m.raw,m.calories,m.nutrition,m.origin,at.toEpochMilli()) }
}
@Entity(tableName="coverage", primaryKeys=["schoolId","date"])
data class CoverageEntity(val schoolId: String, val date: String, val successAt: Long? = null, val attemptedAt: Long, val error: String? = null, val errorKind: String? = null)
@Entity(tableName="monthly_cache",primaryKeys=["schoolId","yearMonth","source"])
data class MonthlyCacheEntity(val schoolId:String,val yearMonth:String,val source:String,val successAt:Long,val freshUntil:Long,val staleUntil:Long)
@Dao interface MealDao {
    @Query("SELECT * FROM meals") fun observeMeals(): Flow<List<MealEntity>>
    @Query("SELECT * FROM coverage") fun observeCoverage(): Flow<List<CoverageEntity>>
    @Query("SELECT * FROM monthly_cache") fun observeMonths():Flow<List<MonthlyCacheEntity>>
    @Query("SELECT * FROM monthly_cache WHERE schoolId=:school AND yearMonth=:month AND source=:source") suspend fun month(school:String,month:String,source:String):MonthlyCacheEntity?
    @Upsert suspend fun putMonth(month:MonthlyCacheEntity)
    @Query("SELECT * FROM meals WHERE schoolId=:schoolId AND date BETWEEN :from AND :to") suspend fun meals(schoolId: String, from: String, to: String): List<MealEntity>
    @Query("SELECT * FROM coverage WHERE schoolId=:schoolId AND date BETWEEN :from AND :to") suspend fun coverage(schoolId: String, from: String, to: String): List<CoverageEntity>
    @Upsert suspend fun putMeals(rows: List<MealEntity>)
    @Upsert suspend fun putCoverage(rows: List<CoverageEntity>)
    @Query("DELETE FROM meals WHERE schoolId=:schoolId AND date BETWEEN :from AND :to") suspend fun deleteRange(schoolId: String, from: String, to: String)
    @Query("DELETE FROM meals") suspend fun clearMeals()
    @Query("DELETE FROM coverage") suspend fun clearCoverage()
    @Query("DELETE FROM monthly_cache") suspend fun clearMonths()
    @Transaction suspend fun saveSuccess(school: String, from: String, to: String, meals: List<MealEntity>, coverage: List<CoverageEntity>) {
        deleteRange(school,from,to); putMeals(meals); putCoverage(coverage)
    }
    @Transaction suspend fun saveMonth(school:String,from:String,to:String,meals:List<MealEntity>,coverage:List<CoverageEntity>,month:MonthlyCacheEntity) {
        saveSuccess(school,from,to,meals,coverage);putMonth(month)
    }
    @Transaction suspend fun clear() { clearMeals(); clearCoverage();clearMonths() }
}
@Database(entities=[MealEntity::class,CoverageEntity::class,MonthlyCacheEntity::class],version=2,exportSchema=true)
abstract class MealDatabase : RoomDatabase() {
    abstract fun dao(): MealDao
    companion object {
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {db.execSQL("CREATE TABLE IF NOT EXISTS `monthly_cache` (`schoolId` TEXT NOT NULL, `yearMonth` TEXT NOT NULL, `source` TEXT NOT NULL, `successAt` INTEGER NOT NULL, `freshUntil` INTEGER NOT NULL, `staleUntil` INTEGER NOT NULL, PRIMARY KEY(`schoolId`, `yearMonth`, `source`))")}
        }
    }
}
