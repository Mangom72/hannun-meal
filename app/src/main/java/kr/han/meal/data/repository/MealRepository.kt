package kr.han.meal.data.repository

import kr.han.meal.data.local.*
import kr.han.meal.data.remote.*
import kr.han.meal.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.*

data class CacheSnapshot(val meals: List<Meal> = emptyList(), val coverage: List<CoverageEntity> = emptyList(), val months:List<MonthlyCacheEntity> = emptyList()) {
    private val indexed by lazy { meals.associateBy { Triple(it.schoolId,it.date,it.type) } }
    private val dates by lazy { coverage.associateBy { it.schoolId to it.date } }
    fun availability(school: String, date: LocalDate, type: MealType): Availability {
        indexed[Triple(school,date,type)]?.let { return Availability.Present(it) }
        val c = dates[school to date.toString()] ?: return Availability.Unknown
        if(c.successAt != null) return Availability.Absent(Instant.ofEpochMilli(c.successAt))
        return c.error?.let { Availability.Failed(it) } ?: Availability.Unknown
    }
    fun lastSync(school: String) = coverage.filter { it.schoolId==school }.mapNotNull { it.successAt }.maxOrNull()
    fun latestError(school: String) = coverage.filter { it.schoolId==school && it.error!=null }.maxByOrNull { it.attemptedAt }?.error
    fun stale(school: String, now: Instant, date: LocalDate?=null):Boolean {
        val at=if(date==null) lastSync(school) else dates[school to date.toString()]?.successAt
        val month=months.filter {it.schoolId==school && (date==null || it.yearMonth==YearMonth.from(date).toString())}.maxByOrNull {it.successAt}
        return month?.let {it.freshUntil<=now.toEpochMilli()} ?: (at?.let { now.toEpochMilli()-it > Duration.ofHours(24).toMillis() } ?: true)
    }
}
sealed interface SyncResult {
    data object Success: SyncResult
    data object Cached: SyncResult
    data class Stale(val message:String):SyncResult
    data class Failure(val kind: ErrorKind, val message: String): SyncResult
}
class MealRepository(private val dao: MealDao, private val service: MonthlyMealSource, private val clock: Clock = Clock.system(SEOUL)) {
    private val mutex=Mutex()
    private val completed=object:LinkedHashMap<String,Pair<Long,SyncResult>>(64,.75f,true) {override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Pair<Long,SyncResult>>)=size>64}
    val cache: Flow<CacheSnapshot> = combine(dao.observeMeals(),dao.observeCoverage(),dao.observeMonths()) { m,c,months -> CacheSnapshot(m.map { it.domain() },c,months) }
    suspend fun snapshot() = cache.first()
    fun needsRefresh(snapshot:CacheSnapshot,endpoint:String,school:School,from:LocalDate,to:LocalDate):Boolean {
        val source=runCatching {publicEndpoint(endpoint).toString()}.getOrNull() ?: return false
        val now=clock.millis()
        return generateSequence(YearMonth.from(from)) {it.plusMonths(1)}.takeWhile {it<=YearMonth.from(to)}.any {month->
            val record=snapshot.months.firstOrNull {it.schoolId==school.id && it.source==source && it.yearMonth==month.toString()}
            val recentFailure=snapshot.coverage.any {it.schoolId==school.id && it.date.startsWith(month.toString()) && it.error!=null && now-it.attemptedAt<60_000}
            !recentFailure && (record==null || record.freshUntil<=now)
        }
    }
    suspend fun sync(endpoint:String,school:School,from:LocalDate,to:LocalDate,force:Boolean=false):SyncResult {
        require(!to.isBefore(from) && Duration.between(from.atStartOfDay(),to.atStartOfDay()).toDays()<=90)
        val began=System.nanoTime()
        return mutex.withLock {
            val source=try {publicEndpoint(endpoint).toString()} catch(e:NeisException) {return@withLock SyncResult.Failure(e.kind,e.message)}
            var result:SyncResult=SyncResult.Cached
            for(month in generateSequence(YearMonth.from(from)) {it.plusMonths(1)}.takeWhile {it<=YearMonth.from(to)}) {
                val key="$source|${school.id}|$month"
                val coalesced=completed[key]?.takeIf {it.first>began}?.second
                if(coalesced!=null) {result=merge(result,coalesced);continue}
                val oldMonth=dao.month(school.id,month.toString(),source)
                if(!force && oldMonth?.freshUntil?.let {it>clock.millis()}==true) continue
                val first=month.atDay(1);val last=month.atEndOfMonth()
                val old=dao.coverage(school.id,first.toString(),last.toString()).associateBy {it.date}
                val dates=(1..month.lengthOfMonth()).map(month::atDay)
                val now=clock.instant()
                val attempt:SyncResult=try {
                    val data=service.month(source,school,month,force).validate(school,month)
                    val fetched=Instant.parse(data.fetchedAt);val fresh=Instant.parse(data.freshUntil);val stale=Instant.parse(data.staleUntil)
                    require(fetched<=now.plusSeconds(300))
                    if(oldMonth!=null && fetched.toEpochMilli()<oldMonth.successAt) SyncResult.Cached
                    else {
                        val message=if(fresh<=clock.instant()) "서버 갱신 지연 · 마지막 성공 데이터 ${data.fetchedAt}" else null
                        dao.saveMonth(school.id,first.toString(),last.toString(),data.domain(school).map {MealEntity.from(it,fetched)},dates.map {CoverageEntity(school.id,it.toString(),fetched.toEpochMilli(),now.toEpochMilli(),message)},MonthlyCacheEntity(school.id,month.toString(),source,fetched.toEpochMilli(),fresh.toEpochMilli(),stale.toEpochMilli()))
                        if(message!=null) SyncResult.Stale(message) else SyncResult.Success
                    }
                } catch(e:CancellationException) {throw e} catch(e:Exception) {
                    val kind=when(e) {is NeisException->e.kind;is IOException->ErrorKind.NETWORK;else->ErrorKind.INVALID}
                    val message=when(kind) {
                        ErrorKind.AUTH,ErrorKind.CONFIG->"급식 서버 연결 설정을 확인해 주세요"
                        ErrorKind.NETWORK->"네트워크 연결 실패 · 저장된 급식을 표시합니다"
                        ErrorKind.SERVER->"급식 서버 오류 · 잠시 후 다시 시도해 주세요"
                        ErrorKind.RATE_LIMITED->"요청이 많습니다 · 저장된 급식을 표시하며 잠시 후 다시 시도합니다"
                        ErrorKind.INVALID->"급식 서버 응답을 해석할 수 없습니다"
                    }
                    dao.putCoverage(dates.map {d->CoverageEntity(school.id,d.toString(),old[d.toString()]?.successAt,now.toEpochMilli(),message,kind.name)})
                    SyncResult.Failure(kind,message)
                }
                completed[key]=System.nanoTime() to attempt
                result=merge(result,attempt)
            }
            result
        }
    }
    private fun merge(old:SyncResult,new:SyncResult):SyncResult=when {old is SyncResult.Failure->old;new is SyncResult.Failure->new;old is SyncResult.Stale->old;new is SyncResult.Stale->new;new is SyncResult.Success->new;else->old}
    suspend fun clear()=mutex.withLock {service.clear();dao.clear();completed.clear()}
}
