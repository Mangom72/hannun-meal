package kr.han.meal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kr.han.meal.core.AppGraph
import kr.han.meal.data.local.CoverageEntity
import kr.han.meal.data.repository.CacheSnapshot
import kr.han.meal.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyWidgetReadabilityTest {
    @Test fun dailySectionsHaveMeasuredLabelsPairedMenusAndHonestOverflow()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val original=android.content.res.Configuration(context.resources.configuration)
        val clock=Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","학교")
            val date=LocalDate.now(clock)
            val dishes=listOf(
                listOf("조식용 한우채소영양죽", "아몬드크랜베리모닝빵", "바나나", "우유"),
                listOf("중식용 소고기버섯불고기와잡곡밥", "들깨시래기된장국", "무말랭이무침", "배추김치"),
                listOf("석식용 수제등심돈가스와토마토소스", "브로콜리크림스프", "양배추사과샐러드", "깍두기")
            )
            val meals=MealType.entries.mapIndexed {i,type->Meal(school.id,date,type,dishes[i].map {Dish(it)},"원문",syncedAt=clock.instant())}
            val cache=CacheSnapshot(meals,listOf(CoverageEntity(school.id,date.toString(),clock.millis(),clock.millis())))
            val directory=File("build/reports/widget-previews/daily-readability").apply {mkdirs()}
            for(large in listOf(false,true)) {
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(android.content.res.Configuration(original).apply {fontScale=if(large) 1.3f else 1f},context.resources.displayMetrics)
                val density=context.resources.displayMetrics.density
                for((width,height) in listOf(144 to 144,320 to 180,320 to 208,320 to 220,320 to 250,320 to 370)) {
                    val config=WidgetConfig(710,WidgetKind.DAILY,school,fontScale=if(large) 1.4f else 1f)
                    val remote=g.renderer.render(config,school,AppSettings(school=school),cache,width,height,requestData=false)
                    val host=remote.apply(context,FrameLayout(context)) as ViewGroup
                    val w=(width*density).toInt();val h=(height*density).toInt()
                    host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));host.layout(0,0,w,h)
                    val body=host.findViewById<LinearLayout>(R.id.widget_body)
                    var shown=0
                    var hidden=0
                    val identities=mutableSetOf<android.net.Uri?>()
                    for(i in 0 until body.childCount) {
                        val row=body.getChildAt(i)
                        val label=row.findViewById<TextView>(R.id.row_label)
                        val menu=row.findViewById<TextView>(R.id.row_menu)
                        assertEquals("Root announces the complete row once",View.IMPORTANT_FOR_ACCESSIBILITY_NO,label.importantForAccessibility)
                        assertEquals("Root announces the complete row once",View.IMPORTANT_FOR_ACCESSIBILITY_NO,menu.importantForAccessibility)
                        assertTrue("Semantic root retains the action",row.isClickable)
                        val rowBounds=Rect(0,0,row.width,row.height)
                        body.offsetDescendantRectToMyCoords(row,rowBounds)
                        assertTrue("$width x $height large=$large row fits body: $rowBounds",rowBounds.bottom<=body.height)
                        for(text in listOf(label,menu).filter {it.visibility==View.VISIBLE}) {
                            assertTrue("Minimum 12dp readable glyph size",text.textSize>=12*density)
                            val bounds=Rect(0,0,text.width,text.height)
                            (row as ViewGroup).offsetDescendantRectToMyCoords(text,bounds)
                            assertTrue("Text child stays inside row",bounds.bottom<=row.height)
                            val layout=text.layout
                            assertTrue("Actual text height fits at $width x $height large=$large row=$i: text=${text.text} height=${text.height} layout=${layout.height} rowHeight=${row.height} padding=${row.paddingBottom}",layout.getLineBottom(layout.lineCount-1)<=text.height-text.compoundPaddingTop-text.compoundPaddingBottom)
                            assertEquals("Every character is laid out",text.text.length,layout.getLineEnd(layout.lineCount-1))
                            for(line in 0 until layout.lineCount) assertEquals("No silent ellipsis",0,layout.getEllipsisCount(line))
                        }
                        if(label.visibility==View.VISIBLE) {
                            if(height>=250) assertTrue("Expanded daily meal tap target is at least 48dp",row.height>=kotlin.math.ceil(48f*density).toInt())
                            val type=MealType.entries[shown]
                            assertTrue("Source order and visible type label",label.text.contains(type.label))
                            assertTrue("Labels are bold",label.typeface.isBold)
                            assertTrue("Content below its label",menu.top>=label.bottom)
                            val semantic=row.contentDescription.toString()
                            assertTrue(semantic.contains(type.label))
                            dishes[shown].forEach {assertTrue("Full semantic row retains $it",semantic.contains(it))}
                            assertTrue("Content is complete or explicitly points to full view",dishes[shown].all {menu.text.contains(it)} || menu.text.contains("전체 보기"))
                            assertTrue(row.performClick())
                            val intent=shadowOf(context).nextStartedActivity
                            assertEquals(date.toString(),intent.getStringExtra("date"))
                            assertEquals(type.name,intent.getStringExtra("mealType"))
                            assertEquals(school.id,intent.getStringExtra("schoolId"))
                            assertEquals(config.id.toString(),intent.data?.pathSegments?.first())
                            assertFalse(intent.getBooleanExtra("weekly",true))
                            assertTrue("Each meal has a distinct tap identity",identities.add(intent.data))
                            shown++
                        } else {
                            assertTrue("Omitted sections must be disclosed",menu.text.contains("끼 더 보기"))
                            hidden=menu.text.toString().substringBefore("끼").toInt()
                        }
                    }
                    assertEquals("No meal silently disappears",3,shown+hidden)
                    assertReservedRowsFit(body)
                    if(height==370) {
                        assertEquals(3,shown)
                        for(i in 0..2) dishes[i].forEach {assertTrue(body.getChildAt(i).findViewById<TextView>(R.id.row_menu).text.contains(it))}
                    }
                    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);host.draw(Canvas(bitmap))
                    File(directory,"daily-${width}x$height-${if(large) "app1.4-system1.3" else "default"}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                    // Reapply must replace children and keep new per-row dates and widget IDs.
                    val tomorrow=date.plusDays(1)
                    val next=meals.map {it.copy(date=tomorrow)}
                    g.renderer.render(config.copy(id=711,dateOffset=1),school,AppSettings(school=school),CacheSnapshot(next),width,height,requestData=false).reapply(context,host)
                    if(shown>0) {
                        assertTrue(body.getChildAt(0).performClick())
                        val intent=shadowOf(context).nextStartedActivity
                        assertEquals(tomorrow.toString(),intent.getStringExtra("date"))
                        assertEquals("711",intent.data?.pathSegments?.first())
                        assertEquals(MealType.BREAKFAST.name,intent.getStringExtra("mealType"))
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(original,context.resources.displayMetrics)
            g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()
        }
    }

    @Test fun dailyLabelsKeepCachedNightColorsAndEachDataState()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val original=org.robolectric.RuntimeEnvironment.getQualifiers()
        val clock=Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","학교")
            val date=LocalDate.now(clock)
            val present=Meal(school.id,date,MealType.BREAKFAST,listOf(Dish("단호박죽")),"원문",syncedAt=clock.instant())
            val covered=CoverageEntity(school.id,date.toString(),clock.millis(),clock.millis())
            for((cache,expected) in listOf(
                CacheSnapshot(listOf(present),listOf(covered)) to listOf("단호박죽","등록 없음","등록 없음"),
                CacheSnapshot() to List(3) {"내려받기 대기"},
                CacheSnapshot(coverage=listOf(covered.copy(successAt=null,error="테스트 실패"))) to List(3) {"조회 실패. 새로고침해 주세요"}
            )) for(widgetHeight in listOf(208,220,370)) {
                org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
                val remote=g.renderer.render(WidgetConfig(712,WidgetKind.DAILY,school),school,AppSettings(school=school),cache,320,widgetHeight,requestData=false)
                org.robolectric.RuntimeEnvironment.setQualifiers("+night")
                val host=remote.apply(context,FrameLayout(context))
                val density=context.resources.displayMetrics.density
                val width=(320*density).toInt();val height=(widgetHeight*density).toInt()
                host.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));host.layout(0,0,width,height)
                val body=host.findViewById<LinearLayout>(R.id.widget_body)
                assertEquals(3,body.childCount)
                assertReservedRowsFit(body)
                for(i in 0..2) {
                    if(widgetHeight==370) assertTrue("Even short state/menu rows have 48dp expanded tap areas: row=$i height=${body.getChildAt(i).height} padding=${body.getChildAt(i).paddingBottom} density=$density",body.getChildAt(i).height>=kotlin.math.ceil(48f*density).toInt())
                    val label=body.getChildAt(i).findViewById<TextView>(R.id.row_label)
                    val menu=body.getChildAt(i).findViewById<TextView>(R.id.row_menu)
                    assertEquals(View.VISIBLE,label.visibility)
                    assertTrue(label.text.contains(MealType.entries[i].label))
                    assertEquals(expected[i],menu.text.toString())
                    assertEquals(context.getColor(R.color.widget_accent),label.currentTextColor)
                    assertEquals(context.getColor(R.color.widget_title),menu.currentTextColor)
                }
            }
        } finally {
            org.robolectric.RuntimeEnvironment.setQualifiers(original)
            g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()
        }
    }

    private fun assertReservedRowsFit(body:LinearLayout) {
        var reserved=0
        for(i in 0 until body.childCount) {
            val row=body.getChildAt(i) as ViewGroup
            var rowBudget=row.paddingTop+row.paddingBottom
            for(id in listOf(R.id.row_label,R.id.row_menu)) {
                val text=row.findViewById<TextView>(id)
                if(text.visibility!=View.VISIBLE) continue
                val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize=text.textSize
                    if(id==R.id.row_label) typeface=android.graphics.Typeface.DEFAULT_BOLD
                }
                val content=text.text
                val builder=android.text.StaticLayout.Builder.obtain(content,0,content.length,paint,text.width)
                    .setIncludePad(false).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                if(android.os.Build.VERSION.SDK_INT>=28) builder.setUseLineSpacingFromFallbacks(true)
                rowBudget+=builder.build().height+text.compoundPaddingTop+text.compoundPaddingBottom
            }
            assertTrue("Actual row fits its static text plus exact emitted padding budget",row.height<=rowBudget)
            assertTrue("Actual row stays inside body",row.bottom<=body.height)
            reserved+=rowBudget
        }
        assertTrue("All static row heights plus SAME emitted touch padding fit body: $reserved <= ${body.height}",reserved<=body.height)
    }
}
