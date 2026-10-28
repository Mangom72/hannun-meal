@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package kr.han.meal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kr.han.meal.data.repository.CacheSnapshot
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.eatingTime
import java.time.*

internal data class MealFocus(val date:LocalDate,val type:MealType)
/** Home follows the existing selection engine, including next-day/unknown selections. */
internal fun mealFocus(selection:Selection,today:LocalDate,fallback:MealType=MealType.LUNCH)=when(selection) {
    is Selection.Selected->MealFocus(selection.date,selection.type)
    is Selection.NeedsData->MealFocus(selection.date,selection.type)
    is Selection.Failure->MealFocus(selection.date,fallback)
    else->MealFocus(today,fallback)
}
private val allergyNames=listOf("난류","우유","메밀","땅콩","대두","밀","고등어","게","새우","돼지고기","복숭아","토마토","아황산류","호두","닭고기","쇠고기","오징어","조개류","잣")
internal fun allergyLabel(numbers:List<Int>)=numbers.distinct().filter {it in 1..19}.joinToString(" · ") {"$it ${allergyNames[it-1]}"}

@Composable fun SchoolToolbarTitle(school:School,fontScale:Float,caption:String="한눈급식",onChange:()->Unit) {
    val large=LocalDensity.current.fontScale*fontScale>1.3f
    Column(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button,onClick=onChange),verticalArrangement=Arrangement.Center) {
        if(!large) Text(listOf(caption,school.region).filter {it.isNotBlank()}.joinToString(" · "),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(school.name,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable fun MealChoices(selected:MealType?,onSelect:(MealType)->Unit,enabled:Set<MealType> = MealType.entries.toSet(),onAutomatic:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        MealType.entries.forEach {type->FilterChip(selected=type==selected,onClick={onSelect(type)},label={Text(type.label)},modifier=Modifier.heightIn(min=48.dp),leadingIcon=if(type==selected) {{Icon(Icons.Outlined.Check,null,Modifier.size(18.dp))}} else null,
            enabled=type in enabled)
        }
        onAutomatic?.let {action->TextButton(onClick=action,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="자동 선택으로 돌아가기"}) {Text("자동")}}
    }
}
@Composable fun DatePickerOverlay(date:LocalDate,onDismiss:()->Unit,onDate:(LocalDate)->Unit) {
    val state=rememberDatePickerState(initialSelectedDateMillis=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(onDismissRequest=onDismiss,confirmButton={TextButton(onClick={state.selectedDateMillis?.let {onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())};onDismiss()}) {Text("날짜 선택")}},dismissButton={TextButton(onClick=onDismiss) {Text("취소")}}) {DatePicker(state)}
}
@Composable fun DisclosureSection(title:String,initiallyExpanded:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    var expanded by rememberSaveable(title) {mutableStateOf(initiallyExpanded)}
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).semantics {stateDescription=if(expanded) "펼침" else "접힘"},contentPadding=PaddingValues(vertical=12.dp)) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            Icon(if(expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,null)
        }
        if(expanded) Column(Modifier.fillMaxWidth().padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable fun MenuLines(meal:Meal,showAllergens:Boolean,large:Boolean=false) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        meal.dishes.forEach {dish->
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(dish.name,style=if(large) MaterialTheme.typography.bodyLarge.copy(fontSize=MaterialTheme.typography.bodyLarge.fontSize*1.125f) else MaterialTheme.typography.bodyLarge)
                if(showAllergens && dish.allergens.isNotEmpty()) Text("알레르기 ${allergyLabel(dish.allergens)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(meal.dishes.isEmpty()) Text("메뉴 정보는 원문에서 확인해 주세요",style=MaterialTheme.typography.bodyMedium)
    }
}

/** Complete detail content stays in one native sheet; close and title remain visible while scrolling. */
@Composable fun MealDetailDialog(target:DetailTarget,cache:CacheSnapshot,schedule:Schedule,schoolName:String?=null,onDismiss:()->Unit) {
    val status=cache.availability(target.school,target.date,target.type)
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    schoolName?.let {Text(it,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}
                    Text(dateLabel(target.date),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${target.type.label} 메뉴",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics {heading()})
                }
                IconButton(onClick=onDismiss) {Icon(Icons.Outlined.Close,"닫기")}
            }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(10.dp)) {Text("식사 ${eatingTime(schedule,target.type)}",Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge)}
                        if(status is Availability.Present && status.meal.calories.isNotBlank()) Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=RoundedCornerShape(10.dp)) {Text(status.meal.calories,Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge)}
                    }
                }
                if(status is Availability.Present) {
                    item {SelectionContainer {MenuLines(status.meal,showAllergens=true,large=true)}}
                    item {Text("알레르기는 메뉴 아래의 번호와 식품명을 확인하세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    item {DisclosureSection("영양 정보") {SelectionContainer {Text(status.meal.nutrition.ifBlank {"등록된 영양 정보가 없습니다"},style=MaterialTheme.typography.bodyMedium)}}}
                    item {DisclosureSection("원산지") {SelectionContainer {Text(status.meal.origin.ifBlank {"등록된 원산지 정보가 없습니다"},style=MaterialTheme.typography.bodyMedium)}}}
                    item {DisclosureSection("알레르기 번호 안내") {Text(allergyLabel((1..19).toList()),style=MaterialTheme.typography.bodyMedium)}}
                    item {DisclosureSection("NEIS 원문") {SelectionContainer {Text(status.meal.raw,style=MaterialTheme.typography.bodySmall)}}}
                } else item {Notice(stateText(status))}
            }
        }
    }
}
