package kr.han.meal.widget

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.han.meal.MainActivity
import kr.han.meal.R
import kr.han.meal.core.AppGraph
import kr.han.meal.core.isOnline
import kr.han.meal.data.repository.CacheSnapshot
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.*
import kr.han.meal.domain.scheduling.MealTiming
import kr.han.meal.ui.shortDate
import kr.han.meal.ui.syncLabel
import kr.han.meal.worker.WorkScheduler
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val widgetProviders = mapOf(WidgetKind.NEXT to NextMealWidget::class.java,WidgetKind.DAILY to DailyMealWidget::class.java,WidgetKind.WEEKLY_SELECTED to WeeklySelectedWidget::class.java,WidgetKind.WEEKLY_ALL to WeeklyAllWidget::class.java)
class WidgetRenderer(private val g: AppGraph) {
    private val mutex=Mutex()
    suspend fun renderAll() = withContext(Dispatchers.IO) { mutex.withLock {
        val manager=AppWidgetManager.getInstance(g.app)
        val p=g.settings.settings.first()
        val configs=g.settings.widgets.first().associateBy {it.id}
        val snapshot=g.repository.snapshot()
        val refreshes=mutableListOf<Instant>()
        for((kind,provider) in widgetProviders) for(id in manager.getAppWidgetIds(ComponentName(g.app,provider))) {
            val config=configs[id] ?: WidgetConfig(id,kind,school=p.school,schedule=p.schedule,display=p.display)
            val school=config.school ?: p.school
            val options=manager.getAppWidgetOptions(id)
            val width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,250).coerceAtLeast(100)
            val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180).coerceAtLeast(100)
            fun views(w:Int,h:Int) = render(config,school,p,snapshot,w,h)
            val remote = if(Build.VERSION.SDK_INT>=31) {
                @Suppress("DEPRECATION")
                val sizes=options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if(!sizes.isNullOrEmpty()) RemoteViews(sizes.distinct().take(16).associateWith {views(it.width.toInt(),it.height.toInt())}) else views(width,height)
            } else views(width,height)
            manager.updateAppWidget(id,remote)
            refreshes += MealTiming.nextRefresh(effectiveWidgetSchedule(config,g.clock.instant()),g.clock)
            config.manualUntil?.let {if(it>g.clock.millis()) refreshes += Instant.ofEpochMilli(it)}
        }
        WorkScheduler.localRefresh(g.app,refreshes.isNotEmpty())
        scheduleRefresh(refreshes.minOrNull())
    } }
    internal fun render(c:WidgetConfig,school:School?,p:AppSettings,cache:CacheSnapshot,width:Int,height:Int,requestData:Boolean=true): RemoteViews {
        val rv=RemoteViews(g.app.packageName,R.layout.widget_meal)
        rv.setImageViewResource(R.id.widget_background,listOf(R.drawable.widget_background,R.drawable.widget_lavender,R.drawable.widget_apricot)[c.color.coerceIn(0,2)])
        rv.setInt(R.id.widget_background,"setImageAlpha",(c.opacity.coerceIn(25,100)*2.55).toInt())
        rv.removeAllViews(R.id.widget_body)
        val d=c.display
        val schedule=effectiveWidgetSchedule(c,g.clock.instant())
        val today=LocalDate.now(g.clock)
        val selection=school?.let {MealSelector(g.clock).select(schedule) {date,t->cache.availability(it.id,date,t)}}
        val selected=selection as? Selection.Selected
        val manualActive=c.manualDate!=null && (!c.schedule.returnAutomatically || c.manualUntil?.let {it>g.clock.millis()}==true)
        val scheduleManual=schedule.mode==SelectionMode.MANUAL && schedule.manualDate!=null && (!schedule.returnAutomatically || schedule.manualUntil==null || schedule.manualUntil>g.clock.millis())
        val anchor=if(manualActive) LocalDate.parse(c.manualDate) else if(scheduleManual) LocalDate.parse(schedule.manualDate) else today.plusDays(c.dateOffset.toLong())
        val openDate=if(c.kind!=WidgetKind.NEXT) anchor else when(selection) {
            is Selection.Selected -> selection.date
            is Selection.NeedsData -> selection.date
            is Selection.Failure -> selection.date
            else -> anchor
        }
        val activeManualType=schedule.mode==SelectionMode.MANUAL && (!schedule.returnAutomatically || schedule.manualUntil==null || schedule.manualUntil>g.clock.millis())
        val openType=when(selection) {
            is Selection.Selected -> selection.type
            is Selection.NeedsData -> selection.type
            else -> if(activeManualType) schedule.manualType else schedule.fixedType
        }
        val open=openIntent(c,openDate,openType,school?.id.orEmpty(),c.kind!=WidgetKind.NEXT)
        val rows=mutableListOf<FitRow>()
        val extras=mutableListOf<FitRow>()
        var countdownTarget:Instant?=null
        visible(rv,R.id.widget_countdown,false)
        var title="${c.kind.label} ${dateText(anchor,d)}".trim()
        fun row(label:String,text:String,pending:PendingIntent?=null,metadata:String?=null) {
            fun labeled(value:String)=listOf(label,value).filter {it.isNotBlank()}.joinToString("  ")
            rows += FitRow(labeled(text),pending ?: open,metadata?.let {labeled(it)})
        }
        val state=when {
            school==null -> "학교를 선택해 주세요"
            !g.configured(p) && cache.lastSync(school.id)==null -> "급식 서버에 연결할 수 없습니다"
            else -> null
        }
        if(state!=null) row("",state)
        else if(school!=null) when(c.kind) {
            WidgetKind.NEXT -> when(selection) {
                is Selection.Selected -> {
                    title=listOf(if(d.mealType) selection.type.label else "급식",dateText(selection.date,d)).filter {it.isNotBlank()}.joinToString(" ")
                    val details=detailIntent(c,selection.date,selection.type,school.id)
                    row("",selection.meal?.let {menu(it,d.copy(allergens=false))} ?: "등록된 급식이 없습니다",details,selection.meal?.let {mealText(it,d)})
                    if(d.eatingTime) extras += FitRow("식사 예정 ${eatingTime(schedule,selection.type)}",details)
                    if(d.countdown) {
                        val remaining=Duration.between(g.clock.instant(),MealTiming.eatingInstant(selection.date,selection.type,schedule))
                        if(!remaining.isNegative) {
                            if(c.liveCountdown && remaining.toHours()<24) countdownTarget=MealTiming.eatingInstant(selection.date,selection.type,schedule)
                            else extras += FitRow("약 ${remaining.toHours()}시간 ${remaining.toMinutes()%60}분 남음 (갱신 시점)",details)
                        } else if(!schedule.hidePassedCountdown) extras += FitRow("식사 예정 시간 지남",details)
                    }
                    if(d.nextPreview) nextMeal(cache,school.id,selection.date,selection.type,schedule)?.let {
                        extras += FitRow("다음 ${shortDate(it.date)} ${it.type.label}  ${menu(it,d)}",detailIntent(c,it.date,it.type,school.id))
                    }
                }
                is Selection.NeedsData -> {
                    title="${selection.type.label} ${dateText(selection.date,d)}".trim()
                    row("","급식을 내려받기 전입니다")
                    if(requestData && g.configured(p)) WorkScheduler.request(g.app,from=selection.date,to=selection.date.plusDays(20))
                }
                is Selection.Failure -> {
                    title="${openType.label} ${dateText(selection.date,d)}".trim()
                    row("요청 실패",selection.message)
                }
                is Selection.Warning -> row("설정 확인",selection.message)
                is Selection.Empty -> row("",selection.message)
                else -> Unit
            }
            WidgetKind.DAILY -> {
                val types=MealType.entries.filter {it in schedule.enabledTypes}
                if(types.isEmpty()) row("설정 확인","표시할 식사 종류를 선택해 주세요")
                types.forEach {type->row((if(selected?.date==anchor && selected.type==type) "● " else "")+type.label,summary(cache,school.id,anchor,type,c),detailIntent(c,anchor,type,school.id),summary(cache,school.id,anchor,type,c,true))}
                if(requestData) requestUnknown(cache,school,p,anchor,anchor)
            }
            WidgetKind.WEEKLY_SELECTED,WidgetKind.WEEKLY_ALL -> {
                val dates=if(c.rangeMode==RangeMode.ROLLING) eligibleDates(anchor,schedule,5) else {
                    val monday=anchor.minusDays((anchor.dayOfWeek.value-1).toLong())
                    (0L..6L).map {monday.plusDays(it)}.filter {it.dayOfWeek.value !in schedule.excludedDays}
                }
                val types=MealType.entries.filter {it in schedule.enabledTypes && (c.kind==WidgetKind.WEEKLY_ALL || it in c.weeklyTypes)}
                title=if(types.size==1) types.first().label+" 한 주" else c.kind.label
                if(dates.isNotEmpty()) title+=" ${dateText(dates.first(),d)}–${dateText(dates.last(),d)}"
                if(dates.isEmpty() || types.isEmpty()) row("설정 확인","요일과 식사 종류를 하나 이상 허용해 주세요")
                dates.forEach {date->types.forEach {type->
                    row("${dateText(date,d).ifBlank {shortDate(date)}} ${type.label}",summary(cache,school.id,date,type,c),detailIntent(c,date,type,school.id),summary(cache,school.id,date,type,c,true))
                }}
                if(requestData && dates.isNotEmpty()) requestUnknown(cache,school,p,dates.first(),dates.last())
            }
        }
        // Work in physical dp: saved font scale is a preferred upper size, not a reason
        // to drop dishes. The 12dp floor remains readable even with combined large fonts.
        val density=g.app.resources.displayMetrics.density
        fun px(dp:Float)=(dp*density).toInt()
        val padding=8
        rv.setViewPadding(R.id.widget_content,px(padding.toFloat()),px(padding.toFloat()),px(padding.toFloat()),px(padding.toFloat()))
        val contentWidth=(width-padding*2).coerceAtLeast(1)
        val bodyWidth=px(contentWidth.toFloat())
        val preferred=(if(c.kind==WidgetKind.NEXT) 15f else 13f)*c.fontScale.coerceIn(.8f,1.4f)*g.app.resources.configuration.fontScale
        var size=preferred.coerceAtLeast(12f)
        val available=px((height-padding*2-48).coerceAtLeast(1).toFloat())
        fun total(items:List<FitRow>,font:Float)=items.sumOf {textLayout(it.text,bodyWidth,font*density).height}
        while(size>12f && total(rows,size)>available) size=(size-.5f).coerceAtLeast(12f)
        val fullFits=total(rows,size)<=available
        // Optional school/sync/time information only receives space left by complete menus.
        var budget=available
        visible(rv,R.id.widget_school,false)
        visible(rv,R.id.widget_status,false)
        if(fullFits) {
            rows.indices.forEach {index->
                rows[index].metadata?.let {metadata->
                    val candidate=rows.toMutableList().apply {set(index,rows[index].copy(text=metadata))}
                    if(total(candidate,size)<=budget) rows[index]=candidate[index]
                }
            }
            if(!c.compact) {
                val spacious=rows.map {it.copy(text=it.text.replace(" · ","\n"))}
                if(total(spacious,size)<=budget) {rows.clear();rows.addAll(spacious)}
            }
            val schoolHeight=px(16f)
            if(d.school && school!=null && total(rows,size)+schoolHeight<=budget) {
                visible(rv,R.id.widget_school,true);rv.setTextViewText(R.id.widget_school,school.name)
                rv.setTextViewTextSize(R.id.widget_school,android.util.TypedValue.COMPLEX_UNIT_PX,12f*density)
                budget-=schoolHeight
            }
            for(extra in extras) if(total(rows+extra,size)<=budget) rows+=extra
            countdownTarget?.let {target->
                if(total(rows,size)+schoolHeight<=budget) {
                    visible(rv,R.id.widget_countdown,true)
                    rv.setTextViewTextSize(R.id.widget_countdown,android.util.TypedValue.COMPLEX_UNIT_PX,12f*density)
                    rv.setChronometer(R.id.widget_countdown,MealTiming.chronometerBase(target,g.clock.instant(),SystemClock.elapsedRealtime()),"식사까지 %s (−는 경과)",true)
                    rv.setChronometerCountDown(R.id.widget_countdown,true)
                    budget-=schoolHeight
                }
            }
            val status=if(school==null) "설정 > 위젯 관리에서 학교 선택" else listOfNotNull(
                if(manualActive || c.dateOffset!=0) "수동 선택 중" else null,
                if(d.freshness) (if(!g.app.isOnline()) "오프라인 (저장된 급식)" else cache.latestError(school.id)) ?: if(cache.lastSync(school.id)==null) "내려받기 대기" else if(cache.stale(school.id,g.clock.instant(),openDate)) "갱신 전 급식" else "저장된 급식" else null,
                if(d.lastSync) "동기화 ${syncLabel(cache.lastSync(school.id))}" else null
            ).joinToString(" · ")
            if(status.isNotBlank() && total(rows,size)+schoolHeight<=budget) {
                visible(rv,R.id.widget_status,true);rv.setTextViewText(R.id.widget_status,status)
                rv.setTextViewTextSize(R.id.widget_status,android.util.TypedValue.COMPLEX_UNIT_PX,12f*density)
                budget-=schoolHeight
            }
        }
        rv.setTextViewText(R.id.widget_title,title)
        rv.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_PX,(if(width<200) 12f else 14f)*density)
        visible(rv,R.id.widget_date,false);visible(rv,R.id.widget_eating,false)
        val lineHeight=textLayout("급식",bodyWidth,size*density).height
        val capacity=(budget/lineHeight).coerceAtLeast(1)
        val hidden=if(rows.size>capacity) rows.size-(capacity-1).coerceAtLeast(0) else 0
        val shown=if(hidden>0) rows.take((capacity-1).coerceAtLeast(0)) else rows.toList()
        var left=budget-(if(hidden>0) lineHeight else 0)
        shown.forEachIndexed {index,item->
            val fair=(left/(shown.size-index)).coerceAtLeast(lineHeight)
            val wanted=textLayout(item.text,bodyWidth,size*density).height
            val allocated=if(fullFits) wanted else minOf(wanted,fair/lineHeight*lineHeight)
            addFitRow(rv,item,bodyWidth,size*density,allocated)
            left-=allocated
        }
        if(hidden>0) addFitRow(rv,FitRow("${hidden}끼 더 보기",open),bodyWidth,12f*density,lineHeight)
        rv.setContentDescription(R.id.widget_root,listOfNotNull(school?.name,title).joinToString(" · "))
        rv.setOnClickPendingIntent(R.id.widget_root,open)
        rv.setOnClickPendingIntent(R.id.widget_title,open)
        rv.setOnClickPendingIntent(R.id.widget_refresh,actionIntent(c.id,"refresh"))
        return rv
    }
    private data class FitRow(val text:String,val pending:PendingIntent,val metadata:String?=null)
    private fun textLayout(text:String,width:Int,size:Float):android.text.StaticLayout {
        val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=size}
        val builder=android.text.StaticLayout.Builder.obtain(text,0,text.length,paint,width.coerceAtLeast(1))
            .setIncludePad(false).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
        if(Build.VERSION.SDK_INT>=28) builder.setUseLineSpacingFromFallbacks(true)
        return builder.build()
    }
    private fun addFitRow(rv:RemoteViews,item:FitRow,width:Int,size:Float,height:Int) {
        val row=RemoteViews(g.app.packageName,R.layout.widget_row)
        val layout=textLayout(item.text,width,size)
        val lines=(0 until layout.lineCount).count {layout.getLineBottom(it)<=height}.coerceAtLeast(1)
        var text=item.text
        if(layout.height>height) {
            val suffix="… 전체 보기"
            var end=layout.getLineEnd((lines-1).coerceAtMost(layout.lineCount-1)).coerceAtMost(text.length)
            while(end>0 && textLayout(text.take(end).trimEnd()+suffix,width,size).height>height) end--
            text=text.take(end).trimEnd()+suffix
        }
        visible(row,R.id.row_label,false)
        row.setTextViewText(R.id.row_menu,text)
        row.setTextViewTextSize(R.id.row_menu,android.util.TypedValue.COMPLEX_UNIT_PX,size)
        row.setInt(R.id.row_menu,"setMaxLines",lines)
        row.setContentDescription(R.id.row_root,item.text)
        row.setOnClickPendingIntent(R.id.row_root,item.pending)
        rv.addView(R.id.widget_body,row)
    }
    private fun dateText(date:LocalDate,d:DisplayOptions)=date.format(DateTimeFormatter.ofPattern(when {d.date && d.weekday->"M/d (E)";d.date->"M/d";d.weekday->"E요일";else->""},Locale.KOREAN))
    private fun visible(rv:RemoteViews,id:Int,show:Boolean) = rv.setViewVisibility(id,if(show) View.VISIBLE else View.GONE)
    private fun menu(meal:Meal,d:DisplayOptions)=meal.dishes.joinToString(" · ") {it.name+if(d.allergens && it.allergens.isNotEmpty()) " (${it.allergens.joinToString(".")})" else ""}.ifBlank {"메뉴 상세는 앱에서 확인"}
    private fun mealText(meal:Meal,d:DisplayOptions)=menu(meal,d)+(if(d.calories && meal.calories.isNotBlank()) " · ${meal.calories}" else "")
    private fun summary(cache:CacheSnapshot,school:String,date:LocalDate,type:MealType,c:WidgetConfig,metadata:Boolean=false):String = when(val a=cache.availability(school,date,type)) {
        is Availability.Present -> if(metadata) mealText(a.meal,c.display) else menu(a.meal,c.display.copy(allergens=false))
        is Availability.Absent -> "등록 없음"
        Availability.Unknown -> "내려받기 대기"
        is Availability.Failed -> "조회 실패. 새로고침해 주세요"
    }
    private fun requestUnknown(cache:CacheSnapshot,school:School,p:AppSettings,from:LocalDate,to:LocalDate) {
        if(!g.configured(p)) return
        if(generateSequence(from) {it.plusDays(1)}.takeWhile {it<=to}.any {cache.availability(school.id,it,MealType.LUNCH) is Availability.Unknown}) WorkScheduler.request(g.app,from=from,to=to)
    }
    private fun nextMeal(cache:CacheSnapshot,school:String,date:LocalDate,type:MealType,s:Schedule): Meal? = cache.meals.filter {it.schoolId==school && (it.date>date || it.date==date && it.type.ordinal>type.ordinal) && it.date.dayOfWeek.value !in s.excludedDays && it.type in s.enabledTypes}.minWithOrNull(compareBy<Meal> {it.date}.thenBy {it.type.ordinal})
    fun actionIntent(id:Int,action:String)=WidgetIntents.action(g.app,id,action)
    fun configIntent(id:Int)=WidgetIntents.configure(g.app,id)
    private fun detailIntent(c:WidgetConfig,date:LocalDate,type:MealType,school:String)=openIntent(c,date,type,school,false)
    private fun openIntent(c:WidgetConfig,date:LocalDate,type:MealType,school:String,weekly:Boolean)=WidgetIntents.open(g.app,c.id,date,type,school,weekly)
    private fun scheduleRefresh(at:Instant?) {
        val alarm=g.app.getSystemService(AlarmManager::class.java)
        val pi=PendingIntent.getBroadcast(g.app,0,Intent(g.app,WidgetEventReceiver::class.java).setAction("kr.han.meal.TRANSITION"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if(at==null) alarm.cancel(pi) else alarm.setWindow(AlarmManager.RTC,at.toEpochMilli(),600_000L,pi)
    }
}
