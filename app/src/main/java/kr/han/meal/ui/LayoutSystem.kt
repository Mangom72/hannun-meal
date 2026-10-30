package kr.han.meal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kr.han.meal.domain.model.*

internal fun focusedDateContext(date:LocalDate,today:LocalDate)=when(date) {
    today->"오늘"
    today.plusDays(1)->"내일"
    else->if(date.isBefore(today)) "지난 날짜" else "이후 날짜"
}
internal fun effectiveSelectionMode(schedule:Schedule,nowMillis:Long)=
    if(schedule.mode==SelectionMode.MANUAL && schedule.returnAutomatically && schedule.manualUntil?.let {nowMillis>=it}==true) SelectionMode.AUTO else schedule.mode

internal fun selectionFallbackType(schedule:Schedule,nowMillis:Long)=
    if(effectiveSelectionMode(schedule,nowMillis)==SelectionMode.MANUAL) schedule.manualType else schedule.fixedType

internal fun selectionModeLabel(schedule:Schedule,nowMillis:Long,manuallyBrowsing:Boolean=false):String {
    return if(manuallyBrowsing) "수동 선택" else when(effectiveSelectionMode(schedule,nowMillis)) {
        SelectionMode.AUTO->"자동 선택"
        SelectionMode.NEXT->"다음 식사 선택"
        SelectionMode.FIXED->"식사 고정"
        SelectionMode.MANUAL->"수동 선택"
    }
}
internal enum class MealEntryAction(val label:String) {
    DETAIL("알레르기·상세"), DOWNLOAD("내려받기"), RETRY("다시 시도")
}
/** Detail remains discoverable independently of compact mode or widget display preferences. */
internal fun mealEntryAction(status:Availability)=when(status) {
    is Availability.Present->MealEntryAction.DETAIL
    Availability.Unknown->MealEntryAction.DOWNLOAD
    is Availability.Failed->MealEntryAction.RETRY
    is Availability.Absent->null
}
internal fun mealActionLabel(date:LocalDate,type:MealType,status:Availability)=
    "${dateLabel(date)} ${type.label} "+when(mealEntryAction(status)) {
        MealEntryAction.DETAIL->"알레르기·상세 보기"
        MealEntryAction.DOWNLOAD->"급식 내려받기"
        MealEntryAction.RETRY->"다시 시도"
        null->"등록된 급식 없음"
    }
internal fun selectionModeExplanation(mode:SelectionMode)=when(mode) {
    SelectionMode.AUTO->"설정한 시간 규칙에 따라 날짜와 식사를 선택합니다."
    SelectionMode.NEXT->"식사 시간을 기준으로 다음 식사를 선택합니다."
    SelectionMode.FIXED->"선택한 식사 종류를 유지하며 날짜를 선택합니다."
    SelectionMode.MANUAL->"지정한 날짜와 식사를 표시합니다."
}

/** Readable measure, shared gutters and semantic grouping; never stretch prose to a tablet edge. */
internal object MealLayout {
    val gutter = 20.dp
    val settingsWidth = 680.dp
    val readingWidth = 640.dp
    val expandedWidth = 1080.dp
}
@Composable internal fun ReadingPane(maxWidth:Dp=MealLayout.settingsWidth,content:@Composable ()->Unit) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Box(Modifier.widthIn(max=maxWidth).fillMaxSize()) {content()}
    }
}
@Composable internal fun GroupLabel(title:String,description:String?=null) {
    Column(Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.semantics {heading()})
        description?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
/** Pick a stable, chronological comparison window without jumping out of the selected week. */
internal fun comparisonDates(dates:List<LocalDate>,selected:LocalDate,columns:Int):List<LocalDate> {
    if(dates.isEmpty()) return emptyList()
    val count=columns.coerceAtLeast(1).coerceAtMost(dates.size)
    val start=dates.indexOf(selected).coerceAtLeast(0).coerceAtMost(dates.size-count)
    return dates.subList(start,start+count)
}
internal fun weekDates(anchor:LocalDate,excluded:Set<Int>,showExcluded:Boolean):List<LocalDate> {
    val monday=anchor.minusDays(anchor.dayOfWeek.value-1L)
    return (0L..6L).map {monday.plusDays(it)}.filter {showExcluded || it.dayOfWeek.value !in excluded}
}

internal fun schedulePreviewLabel(selection:kr.han.meal.domain.model.Selection):String=when(selection) {
    is kr.han.meal.domain.model.Selection.Selected->"${shortDate(selection.date)} ${selection.type.label}"+(if(selection.confirmedEmpty) "\n등록된 급식 없음" else "")
    is kr.han.meal.domain.model.Selection.NeedsData->"${shortDate(selection.date)} ${selection.type.label}\n조회 필요"
    is kr.han.meal.domain.model.Selection.Failure->selection.message
    is kr.han.meal.domain.model.Selection.Warning->selection.message
    is kr.han.meal.domain.model.Selection.Empty->selection.message
}

internal fun navigationRoot(page:String,history:List<String>):String {
    val roots=setOf("home","weekly","settings")
    return if(page in roots) page else history.lastOrNull {it in roots} ?: "settings"
}

internal fun weeklyHeaderScrolls(heightDp:Float,textScale:Float)=heightDp/textScale.coerceAtLeast(1f)<480f
