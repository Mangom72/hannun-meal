package kr.han.meal

import org.junit.Test
import org.junit.Assert.*
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.*
import kr.han.meal.domain.scheduling.*
import java.time.*

class MealSelectorTest {
    private fun clock(value: String) = Clock.fixed(LocalDateTime.parse(value).atZone(SEOUL).toInstant(),ZoneOffset.UTC)
    private fun present(d: LocalDate,t: MealType) = Availability.Present(Meal("B10:1",d,t,listOf(Dish("실제 응답 테스트용")),""))
    private fun select(time: String, s: Schedule=Schedule(), f:(LocalDate,MealType)->Availability=::present) = MealSelector(clock(time)).select(s,f)
    private fun check(time: String, date: String, type: MealType, s: Schedule=Schedule()) {
        val result=select(time,s) as Selection.Selected
        assertEquals(LocalDate.parse(date),result.date);assertEquals(type,result.type)
    }
    @Test fun beforeBreakfastBoundary()=check("2026-10-26T07:59:00","2026-10-26",MealType.BREAKFAST)
    @Test fun lunchBoundary()=check("2026-10-26T08:00:00","2026-10-26",MealType.LUNCH)
    @Test fun beforeDinnerBoundary()=check("2026-10-26T12:59:00","2026-10-26",MealType.LUNCH)
    @Test fun dinnerBoundary()=check("2026-10-26T13:00:00","2026-10-26",MealType.DINNER)
    @Test fun beforeTomorrowBoundary()=check("2026-10-26T18:59:00","2026-10-26",MealType.DINNER)
    @Test fun tomorrowBoundary()=check("2026-10-26T19:00:00","2026-10-27",MealType.BREAKFAST)
    @Test fun midnight() {
        check("2026-10-26T23:59:00","2026-10-27",MealType.BREAKFAST)
        check("2026-10-27T00:00:00","2026-10-27",MealType.BREAKFAST)
    }
    @Test fun fridaySkipsWeekend()=check("2026-10-30T19:30:00","2026-11-02",MealType.BREAKFAST)
    @Test fun lunchOnly()=check("2026-10-26T19:30:00","2026-10-27",MealType.LUNCH,Schedule(enabledTypes=setOf(MealType.LUNCH)))
    @Test fun missingSkipped() {
        val r=select("2026-10-26T00:30:00") { d,t -> if(t==MealType.BREAKFAST) Availability.Absent(Instant.EPOCH) else present(d,t) } as Selection.Selected
        assertEquals(MealType.LUNCH,r.type)
    }
    @Test fun missingPreserved() {
        val r=select("2026-10-26T00:30:00",Schedule(missingPolicy=MissingPolicy.PRESERVE)) { _,_ -> Availability.Absent(Instant.EPOCH) } as Selection.Selected
        assertTrue(r.confirmedEmpty);assertNull(r.meal);assertEquals(MealType.BREAKFAST,r.type)
    }
    @Test fun unknownNotEmpty() { assertTrue(select("2026-10-26T09:00:00") { _,_ -> Availability.Unknown } is Selection.NeedsData) }
    @Test fun failedNotEmpty() { assertTrue(select("2026-10-26T09:00:00") { _,_ -> Availability.Failed("연결 실패") } is Selection.Failure) }
    @Test fun consecutiveMissingDays() {
        val r=select("2026-10-26T09:00:00") { d,t -> if(d.dayOfMonth<29) Availability.Absent(Instant.EPOCH) else present(d,t) } as Selection.Selected
        assertEquals(LocalDate.parse("2026-10-29"),r.date)
    }
    @Test fun allDaysExcluded() { assertTrue(select("2026-10-26T09:00:00",Schedule(excludedDays=(1..7).toSet())) is Selection.Warning) }
    @Test fun allMealsExcluded() { assertTrue(select("2026-10-26T09:00:00",Schedule(enabledTypes=emptySet())) is Selection.Warning) }
    @Test fun monthBoundary()=check("2026-09-30T19:00:00","2026-10-01",MealType.BREAKFAST)
    @Test fun yearBoundary()=check("2026-12-31T19:00:00","2027-01-01",MealType.BREAKFAST)
    @Test fun leapYear()=check("2024-02-28T19:00:00","2024-02-29",MealType.BREAKFAST)
    @Test fun customRules() { assertNull(RuleValidator.validate(listOf(TimeRule(0,600,RuleAction()),TimeRule(600,1440,RuleAction(MealType.LUNCH))))) }
    @Test fun crossingMidnightRules() {
        val s=Schedule(rules=listOf(TimeRule(1140,480,RuleAction(MealType.DINNER)),TimeRule(480,1140,RuleAction(MealType.LUNCH))))
        assertNull(RuleValidator.validate(s.rules));check("2026-10-26T00:30:00","2026-10-26",MealType.DINNER,s)
    }
    @Test fun overlappingRejected() { assertNotNull(RuleValidator.validate(defaultRules()+TimeRule(30,90,RuleAction()))) }
    @Test fun gapsRejected() { assertNotNull(RuleValidator.validate(defaultRules().dropLast(1))) }
    @Test fun invalidOffsetsRejected() { assertNotNull(RuleValidator.validate(listOf(TimeRule(0,1440,RuleAction(dayOffset=22))))) }
    @Test fun boundedSearch() {
        var count=0
        val r=select("2026-10-26T00:30:00",Schedule(excludedDays=emptySet())) { _,_ -> count++; Availability.Absent(Instant.EPOCH) }
        assertTrue(r is Selection.Empty);assertEquals(63,count)
    }
    @Test fun nextAvailableUsesEatingTime()=check("2026-10-26T13:00:00","2026-10-26",MealType.DINNER,Schedule(mode=SelectionMode.NEXT))
    @Test fun fixedMode()=check("2026-10-26T00:30:00","2026-10-26",MealType.LUNCH,Schedule(mode=SelectionMode.FIXED))
    @Test fun manualReturnsAtBoundary() {
        val c=clock("2026-10-26T08:00:00")
        check("2026-10-26T08:00:00","2026-10-26",MealType.LUNCH,Schedule(mode=SelectionMode.MANUAL,manualDate="2026-10-28",manualType=MealType.DINNER,manualUntil=c.millis()))
    }
    @Test fun stickyManualStays()=check("2026-10-26T08:00:00","2026-10-28",MealType.DINNER,Schedule(mode=SelectionMode.MANUAL,manualDate="2026-10-28",manualType=MealType.DINNER,manualUntil=0,returnAutomatically=false))
    @Test fun widgetManualReset() { assertEquals(SelectionMode.AUTO,effectiveWidgetSchedule(WidgetConfig(1,manualDate="2026-10-28",manualUntil=0),Instant.now()).mode) }
    @Test fun eatingTimeIndependentFromTransition() {
        val c=clock("2026-10-26T13:00:00")
        assertEquals(Duration.ofMinutes(270),MealTiming.remaining(LocalDate.parse("2026-10-26"),MealType.DINNER,Schedule(),c))
        assertEquals(Duration.ZERO,MealTiming.remaining(LocalDate.parse("2026-10-26"),MealType.LUNCH,Schedule(),c))
    }
    @Test fun countdownMoreThanDay() {
        val now=clock("2026-10-26T13:00:00").instant()
        assertEquals(1000L+90_000_000L,MealTiming.chronometerBase(now.plusSeconds(90_000),now,1000))
    }
    @Test fun clockTimezoneIgnoredInFavorOfSeoul() {
        val c=Clock.fixed(Instant.parse("2026-10-25T23:00:00Z"),ZoneId.of("America/New_York"))
        assertEquals(MealType.LUNCH,(MealSelector(c).select(Schedule(),::present) as Selection.Selected).type)
    }
    @Test fun nextRefreshIsLocalOnly() {
        val c=clock("2026-10-26T07:59:00")
        assertEquals(clock("2026-10-26T08:00:00").instant(),MealTiming.nextRefresh(Schedule(),c))
    }
    @Test fun fixedMealTypeRemainsFixedAcrossMissingDay() {
        val r=select("2026-10-26T10:00:00",Schedule(mode=SelectionMode.FIXED,fixedType=MealType.LUNCH)) {d,t->
            if(d.dayOfMonth==26 && t==MealType.LUNCH) Availability.Absent(Instant.EPOCH) else present(d,t)
        } as Selection.Selected
        assertEquals(LocalDate.parse("2026-10-27"),r.date);assertEquals(MealType.LUNCH,r.type)
    }

}
