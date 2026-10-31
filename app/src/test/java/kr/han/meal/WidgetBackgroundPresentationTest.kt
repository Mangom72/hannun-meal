package kr.han.meal

import kr.han.meal.domain.model.*
import kr.han.meal.ui.*
import org.junit.Assert.*
import org.junit.Test

class WidgetBackgroundPresentationTest {
    @Test fun draftCleanupExcludesSelectedImageEvenOnSaveRetry() {
        val imports=listOf("first","selected","unused","first")
        assertEquals(listOf("first","unused"),unusedWidgetDraftImages(imports,"selected"))
        assertEquals(listOf("first","unused"),unusedWidgetDraftImages(imports,"selected"))
        assertEquals(listOf("first","selected","unused"),unusedWidgetDraftImages(imports,null))
        assertEquals(emptyList<String>(),unusedWidgetDraftImages(emptyList(),"previously-saved"))
    }
    @Test fun legacySwatchesKeepTheirOriginalPaletteAndClearOnlyCustomColor() {
        val initial=WidgetConfig(7,color=2,customBackgroundColor=0xFF112233.toInt(),backgroundImageId="a".repeat(32),opacity=38)
        widgetSwatches.filter {it.legacy!=null}.forEach {swatch->
            val result=initial.withSwatch(swatch)
            assertEquals(swatch.legacy!!,result.color)
            assertNull(result.customBackgroundColor)
            assertEquals(initial.backgroundImageId,result.backgroundImageId)
            assertEquals(38,result.opacity)
            assertTrue(result.isSwatchSelected(swatch))
        }
    }
    @Test fun customSwatchDoesNotDestroyLegacyChoiceAndOffRetainsEveryOption() {
        val custom=widgetSwatches.first {it.legacy==null}
        val selected=WidgetConfig(8,color=1,opacity=0,backgroundImageId="b".repeat(32)).withSwatch(custom)
        assertEquals(1,selected.color)
        assertEquals(custom.argb,selected.customBackgroundColor)
        assertTrue(selected.isSwatchSelected(custom))
        val off=selected.copy(backgroundEnabled=false)
        assertEquals(selected,off.copy(backgroundEnabled=true))
        val image=off.copy(backgroundMode=WidgetBackgroundMode.IMAGE)
        assertEquals(selected,image.copy(backgroundEnabled=true,backgroundMode=WidgetBackgroundMode.COLOR))
    }
    @Test fun hexInputIsOpaqueAndStrictRatherThanAcceptingAmbiguousValues() {
        assertEquals(0xFF12ABEF.toInt(),parseWidgetColor("#12abEF"))
        assertEquals(0xFF000000.toInt(),parseWidgetColor("000000"))
        assertEquals(0xFFFFFFFF.toInt(),parseWidgetColor("FFFFFF"))
        listOf("","123","12345678","GGGGGG","-12345","12345 ").forEach {assertNull(it,parseWidgetColor(it))}
    }
}
