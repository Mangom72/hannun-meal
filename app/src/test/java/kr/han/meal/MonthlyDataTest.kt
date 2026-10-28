package kr.han.meal

import kr.han.meal.data.remote.*
import kr.han.meal.domain.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MonthlyDataTest {
    @Test fun officialAlphanumericSchoolCodeIsRequestedAndCachedWithoutConversion()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(payload().replace("B10","N10").replace("7010057","C035903")))
            val client=MealApiClient(PublicJson(OkHttpClient()),clock)
            val national=school.copy(office="N10",code="C035903")
            repeat(2) {assertEquals("C035903",client.month(server.url("/").toString(),national,YearMonth.of(2026,10)).schoolCode)}
            assertEquals(1,server.requestCount)
            assertEquals("/v1/meals/N10/C035903/202610",server.takeRequest().path)
        } finally {server.shutdown()}
    }
    private val clock=Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),SEOUL)
    private val school=School("B10","7010057","가락고등학교")
    private fun payload(month:String="202610",date:String="2026-10-26",fresh:String="2026-10-26T01:00:00Z")="""{"schemaVersion":1,"officeCode":"B10","schoolCode":"7010057","yearMonth":"$month","fetchedAt":"2026-10-26T00:00:00Z","freshUntil":"$fresh","staleUntil":"2026-10-27T01:00:00Z","meals":[{"date":"$date","type":"LUNCH","dishes":[{"name":"국","allergens":[5,6],"original":"국 (5.6.)"}],"raw":"국 (5.6.)","calories":"700 Kcal","nutrition":"단백질 20g","origin":"국내산"}]}"""
    @Test fun repeatedAndConcurrentMonthAccessMakesOneCredentialFreeRequest()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(payload()))
            val client=MealApiClient(PublicJson(OkHttpClient()),clock)
            val endpoint=server.url("/api/").toString()
            val responses=coroutineScope {(1..20).map {async {client.month(endpoint,school,YearMonth.of(2026,10))}}.awaitAll()}
            assertEquals(1,server.requestCount)
            assertEquals("국",responses[0].domain(school).single().dishes.single().name)
            val request=server.takeRequest()
            assertEquals("/api/v1/meals/B10/7010057/202610",request.path)
            assertEquals("HannunMeal-Android/${BuildConfig.VERSION_NAME}",request.getHeader("User-Agent"))
            assertNull(request.requestUrl!!.query)
            assertNull(request.getHeader("Authorization"))
            assertFalse(request.path!!.contains("KEY"))
        } finally {server.shutdown()}
    }
    @Test fun expiredMonthRefreshesInsteadOfExtendingServerFreshness()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            repeat(2) {server.enqueue(MockResponse().setBody(payload(fresh="2026-10-26T00:00:00Z")))}
            val client=MealApiClient(PublicJson(OkHttpClient()),clock)
            repeat(2) {client.month(server.url("/").toString(),school,YearMonth.of(2026,10))}
            assertEquals(2,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun nextMonthHasItsOwnCacheKeyAndOldMonthStillHitsMemory()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(payload()));server.enqueue(MockResponse().setBody(payload("202611","2026-11-02")))
            val client=MealApiClient(PublicJson(OkHttpClient()),clock)
            val endpoint=server.url("/").toString()
            client.month(endpoint,school,YearMonth.of(2026,10));client.month(endpoint,school,YearMonth.of(2026,11));client.month(endpoint,school,YearMonth.of(2026,10))
            assertEquals(2,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun malformedWrongSchoolOrMonthNeverSucceeds()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            for(body in listOf("{}",payload().replace("7010057","7010058"),payload(date="2026-11-01"),payload().replace("[5,6]","[99]"))) {
                server.enqueue(MockResponse().setBody(body))
                try {MealApiClient(PublicJson(OkHttpClient()),clock).month(server.url("/").toString(),school,YearMonth.of(2026,10));fail("invalid response accepted")} catch(e:NeisException) {assertEquals(ErrorKind.INVALID,e.kind)}
            }
        } finally {server.shutdown()}
    }
    @Test fun endpointsCannotContainCredentialsKeysFragmentsOrDirectNeis() {
        for(url in listOf("https://u:p@api.example/","https://api.example/?KEY=secret","https://api.example/#secret","https://open.neis.go.kr/hub/","http://remote.example/")) assertThrows(NeisException::class.java) {publicEndpoint(url)}
    }
}
