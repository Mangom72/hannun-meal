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
    internal fun render(c:WidgetConfig,school:School?,p:AppSettings,cache:CacheSnapshot,width:Int,height:Int): RemoteViews {
        val rv=RemoteViews(g.app.packageName,R.layout.widget_meal)
        rv.setImageViewResource(R.id.widget_background,listOf(R.drawable.widget_background,R.drawable.widget_lavender,R.drawable.widget_apricot)[c.color.coerceIn(0,2)])
        rv.setInt(R.id.widget_background,"setImageAlpha",(c.opacity.coerceIn(25,100)*2.55).toInt())
        rv.removeAllViews(R.id.widget_body)
        val fontScale=c.fontScale.coerceIn(.8f,1.4f)*g.app.resources.configuration.fontScale
        val compactNext=c.kind==WidgetKind.NEXT && (width<200 || height<220 || fontScale>1.3f && height<280)
        val d=c.display
        val schedule=effectiveWidgetSchedule(c,g.clock.instant())
        val today=LocalDate.now(g.clock)
        val selection=school?.let {MealSelector(g.clock).select(schedule) {date,t->cache.availability(it.id,date,t)}}
        val selected=selection as? Selection.Selected
        val manualActive=c.manualDate!=null && (!c.schedule.returnAutomatically || c.manualUntil?.let {it>g.clock.millis()}==true)
        val scheduleManual=schedule.mode==SelectionMode.MANUAL && schedule.manualDate!=null && (!schedule.returnAutomatically || schedule.manualUntil==null || schedule.manualUntil>g.clock.millis())
        val anchor=if(manualActive) LocalDate.parse(c.manualDate) else if(scheduleManual) LocalDate.parse(schedule.manualDate) else today.plusDays(c.dateOffset.toLong())
        rv.setTextViewText(R.id.widget_school,school?.name ?: "한눈급식")
        visible(rv,R.id.widget_school,d.school)
        rv.setTextViewText(R.id.widget_title,c.kind.label)
        rv.setTextViewText(R.id.widget_date,dateText(anchor,d))
        visible(rv,R.id.widget_date,d.date || d.weekday)
        visible(rv,R.id.widget_countdown,false);visible(rv,R.id.widget_eating,false)
        val infoLines=if(height<130) 1 else 4
        val state=when {
            school==null -> "학교를 선택해 주세요"
            !g.configured(p) && cache.lastSync(school.id)==null -> "급식 서버 연결을 설정해 주세요"
            else -> null
        }
        if(state!=null) addRow(rv,c,"",if(height<130) if(school==null) "학교 선택" else "서버 연결" else state,if(height<130) 1 else 8,null)
        else if(school!=null) when(c.kind) {
            WidgetKind.NEXT -> {
                when(selection) {
                    is Selection.Selected -> {
                        val date=selection.date;val type=selection.type
                        rv.setTextViewText(R.id.widget_title,listOfNotNull(if(d.date) if(date==today) "오늘" else if(date==today.plusDays(1)) "내일" else null else null,if(d.mealType) type.label else "급식").joinToString(" "))
                        rv.setTextViewText(R.id.widget_date,dateText(date,d))
                        val details=detailIntent(c,date,type,school.id)
                        val chrome=if(compactNext) 76+(20*fontScale).toInt()+(if(height>=170 && !(fontScale>1.5f && height<220)) (16*fontScale).toInt() else 0) else (160*fontScale).toInt()
                        val supplements=(if(d.calories && !selection.meal?.calories.isNullOrBlank() && height>190) 22*fontScale else 0f)+(if(d.nextPreview && height>250) 38*fontScale else 0f)
                        val lines=((height-chrome-supplements)/(18*fontScale)).toInt().coerceIn(1,16)
                        addRow(rv,c,"",selection.meal?.let {menu(it,d,c.compact || compactNext)} ?: "등록된 급식이 없습니다",lines,details)
                        if(d.calories && !selection.meal?.calories.isNullOrBlank() && height>190) addRow(rv,c,"",selection.meal!!.calories,1,details)
                        if(d.eatingTime) {visible(rv,R.id.widget_eating,true);rv.setTextViewText(R.id.widget_eating,"식사 예정 ${eatingTime(schedule,type)}")}
                        if(d.countdown && height>160) {
                            val target=MealTiming.eatingInstant(date,type,schedule)
                            if(target>g.clock.instant()) {
                                val remaining=Duration.between(g.clock.instant(),target)
                                if(c.liveCountdown && remaining.toHours()<24) {
                                    visible(rv,R.id.widget_countdown,true)
                                    rv.setChronometer(R.id.widget_countdown,MealTiming.chronometerBase(target,g.clock.instant(),SystemClock.elapsedRealtime()),"식사 기준 %s · −는 경과",true)
                                    rv.setChronometerCountDown(R.id.widget_countdown,true)
                                } else {
                                    visible(rv,R.id.widget_countdown,false);visible(rv,R.id.widget_eating,true)
                                    val relative=if(remaining.toDays()>0) "${remaining.toDays()}일 ${remaining.toHours()%24}시간" else "${remaining.toHours()}시간 ${remaining.toMinutes()%60}분"
                                    rv.setTextViewText(R.id.widget_eating,(if(d.eatingTime) "${eatingTime(schedule,type)} · " else "")+"약 $relative 남음 · 갱신 기준")
                                }
                            } else if(!schedule.hidePassedCountdown) {visible(rv,R.id.widget_eating,true);rv.setTextViewText(R.id.widget_eating,"식사 예정 시간 지남")}
                        }
                        if(d.nextPreview && height>250) {
                            val next=nextMeal(cache,school.id,date,type,schedule)
                            next?.let {addRow(rv,c,"다음 저장된 ${shortDate(it.date)} ${it.type.label}",it.dishes.take(2).joinToString(" · ") {dish->dish.name},1,detailIntent(c,it.date,it.type,school.id))}
                        }
                        rv.setOnClickPendingIntent(R.id.widget_title,details)
                    }
                    is Selection.NeedsData -> {addRow(rv,c,"${shortDate(selection.date)} ${selection.type.label}","아직 내려받지 않은 날짜 · 동기화 대기",infoLines,null,inline=height<130);if(g.configured(p)) WorkScheduler.request(g.app,from=selection.date,to=selection.date.plusDays(20))}
                    is Selection.Failure -> addRow(rv,c,"요청 실패",selection.message,infoLines,null,inline=height<130)
                    is Selection.Warning -> addRow(rv,c,"설정 확인",selection.message,infoLines,null,inline=height<130)
                    is Selection.Empty -> addRow(rv,c,"",selection.message,infoLines,null,inline=height<130)
                    else -> Unit
                }
            }
            WidgetKind.DAILY -> {
                val types=MealType.entries.filter {it in schedule.enabledTypes}
                if(types.isEmpty()) addRow(rv,c,"설정 확인","표시할 식사 종류를 선택해 주세요",3,null)
                val visibleTypes=if(height<180 && types.size>1) listOf(types.firstOrNull {selected?.date==anchor && it==selected.type} ?: types.first()) else types
                visibleTypes.forEach {type ->
                    val mark=if(selected?.date==anchor && selected.type==type) "● " else ""
                    addRow(rv,c,mark+if(d.mealType) type.label else "",summary(cache,school.id,anchor,type,c),((height-100)/types.size/(18*fontScale)-1).toInt().coerceIn(1,6),detailIntent(c,anchor,type,school.id),inline=height<240)
                }
                if(visibleTypes.size<types.size && height>=130) addRow(rv,c,"","하루 전체 보기",1,openIntent(c,anchor,visibleTypes.first(),school.id,true))
                requestUnknown(cache,school,p,anchor,anchor)
            }
            WidgetKind.WEEKLY_SELECTED,WidgetKind.WEEKLY_ALL -> {
                val dates=if(c.rangeMode==RangeMode.ROLLING) eligibleDates(anchor,schedule,5) else {
                    val monday=anchor.minusDays((anchor.dayOfWeek.value-1).toLong())
                    (0L..6L).map {monday.plusDays(it)}.filter {it.dayOfWeek.value !in schedule.excludedDays}
                }
                val types=if(c.kind==WidgetKind.WEEKLY_ALL) MealType.entries.filter {it in schedule.enabledTypes} else MealType.entries.filter {it in c.weeklyTypes && it in schedule.enabledTypes}
                rv.setTextViewText(R.id.widget_date,if(dates.isNotEmpty() && d.date) "${shortDate(dates.first())} – ${shortDate(dates.last())}" else if(d.weekday) "월요일부터 · 제외 요일 건너뜀" else "")
                if(dates.isEmpty() || types.isEmpty()) addRow(rv,c,"설정 확인","요일과 식사 종류를 하나 이상 허용해 주세요",3,null)
                // Reserve actual chrome and an overflow link before assigning menu lines.
                val systemScale=g.app.resources.configuration.fontScale
                val overhead=20+48+4+(22+16+(if(height>=330) 30 else 0))*systemScale
                val available=(height-overhead-8).coerceAtLeast(20f)
                val lineHeight=18*fontScale
                val labelHeight=16*systemScale+3
                val capacity=(available/(labelHeight+lineHeight)).toInt().coerceIn(1,7)
                val visibleDates=if(dates.size>capacity) dates.take((capacity-1).coerceAtLeast(1)) else dates
                val separateOverflow=visibleDates.size<dates.size && capacity>1
                val overflowSpace=if(separateOverflow) lineHeight+3 else 0f
                val rowLines=if(visibleDates.isEmpty()) 1 else (((available-overflowSpace)/visibleDates.size-labelHeight)/lineHeight).toInt().coerceIn(1,if(c.kind==WidgetKind.WEEKLY_ALL) 3 else 5)
                visibleDates.forEach {date->
                    val mealStates=types.joinToString(" · ") {t->t.label.take(1)+when(cache.availability(school.id,date,t)) {
                        is Availability.Present -> " 있음";is Availability.Absent -> " 없음";Availability.Unknown -> " 미조회";is Availability.Failed -> " 오류"
                    }}
                    val registered=types.filter {cache.availability(school.id,date,it) is Availability.Present}
                    val text=if(c.kind==WidgetKind.WEEKLY_ALL) {
                        if(width<180 || rowLines==1) mealStates else if(registered.isNotEmpty()) registered.joinToString(if(rowLines>1) "\n" else " · ") {t->(if(d.mealType) "${t.label} " else "")+summary(cache,school.id,date,t,c,true)}
                        else if(types.all {cache.availability(school.id,date,it) is Availability.Absent}) "등록된 급식 없음" else "미조회 또는 조회 실패 · 앱에서 확인"
                    } else types.joinToString(if(rowLines>1) "\n" else " · ") {t-> (if(d.mealType) "${t.label} " else "")+summary(cache,school.id,date,t,c,true)}
                    val moreHint=if(visibleDates.size<dates.size && !separateOverflow) " · ${dates.size-visibleDates.size}일 더" else ""
                    val label=(if(date==today) "● " else "")+dateText(date,d).ifBlank {"급식"}+moreHint+(if(c.kind==WidgetKind.WEEKLY_ALL && rowLines>1 && width>=240 && d.mealType) " · $mealStates" else "")
                    addRow(rv,c,label,text,rowLines,openIntent(c,date,types.firstOrNull() ?: MealType.LUNCH,school.id,true),inline=height<130)
                }
                if(separateOverflow) addRow(rv,c,"", "${dates.size-visibleDates.size}일 더 · 전체 보기",1,openIntent(c,anchor,types.firstOrNull() ?: MealType.LUNCH,school.id,true))
                if(dates.isNotEmpty()) requestUnknown(cache,school,p,dates.first(),dates.last())
            }
        }
        val status=if(school==null) "위젯 설정에서 학교를 선택하세요" else listOfNotNull(
            if(manualActive || c.dateOffset!=0) "수동 · 자동 버튼으로 복귀" else null,
            if(d.freshness) (if(!g.app.isOnline()) "오프라인 · 저장된 급식" else cache.latestError(school.id)) ?: if(cache.lastSync(school.id)==null) "내려받기 대기" else if(cache.stale(school.id,g.clock.instant(),selected?.date ?: anchor)) "오래된 캐시 표시" else "저장된 급식" else null,
            if(d.lastSync) "동기화 ${syncLabel(cache.lastSync(school.id))}" else null
        ).joinToString(" · ")
        rv.setTextViewText(R.id.widget_status,status);visible(rv,R.id.widget_status,status.isNotBlank())
        rv.setContentDescription(R.id.widget_root,listOfNotNull(school?.name,selected?.let {"${shortDate(it.date)} ${it.type.label}"}).joinToString(" · "))
        val openDate=selected?.date ?: anchor;val openType=selected?.type ?: MealType.LUNCH
        rv.setOnClickPendingIntent(R.id.widget_root,openIntent(c,openDate,openType,school?.id.orEmpty(),c.kind!=WidgetKind.NEXT))
        rv.setOnClickPendingIntent(R.id.widget_settings,configIntent(c.id))
        for((view,action) in listOf(R.id.widget_previous to "previous",R.id.widget_next to "next",R.id.widget_auto to "auto",R.id.widget_refresh to "refresh")) rv.setOnClickPendingIntent(view,actionIntent(c.id,action))
        if(width<260) {visible(rv,R.id.widget_previous,false);visible(rv,R.id.widget_next,false)}
        if(width<180) {visible(rv,R.id.widget_auto,false);visible(rv,R.id.widget_refresh,false)}
        if(height<200) {
            visible(rv,R.id.widget_school,false);visible(rv,R.id.widget_eating,false)
            visible(rv,R.id.widget_countdown,false);visible(rv,R.id.widget_status,false)
            rv.setInt(R.id.widget_title,"setMaxLines",1)
            rv.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_SP,15f)
        }
        if(compactNext) {
            visible(rv,R.id.widget_school,false);visible(rv,R.id.widget_status,false)
            visible(rv,R.id.widget_eating,false);visible(rv,R.id.widget_countdown,false)
            rv.setInt(R.id.widget_title,"setMaxLines",1)
            rv.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_SP,16f*c.fontScale.coerceIn(.8f,1.4f))
        }
        if(height<170 || fontScale>1.5f && height<220) visible(rv,R.id.widget_date,false)
        if(c.kind==WidgetKind.DAILY && height<240) {visible(rv,R.id.widget_school,false);visible(rv,R.id.widget_status,false)}
        if(c.kind==WidgetKind.DAILY && height in 180..209) {
            visible(rv,R.id.widget_date,false)
            rv.setTextViewText(R.id.widget_title,"하루 급식 · ${dateText(anchor,d)}")
            rv.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_SP,15f)
            rv.setInt(R.id.widget_title,"setMaxLines",1)
        }
        if(height<300 || c.kind!=WidgetKind.NEXT) {
            val padding=(10*g.app.resources.displayMetrics.density).toInt()
            rv.setViewPadding(R.id.widget_content,padding,padding,padding,padding)
        }
        if(c.kind in listOf(WidgetKind.WEEKLY_SELECTED,WidgetKind.WEEKLY_ALL) && height<330) {visible(rv,R.id.widget_school,false);visible(rv,R.id.widget_status,false)}
        if(height<130) {visible(rv,R.id.widget_title,false);visible(rv,R.id.widget_date,false);visible(rv,R.id.widget_school,false);visible(rv,R.id.widget_status,false);visible(rv,R.id.widget_eating,false);visible(rv,R.id.widget_countdown,false)}
        return rv
    }
    private fun dateText(date:LocalDate,d:DisplayOptions)=date.format(DateTimeFormatter.ofPattern(when {d.date && d.weekday->"M/d (E)";d.date->"M/d";d.weekday->"E요일";else->""},Locale.KOREAN))
    private fun visible(rv:RemoteViews,id:Int,show:Boolean) = rv.setViewVisibility(id,if(show) View.VISIBLE else View.GONE)
    private fun menu(meal:Meal,d:DisplayOptions,compact:Boolean)=meal.dishes.joinToString(if(compact) " · " else "\n") {it.name+if(d.allergens && it.allergens.isNotEmpty()) " (${it.allergens.joinToString(".")})" else ""}.ifBlank {"메뉴 상세는 앱에서 확인"}
    private fun summary(cache:CacheSnapshot,school:String,date:LocalDate,type:MealType,c:WidgetConfig,brief:Boolean=false):String = when(val a=cache.availability(school,date,type)) {
        is Availability.Present -> if(brief) a.meal.dishes.take(if(c.compact) 2 else 3).joinToString(" · ") {it.name+if(c.display.allergens && it.allergens.isNotEmpty()) " (${it.allergens.joinToString(".")})" else ""}+(if(a.meal.dishes.size>(if(c.compact) 2 else 3)) " 외" else "")+(if(c.display.calories && a.meal.calories.isNotBlank()) " · ${a.meal.calories}" else "") else menu(a.meal,c.display,c.compact)+(if(c.display.calories && a.meal.calories.isNotBlank()) "\n${a.meal.calories}" else "")
        is Availability.Absent -> "등록 없음"
        Availability.Unknown -> "미조회 · 동기화 대기"
        is Availability.Failed -> "조회 실패 · 새로고침"
    }
    private fun addRow(rv:RemoteViews,c:WidgetConfig,label:String,text:String,lines:Int,pending:PendingIntent?,inline:Boolean=false) {
        val row=RemoteViews(g.app.packageName,R.layout.widget_row)
        row.setTextViewText(R.id.row_label,label);visible(row,R.id.row_label,label.isNotBlank() && !inline)
        row.setTextViewText(R.id.row_menu,if(inline && label.isNotBlank()) "$label  $text" else text);row.setInt(R.id.row_menu,"setMaxLines",lines)
        row.setTextViewTextSize(R.id.row_menu,android.util.TypedValue.COMPLEX_UNIT_SP,((if(c.kind==WidgetKind.NEXT) 15f else 13f)*c.fontScale.coerceIn(.8f,1.4f)).coerceAtLeast(12f))
        pending?.let {row.setOnClickPendingIntent(R.id.row_root,it)}
        rv.addView(R.id.widget_body,row)
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
