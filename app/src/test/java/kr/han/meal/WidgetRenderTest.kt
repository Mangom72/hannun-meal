package kr.han.meal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kr.han.meal.core.AppGraph
import kr.han.meal.data.local.CoverageEntity
import kr.han.meal.data.remote.NeisParser
import kr.han.meal.data.repository.CacheSnapshot
import kr.han.meal.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRenderTest {
    @Test fun allKindsRenderAtCompactAndLargeSizes()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val clock=Clock.fixed(Instant.parse("2025-09-01T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","가락고등학교")
            val meals=NeisParser.page(Json.parseToJsonElement(fixture("neis-real-20250901")).jsonObject,"mealServiceDietInfo").rows.map {NeisParser.meal(it,school).copy(syncedAt=clock.instant())}
            val coverage=(0L..6L).map {CoverageEntity(school.id,LocalDate.now(clock).plusDays(it).toString(),clock.millis(),clock.millis())}
            val cache=CacheSnapshot(meals,coverage)
            val p=AppSettings(school=school)
            val directory=File("build/reports/widget-previews").apply {mkdirs()}
            WidgetKind.entries.forEach {kind->
                listOf(144 to 144,320 to 180,320 to 250,320 to if(kind==WidgetKind.NEXT) 230 else 370).forEach {(width,height)->
                    val rv=g.renderer.render(WidgetConfig(101,kind,school),school,p,cache,width,height)
                    val view=rv.apply(context,FrameLayout(context))
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                    view.layout(0,0,width,height)
                    assertTrue(view.findViewById<TextView>(R.id.widget_title).text.isNotBlank())
                    val settings=view.findViewById<View>(R.id.widget_settings)
                    val bounds=Rect(0,0,settings.width,settings.height)
                    (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(settings,bounds)
                    assertTrue("$kind settings below bounds: $bounds height $height",bounds.bottom<=height)
                    if(kind==WidgetKind.DAILY && height==180) {
                        val body=view.findViewById<android.widget.LinearLayout>(R.id.widget_body)
                        assertEquals(3,body.childCount)
                        assertTrue("Daily sections must fit",body.getChildAt(2).bottom<=body.height)
                    }
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    File(directory,"${kind.name.lowercase()}-${width}x$height.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
            }
        } finally {g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()}
    }
    @Test fun compactNextWidgetKeepsItsMenuAndTouchTargetInsideBoundsAtLargeText()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val clock=Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","아주 긴 학교 이름도 설정에서 확인할 수 있는 고등학교")
            val date=LocalDate.now(clock)
            val meal=Meal(school.id,date,MealType.LUNCH,listOf(Dish("소고기버섯불고기와 따뜻한 잡곡밥"),Dish("맑은 콩나물국"),Dish("배추김치")),"원문",syncedAt=clock.instant())
            val cache=CacheSnapshot(listOf(meal),listOf(CoverageEntity(school.id,date.toString(),clock.millis(),clock.millis())))
            for(scale in listOf(1f,1.4f)) for((width,height) in listOf(144 to 144,180 to 220,240 to 220,260 to 220,320 to 180)) {
                val rv=g.renderer.render(WidgetConfig(205,WidgetKind.NEXT,school,schedule=Schedule(mode=SelectionMode.FIXED),fontScale=scale),school,AppSettings(school=school),cache,width,height)
                val view=rv.apply(context,FrameLayout(context))
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                val body=view.findViewById<android.widget.LinearLayout>(R.id.widget_body)
                assertTrue(body.height>0)
                val row=body.getChildAt(0)
                assertTrue("menu below body at $width x $height scale $scale",row.bottom<=body.height)
                val settings=view.findViewById<View>(R.id.widget_settings)
                assertTrue("48dp setting target",settings.height>=48 && settings.width>=48)
                listOf(R.id.widget_previous,R.id.widget_next,R.id.widget_auto,R.id.widget_refresh).map {view.findViewById<View>(it)}.filter {it.visibility==View.VISIBLE}.forEach {assertTrue("48dp action target at width $width",it.height>=48 && it.width>=48)}
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                val directory=File("build/reports/widget-previews").apply {mkdirs()}
                File(directory,"next-large-font-$scale-${width}x$height.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        } finally {g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()}
    }

    @Test fun everyWidgetAtLargeTextKeepsRowsAboveTheActionBar()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val clock=Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","학교")
            val first=LocalDate.now(clock).minusDays(2)
            val coverage=(0L..6L).map {CoverageEntity(school.id,first.plusDays(it).toString(),clock.millis(),clock.millis())}
            val meals=(0L..6L).flatMap {day->MealType.entries.map {type->Meal(school.id,first.plusDays(day),type,listOf(Dish("소고기버섯불고기"),Dish("따뜻한 잡곡밥"),Dish("콩나물맑은국"),Dish("깍두기")),"화면 검사용 예시",syncedAt=clock.instant())}}
            val cache=CacheSnapshot(meals,coverage)
            for(kind in WidgetKind.entries) for((width,height) in listOf(110 to 110,144 to 144,320 to 180,320 to 250,320 to 370)) {
                val rv=g.renderer.render(WidgetConfig(206,kind,school,fontScale=1.4f),school,AppSettings(school=school),cache,width,height)
                val view=rv.apply(context,FrameLayout(context))
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                val body=view.findViewById<android.widget.LinearLayout>(R.id.widget_body)
                for(i in 0 until body.childCount) assertTrue("$kind row $i clipped at $height: ${body.getChildAt(i).bottom}>${body.height}",body.getChildAt(i).bottom<=body.height)
            }
        } finally {g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()}
    }

    @Test fun aCachedWidgetAdoptsNightTextColorsWhenTheLauncherChangesTheme()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val original=org.robolectric.RuntimeEnvironment.getQualifiers()
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
        val clock=Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","학교")
            val date=LocalDate.now(clock)
            val meal=Meal(school.id,date,MealType.LUNCH,listOf(Dish("메뉴")),"원문",syncedAt=clock.instant())
            val rv=g.renderer.render(WidgetConfig(207,WidgetKind.NEXT,school,schedule=Schedule(mode=SelectionMode.FIXED)),school,AppSettings(school=school),CacheSnapshot(listOf(meal)),180,220)
            org.robolectric.RuntimeEnvironment.setQualifiers("+night")
            val view=rv.apply(context,FrameLayout(context))
            val menu=view.findViewById<TextView>(R.id.row_menu)
            assertEquals(context.getColor(R.color.widget_title),menu.currentTextColor)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(menu.currentTextColor,android.graphics.Color.parseColor("#1B2D23"))>=4.5)
        } finally {org.robolectric.RuntimeEnvironment.setQualifiers(original);g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()}
    }

}
