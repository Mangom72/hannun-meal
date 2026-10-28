package kr.han.meal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kr.han.meal.domain.model.*
import kr.han.meal.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UiPresentationTest {
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
    @Test fun customTextSizeScalesSupportingLabelsAsWellAsMenuText() {
        val standard=mealTypography(1f);val large=mealTypography(1.4f)
        assertEquals(standard.bodyLarge.fontSize.value*1.4f,large.bodyLarge.fontSize.value,.01f)
        assertEquals(standard.labelMedium.fontSize.value*1.4f,large.labelMedium.fontSize.value,.01f)
        assertTrue(large.bodyLarge.lineHeight>large.bodyLarge.fontSize)
    }
}
