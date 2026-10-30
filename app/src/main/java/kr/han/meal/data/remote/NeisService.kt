package kr.han.meal.data.remote

import kr.han.meal.BuildConfig
import kr.han.meal.domain.model.*
import kr.han.meal.domain.model.SEOUL
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.time.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun publicEndpoint(value: String): HttpUrl {
    val u=value.trim().toHttpUrlOrNull() ?: throw NeisException(ErrorKind.CONFIG,"데이터 서버 주소가 올바르지 않습니다. 앱 업데이트를 확인해 주세요")
    val local=BuildConfig.DEBUG && u.host in setOf("localhost","127.0.0.1","10.0.2.2")
    if((u.scheme!="https" && !local) || u.username.isNotEmpty() || u.password.isNotEmpty() || u.query!=null || u.fragment!=null || u.host=="open.neis.go.kr") throw NeisException(ErrorKind.CONFIG,"데이터 서버 주소가 올바르지 않습니다. 앱 업데이트를 확인해 주세요")
    return u.newBuilder().apply {if(!u.encodedPath.endsWith('/')) addPathSegment("")}.build()
}
class PublicJson(private val http:OkHttpClient) {
    suspend fun get(url:HttpUrl,limit:Long=4L*1024*1024):String=withContext(Dispatchers.IO) {
        val call=http.newCall(Request.Builder().url(url).header("Accept","application/json").header("User-Agent","HannunMeal-Android/${BuildConfig.VERSION_NAME}").build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) {if(continuation.isActive) continuation.resumeWithException(NeisException(ErrorKind.NETWORK,"네트워크에 연결할 수 없습니다. 연결 상태를 확인해 주세요"))}
                override fun onResponse(call:Call,response:Response) {
                    try {
                        val text=response.use {r->
                            if(!r.isSuccessful) throw NeisException(if(r.code==429) ErrorKind.RATE_LIMITED else ErrorKind.SERVER,"서버 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요")
                            val body=r.body ?: throw NeisException(ErrorKind.INVALID,"데이터 응답이 없습니다")
                            val source=body.source();source.request(limit+1)
                            if(source.buffer.size>limit) throw NeisException(ErrorKind.INVALID,"데이터 응답이 너무 큽니다")
                            source.readUtf8()
                        }
                        if(continuation.isActive) continuation.resume(text)
                    } catch(e:Exception) {if(continuation.isActive) continuation.resumeWithException(e)}
                }
            })
        }
    }
}
@Serializable data class MonthlyMealRow(val date:String,val type:MealType,val dishes:List<Dish>,val raw:String,val calories:String="",val nutrition:String="",val origin:String="")
@Serializable data class MonthlyMeals(val schemaVersion:Int,val officeCode:String,val schoolCode:String,val yearMonth:String,val fetchedAt:String,val freshUntil:String,val staleUntil:String,val meals:List<MonthlyMealRow>) {
    fun validate(school:School,month:YearMonth):MonthlyMeals {
        try {
            require(schemaVersion==1 && officeCode==school.office && schoolCode==school.code && yearMonth==month.toString().replace("-",""))
            val fetched=Instant.parse(fetchedAt);val fresh=Instant.parse(freshUntil);val stale=Instant.parse(staleUntil)
            require(!fresh.isBefore(fetched) && !stale.isBefore(fresh) && stale<=fetched.plus(Duration.ofDays(31)))
            require(meals.size<=1000 && meals.map {it.date to it.type}.distinct().size==meals.size)
            meals.forEach {m->require(YearMonth.from(LocalDate.parse(m.date))==month);m.dishes.forEach {d->require(d.name.isNotBlank() && d.allergens.all {it in 1..19})}}
        } catch(e:Exception) {throw NeisException(ErrorKind.INVALID,"급식 서버 응답 형식이 올바르지 않습니다")}
        return this
    }
    fun domain(school:School)=meals.map {Meal(school.id,LocalDate.parse(it.date),it.type,it.dishes,it.raw,it.calories,it.nutrition,it.origin,Instant.parse(fetchedAt))}
}
interface MonthlyMealSource { suspend fun month(endpoint:String,school:School,month:YearMonth,force:Boolean=false):MonthlyMeals; suspend fun clear() {} }
class MealApiClient(private val transport:PublicJson,private val clock:Clock=Clock.system(SEOUL)):MonthlyMealSource {
    private val json=Json {ignoreUnknownKeys=true}
    private val mutex=Mutex()
    private val memory=object:LinkedHashMap<String,MonthlyMeals>(32,.75f,true) {override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,MonthlyMeals>)=size>32}
    override suspend fun month(endpoint:String,school:School,month:YearMonth,force:Boolean):MonthlyMeals=mutex.withLock {
        val base=publicEndpoint(endpoint)
        require(Regex("[A-Z]\\d{2}").matches(school.office) && Regex("[A-Z0-9]{7}").matches(school.code))
        val url=base.newBuilder().addPathSegments("v1/meals").addPathSegment(school.office).addPathSegment(school.code).addPathSegment(month.toString().replace("-","")).build()
        memory[url.toString()]?.takeIf {!force && Instant.parse(it.freshUntil)>clock.instant()}?.let {return@withLock it}
        val response=try {json.decodeFromString<MonthlyMeals>(transport.get(url)).validate(school,month)} catch(e:CancellationException) {throw e} catch(e:NeisException) {throw e} catch(e:Exception) {throw NeisException(ErrorKind.INVALID,"급식 서버 응답을 해석할 수 없습니다")}
        memory[url.toString()]=response
        response
    }
    override suspend fun clear()=mutex.withLock {memory.clear()}
}
