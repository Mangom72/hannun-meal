package kr.han.meal

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kr.han.meal.data.remote.*
import kr.han.meal.domain.model.*
import okhttp3.mockwebserver.*
import okhttp3.MediaType.Companion.toMediaType
import org.junit.*
import org.junit.Assert.*
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate

fun fixture(name:String)=object {}.javaClass.getResource("/fixtures/$name.json")!!.readText()
class NeisTest {
    private val school=School("B10","1001","한빛고등학교")
    @Test fun schoolNamesAreNotIdentities() {
        val schools=NeisParser.page(Json.parseToJsonElement(fixture("schools")).jsonObject,"schoolInfo").rows.map(NeisParser::school)
        assertEquals(2,schools.size);assertEquals(schools[0].name,schools[1].name);assertNotEquals(schools[0].id,schools[1].id);assertNotEquals(schools[0].address,schools[1].address)
    }
    @Test fun mealParsingAndOptionalFields() {
        val meals=NeisParser.page(Json.parseToJsonElement(fixture("meals")).jsonObject,"mealServiceDietInfo").rows.map {NeisParser.meal(it,school)}
        assertEquals(2,meals.size);assertEquals("721.5 Kcal",meals[0].calories);assertEquals("",meals[1].calories)
        assertEquals(listOf(5,6),meals[0].dishes[1].allergens);assertEquals("사과 & 배",meals[0].dishes.last().name)
        assertTrue(meals[0].raw.contains("<br"));assertTrue(meals[0].nutrition.contains("\n"))
    }
    @Test fun capturedOfficialMealPayload() {
        val page=NeisParser.page(Json.parseToJsonElement(fixture("neis-real-20250901")).jsonObject,"mealServiceDietInfo")
        val rows=page.rows.map {NeisParser.meal(it,School("B10","7010057","가락고등학교"))}
        assertEquals(5,rows.size);assertEquals("쌀밥",rows.first().dishes.first().name)
        assertEquals(listOf(1,2,5,6,9,10,12,15,16),rows.first().dishes[1].allergens)
    }
    @Test fun emptyIsConfirmed() {assertEquals(0,NeisParser.page(Json.parseToJsonElement(fixture("empty")).jsonObject,"mealServiceDietInfo").total)}
    @Test fun authIsDistinct() {
        val e=assertThrows(NeisException::class.java) {NeisParser.page(Json.parseToJsonElement(fixture("auth")).jsonObject,"mealServiceDietInfo")}
        assertEquals(ErrorKind.AUTH,e.kind)
    }
    @Test fun malformedEnvelopeRejected() {assertThrows(NeisException::class.java){NeisParser.page(buildJsonObject {},"mealServiceDietInfo")}}
    @Test fun missingRowsRejected() {assertThrows(NeisException::class.java){NeisParser.page(Json.parseToJsonElement("""{"mealServiceDietInfo":[{"head":[{"list_total_count":5}]}]}""").jsonObject,"mealServiceDietInfo")}}
    @Test fun dishMarkupEntitiesAndDuplicates() {
        val d=DishParser.parse("<b>볶음밥</b> (1.5.6.)<BR />볶음밥 (1.5.6.)<br>&nbsp;<br/>탕수육 1.5.6.10.<br>떡 &lt;쌀&gt;<br>국 (5)\n과일 &#x26; 채소")
        assertEquals(5,d.size);assertEquals("볶음밥",d[0].name);assertEquals(listOf(1,5,6),d[0].allergens)
        assertEquals(listOf(1,5,6,10),d[1].allergens);assertEquals(listOf(5),d[3].allergens);assertEquals("과일 & 채소",d.last().name)
    }
    @Test fun emptyDishesSafe() {assertTrue(DishParser.parse("<br/> &nbsp; <br>").isEmpty())}
    @Test fun wrongSchoolRejected() {
        val row=NeisParser.page(Json.parseToJsonElement(fixture("meals")).jsonObject,"mealServiceDietInfo").rows.first()
        assertThrows(NeisException::class.java) {NeisParser.meal(row,School("J10","1001","다른 학교"))}
    }
}
