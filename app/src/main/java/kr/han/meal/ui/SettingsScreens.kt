@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package kr.han.meal.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.han.meal.BuildConfig
import kr.han.meal.data.repository.CacheSnapshot
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.RuleValidator
import kotlinx.coroutines.launch

@Composable fun ToggleRow(title:String,checked:Boolean,onChecked:(Boolean)->Unit,subtitle:String?=null) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(value=checked,role=Role.Switch,onValueChange=onChecked).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {Text(title,style=MaterialTheme.typography.bodyLarge);subtitle?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        Switch(checked,onCheckedChange=null)
    }
}
@Composable private fun SettingsLink(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit) {
    Surface(onClick=onClick,color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(horizontal=4.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Icon(Icons.AutoMirrored.Filled.ArrowForward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun SettingsScreen(p:AppSettings,onPage:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {GroupLabel("급식")}
        item {SettingsLink("학교 선택",p.school?.name ?: "학교를 선택해 주세요",AppIcons.School) {onPage("school")}}
        item {SettingsLink("자동 전환과 식사 시간","급식 전환 방식과 제외 요일",Icons.Outlined.DateRange) {onPage("schedule")}}
        item {HorizontalDivider();GroupLabel("화면과 위젯")}
        item {SettingsLink("화면 설정","테마와 글자 크기",Icons.Outlined.Settings) {onPage("appearance")}}
        item {SettingsLink("위젯 관리","미리보기와 홈 화면에 추가",AppIcons.Widgets) {onPage("widgets")}}
        item {HorizontalDivider();GroupLabel("앱 관리")}
        item {SettingsLink("데이터와 개인정보","동기화와 저장 데이터 관리",Icons.Outlined.Refresh) {onPage("data")}}
        item {SettingsLink("앱 업데이트","새 버전 확인과 설치",Icons.Outlined.Info) {onPage("updates")}}
    }
}
@Composable fun SchoolScreen(vm:MealViewModel,onSelected:()->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var type by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit) {vm.search(query,type);vm.prepareSchools()}
    var pending by remember {mutableStateOf<School?>(null)}
    val search by vm.search.collectAsStateWithLifecycle()
    val p by vm.preferences.collectAsStateWithLifecycle()
    val keyboard=LocalSoftwareKeyboardController.current
    val results=search.results.filter {type==null || it.type==type}
    LazyColumn(Modifier.imePadding(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(query.isBlank()) item(key="school-intro") {Text("이름이 같은 학교는 주소를 확인해 주세요",style=MaterialTheme.typography.bodyMedium)}
        item(key="school-search") {
            OutlinedTextField(query,{query=it;vm.search(it,type)},Modifier.fillMaxWidth(),label={Text("학교 이름 또는 지역")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon=if(query.isNotEmpty()) {{IconButton(onClick={query="";vm.search("",type)}) {Icon(Icons.Outlined.Close,"검색어 지우기")}}} else null,
                shape=RoundedCornerShape(16.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={vm.search(query,type);keyboard?.hide()}))
        }
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf(null,"초등학교","중학교","고등학교").forEach {t->FilterChip(type==t,{type=t;vm.search(query,t)},{Text(t ?: "전체")},modifier=Modifier.heightIn(min=48.dp))}}}
        if(search.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("학교 목록을 불러오는 중입니다",style=MaterialTheme.typography.bodySmall)}
        search.error?.let {message->item {Notice(message,"다시 시도",vm::prepareSchools)}}
        if(query.isBlank() && !search.loading) item {
            p.school?.let {school->SectionTitle("선택한 학교");SettingsLink(school.name,school.address,AppIcons.School) {pending=school}}
                ?: Text("학교 이름 일부나 지역을 입력해 주세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(query.isNotBlank() && search.searched && !search.loading && results.isEmpty()) item {Notice("검색 결과가 없습니다. 검색어나 학교급을 바꿔 주세요.")}
        if(query.isNotBlank()) items(results,key={it.id}) {school->
            val chosen=school.id==p.school?.id
            Surface(onClick={pending=school;keyboard?.hide()},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(8.dp),color=if(chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(horizontal=4.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(school.name,style=MaterialTheme.typography.titleMedium)
                        Text(listOf(school.region,school.type).filter {it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Text(school.address,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(chosen) Icon(Icons.Outlined.Check,"선택한 학교",tint=MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        }
    }
    pending?.let {school->AlertDialog(onDismissRequest={pending=null},title={Text(school.name)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {Text(school.address);Text("이 학교의 급식을 표시합니다. 위젯의 개별 학교 설정은 유지됩니다.")}},confirmButton={TextButton(onClick={vm.choose(school);pending=null;onSelected()}) {Text("이 학교 선택")}},dismissButton={TextButton(onClick={pending=null}) {Text("취소")}})}
}
fun timeText(minute:Int)=if(minute==1440) "24:00" else "%02d:%02d".format(minute/60,minute%60)
@Composable fun TimeButton(label:String,minute:Int,allowEnd:Boolean=false,onChange:(Int)->Unit) {
    val context=LocalContext.current
    OutlinedButton(onClick={TimePickerDialog(context,{_,hour,min->onChange(if(allowEnd && hour==0 && min==0) 1440 else hour*60+min)},if(minute==1440) 0 else minute/60,minute%60,true).show()},modifier=Modifier.heightIn(min=48.dp)) {Text("$label ${timeText(minute)}")}
}
@Composable fun ScheduleScreen(initial:Schedule,vm:MealViewModel,onSave:(Schedule)->Unit) {
    var draft by remember(initial) {mutableStateOf(initial)}
    val scroll=rememberScrollState()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val settings by vm.preferences.collectAsStateWithLifecycle()
    val error=RuleValidator.validate(draft.rules)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("한국 시간 기준으로 급식을 전환합니다",style=MaterialTheme.typography.bodyMedium)
            val school=settings.school
            val selection=kr.han.meal.domain.selection.MealSelector(vm.graph.clock).select(draft) {date,type->school?.let {cache.availability(it.id,date,type)} ?: Availability.Unknown}
            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("현재 설정 미리보기",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    Text(schedulePreviewLabel(selection),style=MaterialTheme.typography.titleMedium)
                    if(error==null) Text("다음 일정 확인 ${kr.han.meal.domain.scheduling.MealTiming.nextRefresh(draft,vm.graph.clock).atZone(SEOUL).format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"))}",style=MaterialTheme.typography.bodyMedium)
                    Text("저장된 급식으로 미리 봅니다. 변경 사항은 아래에서 저장해 주세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ScheduleControls(draft) {draft=it}
            error?.let {Notice(it)}
        }
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
            Button(onClick={onSave(draft)},enabled=error==null,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp).heightIn(min=48.dp)) {Text("일정 저장")}
        }
    }
}
@Composable fun ScheduleControls(draft:Schedule,onChange:(Schedule)->Unit) {
    GroupLabel("기본 설정")
    Text("선택 모드",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        listOf(SelectionMode.AUTO to "시간 규칙",SelectionMode.NEXT to "다음 식사",SelectionMode.FIXED to "식사 고정",SelectionMode.MANUAL to "날짜 지정").forEach { (mode,label) -> FilterChip(draft.mode==mode,{onChange(draft.copy(mode=mode,manualDate=if(mode==SelectionMode.MANUAL) draft.manualDate ?: java.time.LocalDate.now(SEOUL).toString() else draft.manualDate,manualUntil=if(mode==SelectionMode.MANUAL) kr.han.meal.domain.scheduling.MealTiming.nextRefresh(draft,java.time.Clock.system(SEOUL)).toEpochMilli() else draft.manualUntil))},{Text(label)},modifier=Modifier.heightIn(min=48.dp)) }
    }
    Text(selectionModeExplanation(draft.mode),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(draft.mode==SelectionMode.FIXED) TypeChoice(draft.fixedType) {onChange(draft.copy(fixedType=it))}
    if(draft.mode==SelectionMode.MANUAL) {
        var showDate by remember {mutableStateOf(false)}
        val date=draft.manualDate ?: java.time.LocalDate.now(SEOUL).toString()
        OutlinedButton(onClick={showDate=true}) {Text("지정 날짜 $date")}
        TypeChoice(draft.manualType) {onChange(draft.copy(manualType=it))}
        if(showDate) {
            val state=rememberDatePickerState(initialSelectedDateMillis=java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest={showDate=false},confirmButton={TextButton(onClick={state.selectedDateMillis?.let {onChange(draft.copy(manualDate=java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),manualUntil=kr.han.meal.domain.scheduling.MealTiming.nextRefresh(draft,java.time.Clock.system(SEOUL)).toEpochMilli()))};showDate=false}){Text("선택")}},dismissButton={TextButton(onClick={showDate=false}){Text("취소")}}) {DatePicker(state)}
        }
    }
    Text("자동 선택에서 제외할 요일",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) { listOf("월","화","수","목","금","토","일").forEachIndexed { i,s -> FilterChip(i+1 in draft.excludedDays,{onChange(draft.copy(excludedDays=if(i+1 in draft.excludedDays) draft.excludedDays-(i+1) else draft.excludedDays+(i+1)))},{Text(s)},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="${s}요일 자동 선택 제외"}) } }
    if(draft.excludedDays.size==7) Notice("모든 요일이 제외되어 급식을 선택할 수 없습니다")
    Text("자동 선택에 포함할 식사",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {MealType.entries.forEach {t->FilterChip(t in draft.enabledTypes,{onChange(draft.copy(enabledTypes=if(t in draft.enabledTypes) draft.enabledTypes-t else draft.enabledTypes+t))},{Text(t.label)},modifier=Modifier.heightIn(min=48.dp))}}
    Text("제외한 요일과 식사도 오늘 화면에서 직접 선택해 볼 수 있습니다.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(draft.enabledTypes.isEmpty()) Notice("식사 종류를 하나 이상 선택해 주세요")
    Text("등록된 급식이 없을 때",style=MaterialTheme.typography.titleMedium)
    ToggleRow("다음 등록 급식으로 건너뛰기",draft.missingPolicy==MissingPolicy.SKIP,{onChange(draft.copy(missingPolicy=if(it) MissingPolicy.SKIP else MissingPolicy.PRESERVE))},"끄면 선택한 날짜와 식사를 유지합니다. 아직 조회하지 않은 날짜는 결식으로 취급하지 않습니다.")
    ToggleRow("탐색 후 자동 일정으로 복귀",draft.returnAutomatically,{onChange(draft.copy(returnAutomatically=it))},"다음 급식 전환 또는 식사 시간에 복귀합니다. 끄면 직접 자동 선택으로 돌아갈 때까지 유지합니다.")
    HorizontalDivider()
    Text("식사 시간",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {MealType.entries.forEach {t->TimeButton(t.label,draft.eatingMinutes[t] ?: 720) {onChange(draft.copy(eatingMinutes=draft.eatingMinutes+(t to it)))}}}
    ToggleRow("예정 시간이 지나면 카운트다운 숨김",draft.hidePassedCountdown,{onChange(draft.copy(hidePassedCountdown=it))})
    DisclosureSection("고급 시간 규칙") {
    HorizontalDivider()
    Text("급식 전환 시간",style=MaterialTheme.typography.titleMedium)
    Text("식사 시간과 별도로 급식을 바꿀 시간을 정합니다. 24:00은 다음 날 00:00이며, 시작이 끝보다 늦으면 자정을 지나는 구간입니다.",style=MaterialTheme.typography.bodySmall)
    draft.rules.forEachIndexed { index,rule ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                fun replace(r:TimeRule) {onChange(draft.copy(rules=draft.rules.toMutableList().also {it[index]=r}))}
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {TimeButton("시작",rule.start) {replace(rule.copy(start=it))};TimeButton("끝",rule.end,true) {replace(rule.copy(end=it))} }
                ToggleRow("다음 가능한 식사",rule.action.nextAvailable,{replace(rule.copy(action=rule.action.copy(nextAvailable=it)))})
                if(!rule.action.nextAvailable) {
                    TypeChoice(rule.action.type) {replace(rule.copy(action=rule.action.copy(type=it)))}
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        OutlinedButton(onClick={replace(rule.copy(action=rule.action.copy(dayOffset=(rule.action.dayOffset-1).coerceAtLeast(0))))},enabled=rule.action.dayOffset>0) {Text("−")}
                        Text(if(rule.action.dayOffset==0) "  오늘  " else "  ${rule.action.dayOffset}일 후  ")
                        OutlinedButton(onClick={replace(rule.copy(action=rule.action.copy(dayOffset=(rule.action.dayOffset+1).coerceAtMost(20))))},enabled=rule.action.dayOffset<20) {Text("+")}
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={
                        val length=if(rule.end>rule.start) rule.end-rule.start else 1440-rule.start+rule.end
                        val mid=(rule.start+length/2)%1440
                        val rules=draft.rules.toMutableList();rules[index]=rule.copy(end=mid);rules.add(index+1,rule.copy(start=mid));onChange(draft.copy(rules=rules))
                    },enabled=draft.rules.size<12 && (rule.end-rule.start+1440)%1440 !=1) {Text("구간 나누기")}
                    TextButton(onClick={
                        val rules=draft.rules.toMutableList();val prev=if(index==0) rules.lastIndex else index-1
                        rules[prev]=rules[prev].copy(end=rule.end);rules.removeAt(index);onChange(draft.copy(rules=rules))
                    },enabled=draft.rules.size>1) {Text("앞 구간에 합치기")}
                }
            }
        }
    }
    TextButton(onClick={onChange(draft.copy(rules=defaultRules()))}) {Text("기본 시간 규칙 복원")}
    }
    Text("절전 설정에 따라 위젯 전환이 늦어질 수 있습니다. 갱신할 때 현재 시간에 맞는 급식을 표시합니다.")
}
@Composable fun TypeChoice(type:MealType,onChoice:(MealType)->Unit) { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {MealType.entries.forEach {t->FilterChip(t==type,{onChoice(t)},{Text(t.label)},modifier=Modifier.heightIn(min=48.dp))}} }

@Composable fun DisplayControls(options:DisplayOptions,onChange:(DisplayOptions)->Unit) {
    ToggleRow("날짜",options.date,{onChange(options.copy(date=it))})
    ToggleRow("요일",options.weekday,{onChange(options.copy(weekday=it))})
    ToggleRow("학교 이름",options.school,{onChange(options.copy(school=it))})
    ToggleRow("식사 종류",options.mealType,{onChange(options.copy(mealType=it))})
    ToggleRow("실제 식사 시간",options.eatingTime,{onChange(options.copy(eatingTime=it))})
    ToggleRow("남은 시간",options.countdown,{onChange(options.copy(countdown=it))})
    ToggleRow("열량",options.calories,{onChange(options.copy(calories=it))})
    ToggleRow("알레르기 번호",options.allergens,{onChange(options.copy(allergens=it))})
    ToggleRow("마지막 동기화",options.lastSync,{onChange(options.copy(lastSync=it))})
    ToggleRow("다음 급식 미리보기",options.nextPreview,{onChange(options.copy(nextPreview=it))})
    ToggleRow("데이터 최신 상태",options.freshness,{onChange(options.copy(freshness=it))})
}
@Composable fun AppearanceScreen(p:AppSettings,vm:MealViewModel,onUpdate:((AppSettings)->AppSettings)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        GroupLabel("테마와 색상")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {listOf("SYSTEM" to "시스템","LIGHT" to "라이트","DARK" to "다크").forEach {(v,label)->FilterChip(p.theme==v,{onUpdate {it.copy(theme=v)}},{Text(label)},modifier=Modifier.heightIn(min=48.dp))}}
        ToggleRow("Material You 색상",p.dynamicColor,{v->onUpdate {it.copy(dynamicColor=v)}},"Android 12 이상에서 배경화면 색상 사용")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {listOf("숲","라벤더","살구").forEachIndexed {i,s->FilterChip(p.color==i,{onUpdate {it.copy(color=i,dynamicColor=false)}},{Text(s)},modifier=Modifier.heightIn(min=48.dp))}}
        GroupLabel("글자와 메뉴")
        Text("글자 크기 ${"%.0f".format(p.fontScale*100)}%")
        Slider(p.fontScale,{v->onUpdate {it.copy(fontScale=v)}},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="글자 크기"},valueRange=.8f..1.4f,steps=5)
        ToggleRow("간결한 메뉴 표시",p.compact,{v->onUpdate {it.copy(compact=v)}})
        GroupLabel("위젯 미리보기", "새 위젯에 적용할 기본값입니다. 기존 위젯은 개별 설정을 사용합니다. 밝은 모드와 어두운 모드는 기기 설정을 따릅니다.")
        PresetChoices(p.preset) {preset->onUpdate {it.copy(preset=preset,display=if(preset==DisplayPreset.CUSTOM) it.display else DisplayOptions.preset(preset))}}
        WidgetPreview(vm,previewConfig(p,WidgetKind.NEXT),height=240)
        Text("위젯 배경 불투명도 ${p.opacity}%")
        Slider(p.opacity.toFloat(),{v->onUpdate {it.copy(opacity=v.toInt())}},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="위젯 배경 불투명도"},valueRange=25f..100f)
        ToggleRow("남은 시간 초 단위 표시",p.liveCountdown,{v->onUpdate {it.copy(liveCountdown=v)}},"식사 시간이 지나면 갱신될 때까지 경과 시간이 음수로 표시될 수 있습니다. 끄면 마지막 갱신 시점의 남은 시간을 표시합니다.")
        DisclosureSection("위젯에 표시할 정보") {DisplayControls(p.display) {d->onUpdate {it.copy(display=d,preset=DisplayPreset.CUSTOM)}}}
    }
}
@Composable fun PresetChoices(selected:DisplayPreset,onChoice:(DisplayPreset)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        DisplayPreset.entries.forEach {preset->FilterChip(selected==preset,{onChoice(preset)},{Text(when(preset){DisplayPreset.MINIMAL->"최소";DisplayPreset.STANDARD->"표준";DisplayPreset.DETAILED->"자세히";DisplayPreset.CUSTOM->"직접 설정"})},modifier=Modifier.heightIn(min=48.dp))}
    }
    Text(when(selected){DisplayPreset.MINIMAL->"날짜와 메뉴";DisplayPreset.STANDARD->"학교, 메뉴, 식사 시간과 남은 시간";DisplayPreset.DETAILED->"열량, 알레르기, 동기화 시각과 다음 급식도 표시";DisplayPreset.CUSTOM->"표시할 정보를 직접 선택"},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable fun DataScreen(p:AppSettings,cache:CacheSnapshot,vm:MealViewModel) {
    var confirm by remember {mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionTitle("동기화와 저장 공간")
        Text("학교 목록과 내려받은 급식을 기기에 저장합니다. 급식은 한 달 단위로 갱신합니다.",style=MaterialTheme.typography.bodyMedium)
        Text("자동 동기화 주기",style=MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(1L,3L,6L,12L,24L).forEach {h->FilterChip(p.syncHours==h,{vm.update {it.copy(syncHours=h)}},{Text("${h}시간")})}}
        Text("최근 동기화 ${syncLabel(p.school?.let {cache.lastSync(it.id)})}")
        Text("학교 ${cache.meals.map {it.schoolId}.distinct().size}곳의 급식 ${cache.meals.size}건")
        cache.latestError(p.school?.id.orEmpty())?.let {Notice(it)}
        Button(onClick=vm::refresh) {Text("지금 동기화")}
        HorizontalDivider()
        GroupLabel("개인정보와 출처")
        Text("한눈급식 ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleMedium)
        Text("학교와 급식 정보: 교육부 NEIS Open API",style=MaterialTheme.typography.bodyMedium)
        GroupLabel("기기에 보관하는 정보")
        Text("선택한 학교, 설정과 내려받은 급식을 기기에 보관합니다. 학교 검색은 기기 안에서 처리하며 앱 삭제 시 저장 데이터도 삭제됩니다.",style=MaterialTheme.typography.bodyMedium)
        GroupLabel("서비스에 요청하는 정보")
        Text("학교 목록은 GitHub Pages에서, 학교 코드와 조회 월에 해당하는 급식은 Cloudflare Workers에서 HTTPS로 받습니다. NEIS API 키는 앱에 저장하거나 전송하지 않습니다.",style=MaterialTheme.typography.bodyMedium)
        Text("로그인, 광고, 분석 도구를 사용하지 않습니다. 업데이트는 GitHub 공개 릴리스에서 받으며 계정 인증 정보는 저장하거나 전송하지 않습니다.",style=MaterialTheme.typography.bodyMedium)
        Text("알레르기 정보는 학교가 제공한 자료입니다. 알레르기가 있다면 먹기 전에 학교에 확인해 주세요.",style=MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        GroupLabel("데이터 삭제와 초기화")
        TextButton(onClick={confirm="cache"},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("저장된 급식 지우기")}
        TextButton(onClick={confirm="preferences"},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("앱 기본 설정 초기화")}
    }
    confirm?.let {which->AlertDialog(onDismissRequest={confirm=null},title={Text(if(which=="cache") "저장된 급식을 지울까요?" else "앱 기본 설정을 초기화할까요?")},text={Text(if(which=="cache") "오프라인에서 메뉴를 볼 수 없게 됩니다. 학교와 설정은 유지합니다." else "학교를 포함한 앱 기본 설정을 초기화합니다. 개별 위젯 설정, 업데이트 설정과 저장된 급식은 유지합니다.")},confirmButton={TextButton(onClick={if(which=="cache") vm.clearCache() else vm.resetPreferences();confirm=null;vm.message.value=if(which=="cache") "저장된 급식을 지웠습니다" else "앱 기본 설정을 초기화했습니다"}){Text(if(which=="cache") "급식 삭제" else "기본 설정 초기화")}},dismissButton={TextButton(onClick={confirm=null}){Text("취소")}})}
}
