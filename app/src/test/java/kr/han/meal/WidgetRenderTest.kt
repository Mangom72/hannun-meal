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
    @Test fun rootAndFallbackContentClicksKeepDisplayedDateAndRequestedMeal()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val clock=Clock.fixed(Instant.parse("2026-10-31T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","학교")
            val p=AppSettings(school=school)
            val saturday=LocalDate.now(clock)
            val monday=saturday.plusDays(2)
            val meal=Meal(school.id,monday,MealType.LUNCH,listOf(Dish("월요일 점심 메뉴")),"원문",syncedAt=clock.instant())
            val cache=CacheSnapshot(listOf(meal),listOf(CoverageEntity(school.id,monday.toString(),clock.millis(),clock.millis())))
            assertEquals(monday,(kr.han.meal.domain.selection.MealSelector(clock).select(Schedule()) {date,type->cache.availability(school.id,date,type)} as Selection.Selected).date)
            fun click(config:WidgetConfig,snapshot:CacheSnapshot,date:LocalDate,type:MealType,body:Boolean=false,title:Boolean=false,prior:View?=null):View {
                val remote=g.renderer.render(config,school,p,snapshot,320,330,requestData=false)
                val view=if(prior==null) remote.apply(context,FrameLayout(context)) else prior.also {remote.reapply(context,it)}
                if(config.kind==WidgetKind.NEXT) {
                    val header=view.findViewById<TextView>(R.id.widget_title).text
                    assertTrue("Requested meal appears beside its date",header.contains("${type.label} ${date.monthValue}/${date.dayOfMonth}"))
                }
                val target=if(body) view.findViewById<android.widget.LinearLayout>(R.id.widget_body).getChildAt(0) else view.findViewById<View>(if(title) R.id.widget_title else R.id.widget_root)
                assertTrue("RemoteViews click must be wired",target.performClick())
                val intent=org.robolectric.Shadows.shadowOf(context).nextStartedActivity
                assertNotNull("Click must send its PendingIntent",intent)
                assertEquals(date.toString(),intent.getStringExtra("date"))
                assertEquals(type.name,intent.getStringExtra("mealType"))
                assertEquals(school.id,intent.getStringExtra("schoolId"))
                assertEquals(config.kind!=WidgetKind.NEXT,intent.getBooleanExtra("weekly",false))
                return view
            }
            listOf(WidgetKind.DAILY,WidgetKind.WEEKLY_SELECTED,WidgetKind.WEEKLY_ALL).forEachIndexed {index,kind->
                click(WidgetConfig(500+index,kind,school),cache,saturday,MealType.LUNCH)
            }
            val missing=WidgetConfig(510,WidgetKind.NEXT,school,schedule=Schedule(mode=SelectionMode.FIXED,fixedType=MealType.DINNER,excludedDays=emptySet()))
            click(missing,CacheSnapshot(),saturday,MealType.DINNER)
            click(missing,CacheSnapshot(),saturday,MealType.DINNER,body=true)
            val manualDate=saturday.minusDays(2)
            val failure=CacheSnapshot(coverage=listOf(CoverageEntity(school.id,manualDate.toString(),null,clock.millis(),"요청 실패")))
            val manual=WidgetConfig(511,WidgetKind.NEXT,school,manualDate=manualDate.toString(),manualType=MealType.DINNER,manualUntil=clock.millis()+60_000)
            click(manual,failure,manualDate,MealType.DINNER)
            click(manual,failure,manualDate,MealType.DINNER,body=true)
            // A launcher normally reapplies to the same host view. Title listeners must
            // not retain the last successful selection when data becomes missing/failed.
            val selectedMeal=meal.copy(date=saturday)
            val selectedConfig=missing.copy(schedule=missing.schedule.copy(fixedType=MealType.LUNCH))
            val host=click(selectedConfig,CacheSnapshot(listOf(selectedMeal)),saturday,MealType.LUNCH,title=true)
            click(missing,CacheSnapshot(),saturday,MealType.DINNER,title=true,prior=host)
            click(manual.copy(id=missing.id),failure,manualDate,MealType.DINNER,title=true,prior=host)
            Unit
        } finally {g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()}
    }
    @Test fun previewsWithMissingDataRenderWithoutStartingWorkManager()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val g=AppGraph(context,Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"),SEOUL),startBackground=false)
        try {
            val school=School("B10","7010057","가락고등학교")
            val p=AppSettings(school=school)
            // This Application has no WorkManager initializer. A preview that queues a sync fails.
            WidgetKind.entries.forEach {kind->
                val config=kr.han.meal.ui.previewConfig(p,kind)
                val views=g.renderer.render(config,school,p,CacheSnapshot(),320,330,requestData=false)
                val view=views.apply(context,FrameLayout(context))
                val refresh=view.findViewById<View>(R.id.widget_refresh)
                assertTrue("$kind refresh has native button role",refresh is android.widget.Button)
                assertEquals("android.widget.Button",refresh.accessibilityClassName)
                assertEquals(context.getString(R.string.refresh_meals),refresh.contentDescription)
                fun buttonCount(node:View):Int=(if(node is android.widget.Button && node.visibility==View.VISIBLE) 1 else 0)+
                    (if(node is android.view.ViewGroup) (0 until node.childCount).sumOf {buttonCount(node.getChildAt(it))} else 0)
                assertEquals("$kind has exactly one visible button",1,buttonCount(view))
                assertTrue(refresh.performClick())
                val broadcast=org.robolectric.Shadows.shadowOf(context).broadcastIntents.last()
                assertEquals("kr.han.meal.refresh",broadcast.action)
                assertEquals(kr.han.meal.widget.WidgetActionReceiver::class.java.name,broadcast.component?.className)
                assertEquals(config.id,broadcast.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,0))
            }
        } finally {g.scope.cancel();g.database.close()}
    }
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
                    val settings=view.findViewById<View>(R.id.widget_refresh)
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
                val settings=view.findViewById<View>(R.id.widget_refresh)
                assertTrue("48dp setting target",settings.height>=48 && settings.width>=48)
                assertEquals(View.VISIBLE,settings.visibility)
                val refreshBounds=Rect(0,0,settings.width,settings.height)
                (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(settings,refreshBounds)
                assertEquals(width-8,refreshBounds.right)
                assertEquals(8,refreshBounds.top)
                assertTrue(view.findViewById<TextView>(R.id.widget_title).text.startsWith("중식 "))
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                val directory=File("build/reports/widget-previews").apply {mkdirs()}
                File(directory,"next-large-font-$scale-${width}x$height.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        } finally {g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()}
    }

    @Test fun everyWidgetAtLargeTextKeepsRowsAboveTheActionBar()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val originalScale=context.resources.configuration.fontScale
        val configuration=android.content.res.Configuration(context.resources.configuration).apply {fontScale=1.3f}
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration,context.resources.displayMetrics)
        val clock=Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"),SEOUL)
        val g=AppGraph(context,clock,startBackground=false)
        try {
            val school=School("B10","7010057","학교")
            val first=LocalDate.now(clock).minusDays(2)
            val coverage=(0L..6L).map {CoverageEntity(school.id,first.plusDays(it).toString(),clock.millis(),clock.millis())}
            val meals=(0L..6L).flatMap {day->MealType.entries.map {type->Meal(school.id,first.plusDays(day),type,listOf(Dish("소고기버섯불고기"),Dish("따뜻한 잡곡밥"),Dish("콩나물맑은국"),Dish("깍두기"),Dish("배추겉절이"),Dish("삼겹살고추장볶음"),Dish("생크림과일와플")),"화면 검사용 예시",syncedAt=clock.instant())}}
            val cache=CacheSnapshot(meals,coverage)
            for(scale in listOf(1f,1.4f)) for(kind in WidgetKind.entries) for((width,height) in listOf(110 to 110,144 to 144,320 to 180,320 to 250,340 to 330,320 to 370)) {
                val rv=g.renderer.render(WidgetConfig(206,kind,school,fontScale=scale),school,AppSettings(school=school),cache,width,height)
                val view=rv.apply(context,FrameLayout(context))
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                val body=view.findViewById<android.widget.LinearLayout>(R.id.widget_body)
                for(i in 0 until body.childCount) {
                    assertTrue("$kind row $i clipped at $height: ${body.getChildAt(i).bottom}>${body.height}",body.getChildAt(i).bottom<=body.height)
                    val menu=body.getChildAt(i).findViewById<TextView>(R.id.row_menu)
                    for(line in 0 until menu.layout.lineCount) assertEquals("$kind must use an honest full-view hint, not launcher ellipsis",0,menu.layout.getEllipsisCount(line))
                    assertTrue("$kind laid-out text must fit its measured view",menu.layout.getLineBottom(menu.layout.lineCount-1)<=menu.height-menu.compoundPaddingTop-menu.compoundPaddingBottom)
                }
                val refresh=view.findViewById<View>(R.id.widget_refresh)
                assertEquals(View.VISIBLE,refresh.visibility)
                assertTrue(refresh.width>=48 && refresh.height>=48)
                val refreshBounds=Rect(0,0,refresh.width,refresh.height)
                (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(refresh,refreshBounds)
                assertEquals("$kind refresh is top-right",width-8,refreshBounds.right)
                assertEquals("$kind refresh is top-right",8,refreshBounds.top)
                val title=view.findViewById<TextView>(R.id.widget_title).text.toString()
                if(kind==WidgetKind.NEXT) assertTrue("Meal type and selected date share header",title.contains("중식 10/28"))
                if(kind==WidgetKind.DAILY) assertTrue("Daily header retains its date",title.contains("10/28"))
                if(kind in listOf(WidgetKind.WEEKLY_SELECTED,WidgetKind.WEEKLY_ALL)) {
                    for(i in 0 until body.childCount) {
                        val row=body.getChildAt(i)
                        val text=row.contentDescription.toString()
                        if(!text.contains("끼 더 보기")) {
                            assertTrue("Weekly meal retains date",text.contains(Regex("10/\\d+")))
                            assertTrue("Weekly meal retains type",MealType.entries.any {text.contains(it.label)})
                        }
                    }
                }
                if(kind==WidgetKind.DAILY && width==340 && height==330) {
                    assertEquals("All three meals fit before metadata",3,body.childCount)
                    for(i in 0 until body.childCount) {
                        val menu=(body.getChildAt(i) as android.view.ViewGroup).findViewById<TextView>(R.id.row_menu)
                        meals.first().dishes.forEach {dish->assertTrue("Full dish name must survive: ${dish.name}",menu.text.contains(dish.name))}
                        assertFalse(menu.text.contains("전체 보기"))
                        for(line in 0 until menu.layout.lineCount) assertEquals("No silent TextView ellipsis",0,menu.layout.getEllipsisCount(line))
                    }
                }
                if(width==110 && height==110 && kind==WidgetKind.WEEKLY_ALL) {
                    val texts=(0 until body.childCount).joinToString {body.getChildAt(it).findViewById<TextView>(R.id.row_menu).text}
                    assertTrue("Tiny weekly widget must disclose omitted meals",texts.contains("끼 더"))
                }
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                val directory=File("build/reports/widget-previews").apply {mkdirs()}
                File(directory,"fit-${kind.name.lowercase()}-app$scale-system1.3-${width}x$height.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        } finally {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(android.content.res.Configuration(context.resources.configuration).apply {fontScale=originalScale},context.resources.displayMetrics)
            g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()
        }
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
            view.measure(View.MeasureSpec.makeMeasureSpec(180,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(220,View.MeasureSpec.EXACTLY));view.layout(0,0,180,220)
            val bitmap=Bitmap.createBitmap(180,220,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File("build/reports/widget-previews/next-night-cached.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        } finally {org.robolectric.RuntimeEnvironment.setQualifiers(original);g.scope.coroutineContext[Job]!!.cancelAndJoin();g.database.close()}
    }

}
