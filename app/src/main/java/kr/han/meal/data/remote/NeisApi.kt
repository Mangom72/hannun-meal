package kr.han.meal.data.remote

import kotlinx.serialization.json.*
import kr.han.meal.domain.model.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Legacy response parser retained for fixtures and menu compatibility; runtime requests use MealApiClient.
enum class ErrorKind { AUTH, NETWORK, SERVER, INVALID, CONFIG, RATE_LIMITED }
class NeisException(val kind: ErrorKind, override val message: String) : Exception(message)
data class NeisPage(val total: Int, val rows: List<JsonObject>)
object NeisParser {
    private fun checkResult(result: JsonObject) {
        val code = result.text("CODE")
        if(code.isBlank()) throw NeisException(ErrorKind.INVALID,"NEIS 결과 코드가 없습니다")
        if(code == "INFO-000" || code == "INFO-200") return
        val auth = code in setOf("INFO-100","ERROR-290") || result.text("MESSAGE").contains("인증")
        throw NeisException(if(auth) ErrorKind.AUTH else ErrorKind.SERVER, if(auth) "NEIS 인증키를 확인해 주세요" else "NEIS 오류 ($code): ${result.text("MESSAGE")}")
    }
    fun page(root: JsonObject, name: String): NeisPage {
        root["RESULT"]?.jsonObject?.let {
            checkResult(it)
            if(it.text("CODE") == "INFO-200") return NeisPage(0,emptyList())
            throw NeisException(ErrorKind.INVALID,"NEIS 응답에 데이터 영역이 없습니다")
        }
        val sections = root[name] as? JsonArray ?: throw NeisException(ErrorKind.INVALID,"NEIS 응답 형식이 올바르지 않습니다")
        val head = sections.mapNotNull { (it as? JsonObject)?.get("head") as? JsonArray }.flatten()
        head.mapNotNull { (it as? JsonObject)?.get("RESULT") as? JsonObject }.forEach(::checkResult)
        val total = head.mapNotNull { (it as? JsonObject)?.get("list_total_count")?.jsonPrimitive?.intOrNull }.firstOrNull()
            ?: throw NeisException(ErrorKind.INVALID,"NEIS 전체 건수가 없습니다")
        val rows = sections.mapNotNull { (it as? JsonObject)?.get("row") as? JsonArray }.flatten().map {
            it as? JsonObject ?: throw NeisException(ErrorKind.INVALID,"NEIS 행 형식이 올바르지 않습니다")
        }
        if(total>0 && rows.isEmpty()) throw NeisException(ErrorKind.INVALID,"NEIS 응답에서 행이 누락되었습니다")
        return NeisPage(total,rows)
    }
    fun school(row: JsonObject): School = School(row.required("ATPT_OFCDC_SC_CODE"),row.required("SD_SCHUL_CODE"),row.required("SCHUL_NM"),row.text("ORG_RDNMA") .ifBlank { row.text("LCTN_SC_NM") })
    fun meal(row: JsonObject, school: School): Meal {
        if(row.required("ATPT_OFCDC_SC_CODE") != school.office || row.required("SD_SCHUL_CODE") != school.code) throw NeisException(ErrorKind.INVALID,"요청한 학교와 다른 응답입니다")
        val type = MealType.fromCode(row.required("MMEAL_SC_CODE")) ?: throw NeisException(ErrorKind.INVALID,"알 수 없는 식사 코드입니다")
        val date = runCatching { LocalDate.parse(row.required("MLSV_YMD"),DateTimeFormatter.BASIC_ISO_DATE) }.getOrElse { throw NeisException(ErrorKind.INVALID,"급식 날짜 형식이 올바르지 않습니다") }
        val raw = row.required("DDISH_NM")
        return Meal(school.id,date,type,DishParser.parse(raw),raw,row.text("CAL_INFO"),DishParser.plain(row.text("NTR_INFO")),DishParser.plain(row.text("ORPLC_INFO")))
    }
}
fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonObject.required(key: String): String = text(key).takeIf { it.isNotBlank() } ?: throw NeisException(ErrorKind.INVALID,"필수 응답 필드가 없습니다: $key")
object DishParser {
    private val breaks = Regex("(?i)<br\\s*/?>|</(?:p|div|li)\\s*>")
    // Only parenthesized numeric annotations and trailing dotted lists are allergens; preserve meaningful dish punctuation.
    private val encodedBreaks = Regex("(?i)&lt;br\\s*/?&gt;")
    private val parenthesized = Regex("\\(\\s*(\\d{1,2}(?:[.,\\s]+\\d{1,2})*[.]?)\\s*\\)")
    private val trailing = Regex("(?:\\s+|(?<=[가-힣]))(\\d{1,2}(?:\\.\\d{1,2})+\\.?)\\s*$")
    fun plain(raw: String): String = Jsoup.parse(encodedBreaks.replace(breaks.replace(raw,"\n"),"\n")).wholeText().trim()
    fun parse(raw: String): List<Dish> {
        val decoded = encodedBreaks.replace(raw,"\n")
        return breaks.replace(decoded,"\n").split('\n','\r').mapNotNull { part ->
            val original = Jsoup.parse(part).text().trim()
            if(original.isBlank()) return@mapNotNull null
            val found = (parenthesized.findAll(original).toList() + trailing.findAll(original).toList())
            val allergens = found.flatMap { Regex("\\d+").findAll(it.groupValues[1]).map { n -> n.value.toInt() }.toList() }.filter { it in 1..19 }.distinct().sorted()
            val name = trailing.replace(parenthesized.replace(original, ""), "").replace(Regex("\\s+")," ").trim()
            if(name.isBlank()) null else Dish(name,allergens,original)
        }.distinctBy { it.name to it.allergens }
    }
}
