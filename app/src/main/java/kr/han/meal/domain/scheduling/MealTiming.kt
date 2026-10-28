package kr.han.meal.domain.scheduling

import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.eatingTime
import java.time.*

object MealTiming {
    fun eatingInstant(date: LocalDate, type: MealType, schedule: Schedule): Instant = date.atTime(eatingTime(schedule,type)).atZone(SEOUL).toInstant()
    fun remaining(date: LocalDate, type: MealType, schedule: Schedule, clock: Clock): Duration = Duration.between(clock.instant(),eatingInstant(date,type,schedule)).let { if(it.isNegative) Duration.ZERO else it }
    /** RemoteViews Chronometer uses elapsedRealtime, never epoch milliseconds. */
    fun chronometerBase(target: Instant, now: Instant, elapsedRealtime: Long) = elapsedRealtime + Duration.between(now,target).toMillis().coerceAtLeast(0)
    fun nextRefresh(schedule: Schedule, clock: Clock): Instant {
        val now = clock.instant().atZone(SEOUL)
        val candidates = mutableListOf(now.toLocalDate().plusDays(1).atStartOfDay(SEOUL).toInstant())
        for(d in 0..1) {
            for(m in (schedule.rules.map { it.start } + schedule.eatingMinutes.values)) {
                if(m !in 0..1439) continue
                val instant = now.toLocalDate().plusDays(d.toLong()).atStartOfDay(SEOUL).plusMinutes(m.toLong()).toInstant()
                if(instant > clock.instant()) candidates += instant
            }
        }
        schedule.manualUntil?.let { if(it>clock.millis()) candidates += Instant.ofEpochMilli(it) }
        return candidates.minOrNull()!!
    }
}
