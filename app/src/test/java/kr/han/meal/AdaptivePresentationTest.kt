package kr.han.meal

import kr.han.meal.ui.*
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.MealSelector
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AdaptivePresentationTest {
    @Test fun childScreensKeepTheirOriginatingNavigationDestinationSelected() {
        assertEquals("home",navigationRoot("school",listOf("home")))
        assertEquals("weekly",navigationRoot("school",listOf("weekly")))
        assertEquals("settings",navigationRoot("widgets",listOf("settings")))
        assertEquals("settings",navigationRoot("appearance",emptyList()))
    }
    @Test fun shortOrLargeTextWindowsCanScrollTheHeaderToReachMeals() {
        assertTrue(weeklyHeaderScrolls(280f,1f))
        assertTrue(weeklyHeaderScrolls(640f,1.82f))
        assertFalse(weeklyHeaderScrolls(640f,1f))
    }
    private val monday=LocalDate.parse("2026-10-26")
    @Test fun weeklyComparisonKeepsSelectionInViewAndDatesInOrderAtWeekEdges() {
        val dates=weekDates(monday,setOf(6,7),false)
        assertEquals(listOf(monday.plusDays(4)),comparisonDates(dates,dates.last(),1))
        assertEquals(dates.takeLast(3),comparisonDates(dates,dates.last(),3))
        assertEquals(dates.take(2),comparisonDates(dates,dates.first(),2))
        assertEquals(emptyList<LocalDate>(),comparisonDates(emptyList(),monday,3))
    }
    @Test fun weeklyNavigationSpansMonthAndCanExplicitlyInspectExcludedWeekends() {
        val days=weekDates(LocalDate.parse("2026-11-01"),setOf(6,7),true)
        assertEquals(monday,days.first());assertEquals(LocalDate.parse("2026-11-01"),days.last());assertEquals(7,days.size)
        assertEquals(5,weekDates(days.last(),setOf(6,7),false).size)
        assertTrue(weekDates(monday,(1..7).toSet(),false).isEmpty())
    }
    @Test fun previewUsesRealSelectionAcrossMidnightAndMissingDataWithoutInventingMeals() {
        val clock=Clock.fixed(Instant.parse("2026-10-28T15:00:00Z"),SEOUL)
        val needs=MealSelector(clock).select(Schedule()) {_,_->Availability.Unknown}
        assertEquals("10/29 (목) 조식\n조회 필요",schedulePreviewLabel(needs))
        val absent=MealSelector(clock).select(Schedule(missingPolicy=MissingPolicy.PRESERVE)) {_,_->Availability.Absent(clock.instant())}
        assertTrue(schedulePreviewLabel(absent).endsWith("등록된 급식 없음"))
        val failed=MealSelector(clock).select(Schedule()) {_,_->Availability.Failed("연결 실패")}
        assertEquals("연결 실패",schedulePreviewLabel(failed))
    }
    @Test fun previewRetainsCustomScheduleAndAppearanceWithoutSavingAWidget() {
        val rules=listOf(TimeRule(1140,480,RuleAction(MealType.DINNER)),TimeRule(480,1140,RuleAction(MealType.LUNCH)))
        val p=AppSettings(schedule=Schedule(rules=rules),fontScale=1.4f,opacity=65,compact=true,color=2,display=DisplayOptions.preset(DisplayPreset.DETAILED))
        WidgetKind.entries.forEach {kind->val config=previewConfig(p,kind)
            assertTrue(config.id<0);assertEquals(kind,config.kind);assertEquals(p.schedule,config.schedule)
            assertEquals(p.display,config.display);assertEquals(65,config.opacity);assertTrue(config.compact);assertEquals(1.4f,config.fontScale)
        }
        assertEquals(4,WidgetKind.entries.map {previewConfig(p,it).id}.distinct().size)
    }
}
