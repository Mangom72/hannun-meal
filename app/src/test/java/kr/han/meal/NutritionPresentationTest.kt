package kr.han.meal

import kr.han.meal.data.remote.DishParser
import kr.han.meal.ui.NutritionSection
import kr.han.meal.ui.nutritionSections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionPresentationTest {
    @Test fun `real NEIS fixture fields retain every normalized entry`() {
        val text = requireNotNull(javaClass.getResource("/fixtures/neis-real-20250901.json")).readText()
        val sections = Json.parseToJsonElement(text).jsonObject.getValue("mealServiceDietInfo").jsonArray
        val rows = sections.flatMap { it.jsonObject["row"]?.jsonArray?.toList().orEmpty() }
        rows.forEach { row ->
            val raw = DishParser.plain(row.jsonObject.getValue("NTR_INFO").jsonPrimitive.content)
            val result = nutritionSections(raw)
            assertEquals(listOf("주요 영양소", "비타민", "무기질"), result.map { it.title })
            assertEquals(raw.lines(), result.flatMap { it.entries })
        }
    }

    @Test fun `precision signs units whitespace and duplicate entries survive grouping`() {
        val lines = listOf(
            "  칼슘(mg) : +201.500  ", "단백질(g) : -0.00", "비타민A(R.E) : 44.20",
            "엽산(ug) : 12.000", "비타민B12(μg) : +0.001", "단백질(g) : -0.00",
            "티아민(mg) : .30", "리보플라빈(mg) : 0.8", "나이아신(mg) : 1.2",
            "나트륨(mg) : 123", "칼륨(mg) : 23", "마그네슘(mg) : 4", "아연(mg) : 2",
            "식이섬유(g) : 3.0", "알 수 없는 안내", "지방산(g) : 1.0",
        )
        val result = nutritionSections(lines.joinToString("\r\n"))
        assertEquals(listOf(lines[1], lines[5]), result[0].entries)
        assertEquals(listOf(lines[2], lines[3], lines[4], lines[6], lines[7], lines[8]), result[1].entries)
        assertEquals(listOf(lines[0]) + lines.subList(9, 13), result[2].entries)
        assertEquals(lines.takeLast(3), result[3].entries)
        assertEquals(lines.groupingBy { it }.eachCount(), result.flatMap { it.entries }.groupingBy { it }.eachCount())
    }

    @Test fun `empty and blank lines do not create sections`() {
        assertEquals(emptyList<NutritionSection>(), nutritionSections(" \r\n\t"))
        assertEquals(listOf(NutritionSection("주요 영양소", listOf("단백질(g) : 1"))), nutritionSections("\n단백질(g) : 1\n\n"))
    }

    @Test fun `unsupported and continuous formats preserve complete raw text`() {
        listOf(
            "  탄수화물(g) : 10 단백질(g) : 2  ",
            "탄수화물(g) : 10<br/>단백질(g) : 2",
            "탄수화물(g) : 10; 단백질(g) : 2",
            "탄수화물(g) | 10\n단백질(g) | 2",
            "  형식을 알 수 없는 원문\n ", "단백질(g) :", "비타민A(R.E : 44.2",
        ).forEach { raw ->
            assertEquals(listOf(NutritionSection("원문", listOf(raw))), nutritionSections(raw))
        }
    }

    @Test fun `unknown labels and ambiguous lines are not guessed or removed`() {
        val lines = listOf("미확인성분(ug) : 0.001", "단백질 추정 : 2", "철분(mg) : 2.5", "단백질(g) :")
        val result = nutritionSections(lines.joinToString("\n"))
        assertEquals(listOf(NutritionSection("무기질", listOf(lines[2])), NutritionSection("기타 정보", listOf(lines[0], lines[1], lines[3]))), result)
    }
}
