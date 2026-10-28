package kr.han.meal.domain.selection

import kr.han.meal.domain.model.*
import java.time.*

object RuleValidator {
    fun validate(rules: List<TimeRule>): String? {
        if (rules.isEmpty()) return "하루 전체를 채우는 규칙이 필요합니다"
        val coverage = IntArray(1440)
        for (r in rules) {
            if(r.start !in 0..1439 || r.end !in 0..1440 || r.start == r.end || r.action.dayOffset !in 0..20) return "시간 또는 날짜 범위가 올바르지 않습니다"
            for (m in 0..1439) if(r.contains(m)) coverage[m]++
        }
        if(coverage.any { it > 1 }) return "겹치는 시간 구간이 있습니다"
        if(coverage.any { it == 0 }) return "규칙이 없는 시간 구간이 있습니다"
        return null
    }
}
/** No IO: unknown coverage stops search and asks the caller to load it. Only confirmed absence can be skipped. */
class MealSelector(private val clock: Clock = Clock.system(SEOUL), private val horizon: Int = 21) {
    fun select(schedule: Schedule, lookup: (LocalDate, MealType) -> Availability): Selection {
        if(schedule.excludedDays.containsAll((1..7).toList())) return Selection.Warning("모든 요일이 제외되어 있습니다. 요일을 하나 이상 허용해 주세요")
        if(schedule.enabledTypes.isEmpty()) return Selection.Warning("표시할 식사 종류를 하나 이상 선택해 주세요")
        RuleValidator.validate(schedule.rules)?.let { return Selection.Warning(it) }
        val now = clock.instant().atZone(SEOUL)
        val minute = now.hour * 60 + now.minute
        val effectiveMode = if(schedule.mode == SelectionMode.MANUAL && schedule.returnAutomatically && schedule.manualUntil?.let { clock.millis() >= it } == true) SelectionMode.AUTO else schedule.mode
        var nextMode = effectiveMode == SelectionMode.NEXT
        val action = when(effectiveMode) {
            SelectionMode.AUTO -> schedule.rules.first { it.contains(minute) }.action.also { nextMode = it.nextAvailable }
            SelectionMode.FIXED -> RuleAction(schedule.fixedType)
            SelectionMode.NEXT -> RuleAction(MealType.BREAKFAST, nextAvailable=true)
            SelectionMode.MANUAL -> RuleAction(schedule.manualType)
        }
        val startDate = if(effectiveMode == SelectionMode.MANUAL) runCatching { LocalDate.parse(schedule.manualDate) }.getOrElse { now.toLocalDate() } else now.toLocalDate().plusDays(action.dayOffset.toLong())
        val startType = if(nextMode) MealType.BREAKFAST else action.type
        for(day in 0 until horizon) {
            val date = startDate.plusDays(day.toLong())
            if(date.dayOfWeek.value in schedule.excludedDays) continue
            for(type in MealType.entries) {
                if(day == 0 && type.ordinal < startType.ordinal) continue
                if(type !in schedule.enabledTypes) continue
                if(effectiveMode == SelectionMode.FIXED && type != schedule.fixedType) continue
                if(nextMode && !date.atTime(eatingTime(schedule,type)).atZone(SEOUL).isAfter(now)) continue
                when(val status = lookup(date,type)) {
                    is Availability.Present -> return Selection.Selected(date,type,status.meal)
                    is Availability.Absent -> if(schedule.missingPolicy == MissingPolicy.PRESERVE || effectiveMode == SelectionMode.MANUAL) return Selection.Selected(date,type,null,true)
                    Availability.Unknown -> return Selection.NeedsData(date,type)
                    is Availability.Failed -> return Selection.Failure(date,status.message)
                }
            }
        }
        return Selection.Empty()
    }
}
fun eatingTime(schedule: Schedule, type: MealType): LocalTime = LocalTime.ofSecondOfDay((schedule.eatingMinutes[type] ?: 720).coerceIn(0,1439)*60L)
fun effectiveWidgetSchedule(config: WidgetConfig, now: Instant): Schedule {
    val active = config.manualDate != null && (!config.schedule.returnAutomatically || config.manualUntil?.let { now.toEpochMilli()<it } == true)
    return if(active) config.schedule.copy(mode=SelectionMode.MANUAL,manualDate=config.manualDate,manualType=config.manualType ?: config.schedule.fixedType,manualUntil=config.manualUntil) else config.schedule
}
fun eligibleDates(start: LocalDate, schedule: Schedule, count: Int): List<LocalDate> {
    if(schedule.excludedDays.size >= 7) return emptyList()
    return (0L..70L).asSequence().map { start.plusDays(it) }.filter { it.dayOfWeek.value !in schedule.excludedDays }.take(count).toList()
}
