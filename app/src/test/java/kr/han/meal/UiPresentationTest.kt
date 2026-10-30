package kr.han.meal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kr.han.meal.domain.model.*
import kr.han.meal.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.Instant

class UiPresentationTest {
    @Test fun focusedDateContextDoesNotCallTomorrowsAutomaticMealToday() {
        val today=LocalDate.parse("2026-12-31")
        assertEquals("오늘",focusedDateContext(today,today))
        assertEquals("내일",focusedDateContext(LocalDate.parse("2027-01-01"),today))
        assertEquals("이후 날짜",focusedDateContext(today.plusDays(4),today))
        assertEquals("지난 날짜",focusedDateContext(today.minusDays(1),today))
        assertEquals("자동 선택",selectionModeLabel(Schedule(mode=SelectionMode.AUTO),0L))
        assertEquals("다음 식사 선택",selectionModeLabel(Schedule(mode=SelectionMode.NEXT),0L))
        assertEquals("식사 고정",selectionModeLabel(Schedule(mode=SelectionMode.FIXED),0L))
        SelectionMode.entries.forEach {assertEquals("수동 선택",selectionModeLabel(Schedule(mode=it),0L,manuallyBrowsing=true))}
    }
    @Test fun manualModeLabelMatchesAutomaticReturnAtTheExactExpiryBoundary() {
        val schedule=Schedule(mode=SelectionMode.MANUAL,manualUntil=1000L,returnAutomatically=true)
        assertEquals("수동 선택",selectionModeLabel(schedule,999L))
        assertEquals("자동 선택",selectionModeLabel(schedule,1000L))
        assertEquals("자동 선택",selectionModeLabel(schedule,1001L))
        for(now in listOf(999L,1000L,1001L)) {
            assertEquals("수동 선택",selectionModeLabel(schedule.copy(returnAutomatically=false),now))
            assertEquals("수동 선택",selectionModeLabel(schedule,now,manuallyBrowsing=true))
        }
        assertEquals("수동 선택",selectionModeLabel(schedule.copy(manualUntil=null),1001L))
    }
    @Test fun failedManualMealKeepsItsTypeUntilExpiryIncludingStickySelection() {
        val date=LocalDate.parse("2026-10-31")
        val schedule=Schedule(mode=SelectionMode.MANUAL,manualDate=date.toString(),manualType=MealType.DINNER,fixedType=MealType.LUNCH,manualUntil=1000L)
        val failure=Selection.Failure(date,"연결 실패")
        fun focus(s:Schedule,now:Long)=mealFocus(failure,date,selectionFallbackType(s,now))
        assertEquals(SelectionMode.MANUAL,effectiveSelectionMode(schedule,999L))
        assertEquals(MealFocus(date,MealType.DINNER),focus(schedule,999L))
        for(now in listOf(1000L,1001L)) {
            assertEquals(SelectionMode.AUTO,effectiveSelectionMode(schedule,now))
            assertEquals(MealFocus(date,MealType.LUNCH),focus(schedule,now))
        }
        for(now in listOf(999L,1000L,1001L)) {
            assertEquals(MealFocus(date,MealType.DINNER),focus(schedule.copy(returnAutomatically=false),now))
            assertEquals(MealFocus(date,MealType.DINNER),focus(schedule.copy(manualUntil=null),now))
        }
    }
    @Test fun weeklyActionsDistinguishMissingFailedAndConfirmedAbsentWithoutClaimingLoading() {
        val date=LocalDate.parse("2026-11-02")
        val meal=Meal("N10:C035903",date,MealType.LUNCH,listOf(Dish("메뉴")),"메뉴")
        val present=Availability.Present(meal)
        assertEquals(MealEntryAction.DETAIL,mealEntryAction(present))
        assertEquals(MealEntryAction.DOWNLOAD,mealEntryAction(Availability.Unknown))
        assertEquals(MealEntryAction.RETRY,mealEntryAction(Availability.Failed("연결 실패")))
        assertNull(mealEntryAction(Availability.Absent(Instant.EPOCH)))
        assertEquals("11월 2일 월요일 중식 알레르기·상세 보기",mealActionLabel(date,MealType.LUNCH,present))
        assertEquals("11월 2일 월요일 석식 급식 내려받기",mealActionLabel(date,MealType.DINNER,Availability.Unknown))
        assertEquals("아직 내려받지 않은 날짜입니다",stateText(Availability.Unknown))
        assertEquals("연결 실패",stateText(Availability.Failed("연결 실패")))
    }
    @Test fun homeFollowsTheEngineAcrossTodayNextDayAndUnknownData() {
        val today=LocalDate.parse("2026-10-28")
        val tomorrow=today.plusDays(1)
        assertEquals(MealFocus(today,MealType.LUNCH),mealFocus(Selection.Selected(today,MealType.LUNCH,null,true),today))
        assertEquals(MealFocus(tomorrow,MealType.BREAKFAST),mealFocus(Selection.NeedsData(tomorrow,MealType.BREAKFAST),today))
        assertEquals(MealFocus(tomorrow,MealType.DINNER),mealFocus(Selection.Selected(tomorrow,MealType.DINNER,null),today))
        assertEquals(tomorrow,mealFocus(Selection.Failure(tomorrow,"네트워크 오류"),today).date)
    }
    @Test fun allergenDescriptionsRetainNumbersAndNamesWithoutInventingUnknownFoods() {
        assertEquals("1 난류 · 5 대두 · 19 잣",allergyLabel(listOf(1,5,5,19,0,20)))
        assertEquals("",allergyLabel(emptyList()))
    }
    @Test fun everyBrandPaletteHasReadableTextInBothModes() {
        for(dark in listOf(false,true)) for(preset in 0..2) {
            val c=mealColors(dark,preset)
            listOf(c.onPrimary to c.primary,c.onPrimaryContainer to c.primaryContainer,c.onSecondaryContainer to c.secondaryContainer,c.onTertiaryContainer to c.tertiaryContainer,c.onSurface to c.surface,c.onSurfaceVariant to c.surface,c.onSurface to c.surfaceContainerLowest,c.onSurfaceVariant to c.surfaceContainerLow).forEach {(ink,paper)->
                val a=ink.luminance();val b=paper.luminance();val ratio=(maxOf(a,b)+.05f)/(minOf(a,b)+.05f)
                assertTrue("dark=$dark preset=$preset ratio=$ratio",ratio>=4.5f)
            }
        }
    }
    @Test fun touchedScreenTextRolesHaveNormalTextContrastOnTheirActualSurfaces() {
        for(dark in listOf(false,true)) for(preset in 0..2) {
            val c=mealColors(dark,preset)
            // Menu/detail text, settings group labels, and the low-container selection preview.
            listOf(
                "menu and detail" to (c.onSurface to c.surface),
                "supporting text" to (c.onSurfaceVariant to c.surface),
                "action and group labels" to (c.primary to c.surface),
                "preview text" to (c.onSurface to c.surfaceContainerLow),
                "preview supporting text" to (c.onSurfaceVariant to c.surfaceContainerLow),
                "preview label" to (c.primary to c.surfaceContainerLow),
                "selected date" to (c.onPrimaryContainer to c.primaryContainer)
            ).forEach {(role,pair)->
                val ratio=contrastRatio(pair.first,pair.second)
                assertTrue("$role dark=$dark preset=$preset ratio=$ratio",ratio>=4.5f)
            }
        }
    }
    @Test fun interactivePrimaryAndOutlineTokensMeetNonTextContrastAgainstAdjacentSurface() {
        for(dark in listOf(false,true)) for(preset in 0..2) {
            val c=mealColors(dark,preset)
            // Primary tab indicator/action icons and interactive outlined-control borders.
            // outlineVariant is a decorative divider, not an interactive boundary.
            listOf("primary" to c.primary,"outline" to c.outline).forEach {(role,ink)->
                val ratio=contrastRatio(ink,c.surface)
                assertTrue("$role dark=$dark preset=$preset ratio=$ratio",ratio>=3f)
            }
        }
    }
    private fun contrastRatio(ink:Color,paper:Color):Float {
        val a=ink.luminance();val b=paper.luminance()
        return (maxOf(a,b)+.05f)/(minOf(a,b)+.05f)
    }
    @Test fun customTextSizeScalesSupportingLabelsAsWellAsMenuText() {
        val standard=mealTypography(1f);val large=mealTypography(1.4f)
        assertEquals(standard.bodyLarge.fontSize.value*1.4f,large.bodyLarge.fontSize.value,.01f)
        assertEquals(standard.labelMedium.fontSize.value*1.4f,large.labelMedium.fontSize.value,.01f)
        assertTrue(large.bodyLarge.lineHeight>large.bodyLarge.fontSize)
    }
}
