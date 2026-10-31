package kr.han.meal.domain.model

import kotlinx.serialization.Serializable
import java.time.*

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
@Serializable enum class MealType(val code: String, val label: String) {
    BREAKFAST("1", "조식"), LUNCH("2", "중식"), DINNER("3", "석식");
    companion object { fun fromCode(code: String) = entries.firstOrNull { it.code == code } }
}
@Serializable data class School(val office: String, val code: String, val name: String, val address: String = "", val type: String = "", val region: String = "") {
    val id get() = "$office:$code"
}
@Serializable data class Dish(val name: String, val allergens: List<Int> = emptyList(), val original: String = name)
data class Meal(val schoolId: String, val date: LocalDate, val type: MealType, val dishes: List<Dish>, val raw: String, val calories: String = "", val nutrition: String = "", val origin: String = "", val syncedAt: Instant = Instant.EPOCH)
@Serializable enum class MissingPolicy { SKIP, PRESERVE }
@Serializable enum class SelectionMode { AUTO, NEXT, FIXED, MANUAL }
@Serializable enum class RangeMode { CALENDAR, ROLLING }
@Serializable enum class DisplayPreset { MINIMAL, STANDARD, DETAILED, CUSTOM }
@Serializable data class DisplayOptions(
    val date: Boolean = true, val weekday: Boolean = true, val school: Boolean = true,
    val mealType: Boolean = true, val eatingTime: Boolean = true, val countdown: Boolean = true,
    val calories: Boolean = false, val allergens: Boolean = false, val lastSync: Boolean = false,
    val nextPreview: Boolean = false, val freshness: Boolean = true
) {
    companion object {
        fun preset(p: DisplayPreset) = when(p) {
            DisplayPreset.MINIMAL -> DisplayOptions(weekday=false, school=false, eatingTime=false, countdown=false, freshness=false)
            DisplayPreset.DETAILED -> DisplayOptions(calories=true, allergens=true, lastSync=true, nextPreview=true)
            else -> DisplayOptions()
        }
    }
}
@Serializable data class RuleAction(val type: MealType = MealType.BREAKFAST, val dayOffset: Int = 0, val nextAvailable: Boolean = false)
/** Minutes since midnight. end=1440 means midnight at the end of the day; start>end wraps midnight. */
@Serializable data class TimeRule(val start: Int, val end: Int, val action: RuleAction) {
    fun contains(minute: Int) = if (start < end) minute >= start && minute < end else minute >= start || minute < end
}
fun defaultRules() = listOf(
    TimeRule(0,480, RuleAction(MealType.BREAKFAST)),
    TimeRule(480,780, RuleAction(MealType.LUNCH)),
    TimeRule(780,1140, RuleAction(MealType.DINNER)),
    TimeRule(1140,1440, RuleAction(MealType.BREAKFAST,1))
)
@Serializable data class Schedule(
    val rules: List<TimeRule> = defaultRules(),
    val excludedDays: Set<Int> = setOf(6,7),
    val enabledTypes: Set<MealType> = MealType.entries.toSet(),
    val missingPolicy: MissingPolicy = MissingPolicy.SKIP,
    val mode: SelectionMode = SelectionMode.AUTO,
    val fixedType: MealType = MealType.LUNCH,
    val manualDate: String? = null,
    val manualType: MealType = MealType.LUNCH,
    val manualUntil: Long? = null,
    val returnAutomatically: Boolean = true,
    val eatingMinutes: Map<MealType,Int> = mapOf(MealType.BREAKFAST to 450, MealType.LUNCH to 720, MealType.DINNER to 1050),
    val hidePassedCountdown: Boolean = false
)
@Serializable data class WidgetConfig(
    val id: Int, val kind: WidgetKind = WidgetKind.NEXT, val school: School? = null,
    val schedule: Schedule = Schedule(), val display: DisplayOptions = DisplayOptions(),
    val preset: DisplayPreset = DisplayPreset.STANDARD, val rangeMode: RangeMode = RangeMode.CALENDAR,
    val weeklyTypes: Set<MealType> = setOf(MealType.LUNCH), val dateOffset: Int = 0,
    val manualDate: String? = null, val manualType: MealType? = null, val manualUntil: Long? = null,
    val liveCountdown: Boolean = false, val opacity: Int = 94, val fontScale: Float = 1f, val compact: Boolean = false, val color: Int = 0,
    val backgroundEnabled: Boolean = true, val backgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.COLOR,
    val customBackgroundColor: Int? = null, val backgroundImageId: String? = null
)
@Serializable enum class WidgetBackgroundMode { COLOR, IMAGE }
@Serializable enum class WidgetKind(val label: String) { NEXT("다음 급식"), DAILY("하루 급식"), WEEKLY_SELECTED("주간 선택 급식"), WEEKLY_ALL("주간 전체 급식") }
@Serializable data class AppSettings(
    val school: School? = null, val mealApiUrl: String = "", val schoolDirectoryUrl: String = "", val schedule: Schedule = Schedule(),
    val display: DisplayOptions = DisplayOptions(), val preset: DisplayPreset = DisplayPreset.STANDARD,
    val theme: String = "SYSTEM", val dynamicColor: Boolean = true, val color: Int = 0,
    val liveCountdown: Boolean = false, val fontScale: Float = 1f, val compact: Boolean = false, val opacity: Int = 94, val syncHours: Long = 6
)
sealed interface Availability {
    data class Present(val meal: Meal) : Availability
    data class Absent(val syncedAt: Instant) : Availability
    data object Unknown : Availability
    data class Failed(val message: String) : Availability
}
sealed interface Selection {
    data class Selected(val date: LocalDate, val type: MealType, val meal: Meal?, val confirmedEmpty: Boolean = false) : Selection
    data class NeedsData(val date: LocalDate, val type: MealType) : Selection
    data class Failure(val date: LocalDate, val message: String) : Selection
    data class Warning(val message: String) : Selection
    data class Empty(val message: String = "21일 안에 표시할 급식이 없습니다") : Selection
}
