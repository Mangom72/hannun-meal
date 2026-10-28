@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package kr.han.meal.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.Role
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
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {Text(title,style=MaterialTheme.typography.bodyLarge);subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        Switch(checked,onCheckedChange=null)
    }
}
@Composable private fun SettingsLink(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit) {
    Surface(onClick=onClick,color=MaterialTheme.colorScheme.surfaceContainerLowest,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Icon(Icons.AutoMirrored.Filled.ArrowForward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun SettingsScreen(p:AppSettings,onPage:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {SectionTitle("급식과 생활")}
        item {SettingsLink("학교 선택",p.school?.name ?: "우리 학교를 찾아보세요",AppIcons.School) {onPage("school")}}
        item {SettingsLink("자동 전환과 식사 시간","표시할 급식 · 요일 · 식사 시간",Icons.Outlined.DateRange) {onPage("schedule")}}
        item {Spacer(Modifier.height(8.dp));SectionTitle("화면과 위젯")}
        item {SettingsLink("화면 꾸미기","테마 · 글자 크기 · 표시 정보",Icons.Outlined.Settings) {onPage("appearance")}}
        item {SettingsLink("위젯 관리","위젯 추가와 개별 설정",AppIcons.Widgets) {onPage("widgets")}}
        item {Spacer(Modifier.height(8.dp));SectionTitle("앱 관리")}
        item {SettingsLink("데이터와 개인정보","서버 연결 · 동기화 · 캐시",Icons.Outlined.Refresh) {onPage("data")}}
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
        if(query.isBlank()) item(key="school-intro") {SectionTitle("우리 학교 찾기","같은 이름의 학교는 지역과 주소로 구분하세요")}
        item(key="school-search") {
            OutlinedTextField(query,{query=it;vm.search(it,type)},Modifier.fillMaxWidth(),label={Text("학교 이름 또는 지역")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon=if(query.isNotEmpty()) {{IconButton(onClick={query="";vm.search("",type)}) {Icon(Icons.Outlined.Close,"검색어 지우기")}}} else null,
                shape=RoundedCornerShape(16.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={vm.search(query,type);keyboard?.hide()}))
        }
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf(null,"초등학교","중학교","고등학교").forEach {t->FilterChip(type==t,{type=t;vm.search(query,t)},{Text(t ?: "전체")},modifier=Modifier.heightIn(min=48.dp))}}}
        if(search.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("전국 학교 목록을 준비하고 있습니다",style=MaterialTheme.typography.bodySmall)}
        search.error?.let {message->item {Notice(message,"다시 시도",vm::prepareSchools)}}
        if(query.isBlank() && !search.loading) item {
            p.school?.let {school->SectionTitle("선택한 학교");SettingsLink(school.name,school.address,AppIcons.School) {pending=school}}
                ?: Text("이름 일부나 지역으로 검색해 보세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(query.isNotBlank() && search.searched && !search.loading && results.isEmpty()) item {Notice("일치하는 학교가 없습니다. 이름·지역 또는 학교급을 바꿔 보세요.")}
        if(query.isNotBlank()) items(results,key={it.id}) {school->
            val chosen=school.id==p.school?.id
            OutlinedCard(onClick={pending=school;keyboard?.hide()},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=if(chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(school.name,style=MaterialTheme.typography.titleMedium)
                        Text(listOf(school.region,school.type).filter {it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Text(school.address,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(chosen) Icon(Icons.Outlined.Check,"선택한 학교",tint=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    pending?.let {school->AlertDialog(onDismissRequest={pending=null},title={Text(school.name)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {Text(school.address);Text("교육청 ${school.office} · 학교 ${school.code}",style=MaterialTheme.typography.bodySmall);Text("이 학교의 급식을 표시합니다. 위젯의 개별 학교 설정은 유지됩니다.")}},confirmButton={TextButton(onClick={vm.choose(school);pending=null;onSelected()}) {Text("이 학교 선택")}},dismissButton={TextButton(onClick={pending=null}) {Text("취소")}})}
}
fun timeText(minute:Int)=if(minute==1440) "24:00" else "%02d:%02d".format(minute/60,minute%60)
@Composable fun TimeButton(label:String,minute:Int,allowEnd:Boolean=false,onChange:(Int)->Unit) {
    val context=LocalContext.current
    OutlinedButton(onClick={TimePickerDialog(context,{_,hour,min->onChange(if(allowEnd && hour==0 && min==0) 1440 else hour*60+min)},if(minute==1440) 0 else minute/60,minute%60,true).show()}) {Text("$label ${timeText(minute)}")}
}
@Composable fun ScheduleScreen(initial:Schedule,onSave:(Schedule)->Unit) {
    var draft by remember(initial) {mutableStateOf(initial)}
    val scroll=rememberScrollState()
    val error=RuleValidator.validate(draft.rules)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SectionTitle("급식을 표시하는 방법", "한국 시간 기준으로 자동 전환합니다")
            ScheduleControls(draft) {draft=it}
            error?.let {Notice(it)}
        }
        Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
            Button(onClick={onSave(draft)},enabled=error==null,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp).heightIn(min=48.dp)) {Text("일정 저장")}
        }
    }
}
@Composable fun ScheduleControls(draft:Schedule,onChange:(Schedule)->Unit) {
    Text("선택 모드",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(SelectionMode.AUTO to "시간 규칙",SelectionMode.NEXT to "다음 식사",SelectionMode.FIXED to "고정 종류",SelectionMode.MANUAL to "날짜 지정").forEach { (mode,label) -> FilterChip(draft.mode==mode,{onChange(draft.copy(mode=mode,manualDate=if(mode==SelectionMode.MANUAL) draft.manualDate ?: java.time.LocalDate.now(SEOUL).toString() else draft.manualDate,manualUntil=if(mode==SelectionMode.MANUAL) kr.han.meal.domain.scheduling.MealTiming.nextRefresh(draft,java.time.Clock.system(SEOUL)).toEpochMilli() else draft.manualUntil))},{Text(label)}) }
    }
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
    Text("급식을 보지 않는 요일",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("월","화","수","목","금","토","일").forEachIndexed { i,s -> FilterChip(i+1 in draft.excludedDays,{onChange(draft.copy(excludedDays=if(i+1 in draft.excludedDays) draft.excludedDays-(i+1) else draft.excludedDays+(i+1)))},{Text(s)}) } }
    if(draft.excludedDays.size==7) Notice("모든 요일이 제외되어 급식을 선택할 수 없습니다")
    Text("표시할 식사",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {MealType.entries.forEach {t->FilterChip(t in draft.enabledTypes,{onChange(draft.copy(enabledTypes=if(t in draft.enabledTypes) draft.enabledTypes-t else draft.enabledTypes+t))},{Text(t.label)})}}
    if(draft.enabledTypes.isEmpty()) Notice("식사 종류를 하나 이상 선택해 주세요")
    Text("등록된 급식이 없을 때",style=MaterialTheme.typography.titleMedium)
    ToggleRow("다음 등록 급식으로 건너뛰기",draft.missingPolicy==MissingPolicy.SKIP,{onChange(draft.copy(missingPolicy=if(it) MissingPolicy.SKIP else MissingPolicy.PRESERVE))},"끄면 선택한 날짜와 식사를 유지합니다. 아직 조회하지 않은 날짜는 결식으로 취급하지 않습니다.")
    ToggleRow("탐색 후 자동 일정으로 복귀",draft.returnAutomatically,{onChange(draft.copy(returnAutomatically=it))},"다음 전환·식사 시각에 복귀합니다. 끄면 직접 자동 모드로 돌릴 때까지 유지합니다.")
    HorizontalDivider()
    Text("식사 시간",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {MealType.entries.forEach {t->TimeButton(t.label,draft.eatingMinutes[t] ?: 720) {onChange(draft.copy(eatingMinutes=draft.eatingMinutes+(t to it)))}}}
    ToggleRow("예정 시간이 지나면 카운트다운 숨김",draft.hidePassedCountdown,{onChange(draft.copy(hidePassedCountdown=it))})
    DisclosureSection("세부 시간 규칙") {
    HorizontalDivider()
    Text("급식 전환 시간",style=MaterialTheme.typography.titleMedium)
    Text("예: 13:00부터 석식을 표시해도 실제 식사 시간은 17:30일 수 있어요. 24:00은 다음 날 00:00입니다. 시작이 끝보다 늦으면 자정을 지나는 구간입니다.",style=MaterialTheme.typography.bodySmall)
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
    Notice("배터리 절약 및 Android 제한으로 위젯 자동 전환은 몇 분 늦어질 수 있습니다. 실제 갱신 시 현재 시간에 맞는 급식을 다시 계산합니다.")
}
@Composable fun TypeChoice(type:MealType,onChoice:(MealType)->Unit) { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {MealType.entries.forEach {t->FilterChip(t==type,{onChoice(t)},{Text(t.label)})}} }

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
@Composable fun AppearanceScreen(p:AppSettings,onUpdate:((AppSettings)->AppSettings)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SectionTitle("편안하게 읽는 급식", "새 위젯에 적용할 기본 모양입니다. 기존 위젯은 개별 설정을 사용합니다.")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("SYSTEM" to "시스템","LIGHT" to "라이트","DARK" to "다크").forEach {(v,label)->FilterChip(p.theme==v,{onUpdate {it.copy(theme=v)}},{Text(label)})}}
        ToggleRow("Material You 색상",p.dynamicColor,{v->onUpdate {it.copy(dynamicColor=v)}},"Android 12 이상에서 배경화면 색상 사용")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("숲","라벤더","살구").forEachIndexed {i,s->FilterChip(p.color==i,{onUpdate {it.copy(color=i,dynamicColor=false)}},{Text(s)})}}
        Text("글자 크기 ${"%.0f".format(p.fontScale*100)}%")
        Slider(p.fontScale,{v->onUpdate {it.copy(fontScale=v)}},valueRange=.8f..1.4f,steps=5)
        Text("위젯 배경 불투명도 ${p.opacity}%")
        Slider(p.opacity.toFloat(),{v->onUpdate {it.copy(opacity=v.toInt())}},valueRange=25f..100f)
        ToggleRow("초 단위 카운트다운 기본값",p.liveCountdown,{v->onUpdate {it.copy(liveCountdown=v)}},"네이티브 타이머 · 갱신 지연 중 음수는 경과 시간입니다. 끄면 갱신 시점 기준 상대 시간을 표시합니다.")
        ToggleRow("간결한 메뉴 표시",p.compact,{v->onUpdate {it.copy(compact=v)}})
        PresetChoices(p.preset) {preset->onUpdate {it.copy(preset=preset,display=if(preset==DisplayPreset.CUSTOM) it.display else DisplayOptions.preset(preset))}}
        DisplayPreview(p.display,p.school?.name)
        DisplayControls(p.display) {d->onUpdate {it.copy(display=d,preset=DisplayPreset.CUSTOM)}}
    }
}
@Composable fun PresetChoices(selected:DisplayPreset,onChoice:(DisplayPreset)->Unit) { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {DisplayPreset.entries.forEach {p->FilterChip(selected==p,{onChoice(p)},{Text(when(p){DisplayPreset.MINIMAL->"최소";DisplayPreset.STANDARD->"표준";DisplayPreset.DETAILED->"자세히";DisplayPreset.CUSTOM->"직접 설정"})})}} }
@Composable fun DisplayPreview(display:DisplayOptions,school:String?) {
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("표시 정보 미리보기",style=MaterialTheme.typography.labelMedium)
        if(display.school) Text(school ?: "선택한 학교")
        Text(listOfNotNull(if(display.date) "10월 26일" else null,if(display.weekday) "월요일" else null,if(display.mealType) "중식" else null).joinToString(" · "))
        Text("실제 메뉴는 동기화 후 표시됩니다")
        if(display.eatingTime) Text("식사 예정 12:00")
        if(display.countdown) Text("식사까지 남은 시간")
        if(display.freshness) Text("데이터 최신 상태")
    }}
}
@Composable fun DataScreen(p:AppSettings,cache:CacheSnapshot,vm:MealViewModel) {
    var confirm by remember {mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionTitle("NEIS와 안전하게 연결", "학교 목록 · 급식 데이터 서버")
        Text("전국 학교 목록은 기기에 저장해 검색하고 급식은 보안 서버에서 월 단위로 받습니다. NEIS 인증키는 서버에서만 관리합니다.",style=MaterialTheme.typography.bodyMedium)
        Text("학교를 선택하면 공식 데이터 서비스에 자동으로 연결됩니다.",style=MaterialTheme.typography.bodyMedium)
        Text("자동 동기화 주기",style=MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(1L,3L,6L,12L,24L).forEach {h->FilterChip(p.syncHours==h,{vm.update {it.copy(syncHours=h)}},{Text("${h}시간")})}}
        Text("최근 동기화 ${syncLabel(p.school?.let {cache.lastSync(it.id)})}")
        Text("저장된 급식 ${cache.meals.size}건 · 학교 ${cache.meals.map {it.schoolId}.distinct().size}곳")
        cache.latestError(p.school?.id.orEmpty())?.let {Notice(it)}
        Button(onClick=vm::refresh) {Text("지금 동기화")}
        OutlinedButton(onClick={confirm="cache"}) {Text("저장된 급식 지우기")}
        OutlinedButton(onClick={confirm="preferences"}) {Text("앱 기본 설정 초기화")}
        HorizontalDivider()
        Text("한눈급식 ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleMedium)
        Text("급식·학교 정보 제공: 교육부 NEIS Open API\n\n로그인, 광고, 분석 도구를 사용하지 않습니다. 학교 검색은 기기 안에서 처리합니다. 학교 목록은 GitHub Pages에서, 학교 코드와 조회 월에 해당하는 급식은 Cloudflare Workers에서 HTTPS로 받습니다. NEIS API 키는 앱에 저장하거나 전송하지 않습니다. 자동 업데이트는 GitHub의 공개 릴리스 정보를 확인하고 파일을 내려받습니다. 계정 인증 정보는 저장하거나 전송하지 않습니다. 데이터는 기기에 보관되며 앱 삭제 시 삭제됩니다. 알레르기 정보는 학교가 제공한 자료로, 식이 안전이 중요할 때 학교에 직접 확인하세요.",style=MaterialTheme.typography.bodyMedium)
    }
    confirm?.let {which->AlertDialog(onDismissRequest={confirm=null},title={Text(if(which=="cache") "저장된 급식을 지울까요?" else "앱 기본 설정을 초기화할까요?")},text={Text(if(which=="cache") "오프라인에서 메뉴를 볼 수 없게 됩니다. 학교와 설정은 유지합니다." else "학교를 포함한 앱 기본 설정을 초기화합니다. 개별 위젯 설정, 업데이트 설정과 캐시는 유지합니다.")},confirmButton={TextButton(onClick={if(which=="cache") vm.clearCache() else vm.resetPreferences();confirm=null}){Text("지우기")}},dismissButton={TextButton(onClick={confirm=null}){Text("취소")}})}
}
