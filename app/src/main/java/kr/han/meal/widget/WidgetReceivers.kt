package kr.han.meal.widget

import android.appwidget.*
import android.content.*
import android.os.Bundle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.han.meal.core.graph
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.*
import kr.han.meal.domain.scheduling.MealTiming
import kr.han.meal.worker.*
import java.time.*

open class MealWidgetProvider: AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) { local(context) }
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle) {local(context)}
    override fun onDeleted(context:Context,ids:IntArray) {
        val pending=goAsync();context.graph.scope.launch {try {
            val images=context.graph.settings.widgets.first().filter {it.id in ids}.mapNotNull {it.backgroundImageId}
            ids.forEach {context.graph.settings.removeWidget(it)}
            context.graph.widgetBackgrounds.discard(images)
            context.graph.renderer.renderAll()
        } finally {pending.finish()} }
    }
    private fun local(context:Context) {val pending=goAsync();context.graph.scope.launch {try {context.graph.renderer.renderAll()} finally {pending.finish()} } }
}
class NextMealWidget: MealWidgetProvider()
class DailyMealWidget: MealWidgetProvider()
class WeeklySelectedWidget: MealWidgetProvider()
class WeeklyAllWidget: MealWidgetProvider()
class WidgetEventReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf("kr.han.meal.TRANSITION",Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_DATE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending=goAsync()
        context.graph.scope.launch {try {WorkScheduler.install(context,context.graph.settings.settings.first().syncHours);context.graph.renderer.renderAll()} finally {pending.finish()} }
    }
}
class WidgetActionReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID || AppWidgetManager.getInstance(context).getAppWidgetInfo(id)==null) return
        val pending=goAsync()
        context.graph.scope.launch {
            try {
                val g=context.graph
                val c=g.settings.widgets.first().firstOrNull {it.id==id}
                when(intent.action?.substringAfterLast('.')) {
                    "refresh" -> WorkScheduler.request(context,true)
                    "auto" -> g.settings.updateWidget(id) {it.copy(manualDate=null,manualType=null,manualUntil=null,dateOffset=0,schedule=it.schedule.copy(mode=SelectionMode.AUTO,manualDate=null,manualUntil=null))}
                    "previous","next" -> if(c!=null) {
                        val p=g.settings.settings.first();val school=c.school ?: p.school;val cache=g.repository.snapshot()
                        val lookup:(LocalDate,MealType)->Availability={d,t->school?.let {cache.availability(it.id,d,t)} ?: Availability.Unknown}
                        val selection=MealSelector(g.clock).select(effectiveWidgetSchedule(c,g.clock.instant()),lookup)
                        g.settings.saveWidget(WidgetNavigation.move(c,selection,g.clock,if(intent.action?.endsWith("previous")==true) -1 else 1,lookup))
                    }
                }
                g.renderer.renderAll()
            } finally {pending.finish()}
        }
    }
}
/** Navigation is persisted independently for every widget. Unknown targets remain navigable, never assumed absent. */
object WidgetNavigation {
    fun move(c:WidgetConfig,selected:Selection,clock:Clock,direction:Int,lookup:(LocalDate,MealType)->Availability):WidgetConfig {
        val today=LocalDate.now(clock.withZone(SEOUL))
        val active=c.manualDate!=null && (!c.schedule.returnAutomatically || c.manualUntil?.let {it>clock.millis()}==true)
        var date=if(c.kind==WidgetKind.NEXT) (selected as? Selection.Selected)?.date ?: (selected as? Selection.NeedsData)?.date ?: today else if(active) LocalDate.parse(c.manualDate) else today.plusDays(c.dateOffset.toLong())
        var type=(selected as? Selection.Selected)?.type ?: (selected as? Selection.NeedsData)?.type ?: c.manualType ?: MealType.LUNCH
        if(c.schedule.excludedDays.size==7 || c.schedule.enabledTypes.isEmpty()) return c
        if(c.kind==WidgetKind.NEXT) {
            for(i in 1..63) {
                val ordinal=type.ordinal+direction
                if(ordinal<0) {date=date.minusDays(1);type=MealType.DINNER} else if(ordinal>2) {date=date.plusDays(1);type=MealType.BREAKFAST} else type=MealType.entries[ordinal]
                if(date.dayOfWeek.value in c.schedule.excludedDays || type !in c.schedule.enabledTypes) continue
                val a=lookup(date,type)
                if(a is Availability.Absent && c.schedule.missingPolicy==MissingPolicy.SKIP) continue
                return c.copy(manualDate=date.toString(),manualType=type,manualUntil=MealTiming.nextRefresh(c.schedule,clock).toEpochMilli(),dateOffset=0)
            }
            return c
        }
        if(c.kind==WidgetKind.DAILY) {do {date=date.plusDays(direction.toLong())} while(date.dayOfWeek.value in c.schedule.excludedDays)} else date=date.plusDays(7L*direction)
        return c.copy(manualDate=date.toString(),manualType=type,manualUntil=MealTiming.nextRefresh(c.schedule,clock).toEpochMilli(),dateOffset=0)
    }
}
