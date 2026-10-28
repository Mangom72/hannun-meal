@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package kr.han.meal.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.RuleValidator
import kr.han.meal.widget.*

@Composable fun WidgetManagementScreen(vm:MealViewModel) {
    val context=LocalContext.current
    val widgets by vm.widgets.collectAsStateWithLifecycle()
    val manager=remember {AppWidgetManager.getInstance(context)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {SectionTitle("급식을 홈 화면에", "크기에 맞춰 메뉴와 시간을 간결하게 보여 줍니다")}
        item {Notice("홈 화면 빈 곳을 길게 누른 뒤 ‘위젯 → 한눈급식’을 선택하세요. 일부 런처에서는 아래 추가 버튼도 사용할 수 있습니다.")}
        items(WidgetKind.entries) {kind->
            OutlinedCard(shape=RoundedCornerShape(16.dp),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(kind.label,style=MaterialTheme.typography.titleMedium)
                    Text(when(kind){WidgetKind.NEXT->"선택한 급식과 남은 시간 · 2×2 / 4×2";WidgetKind.DAILY->"하루 세 끼와 날짜 탐색 · 4×3";WidgetKind.WEEKLY_SELECTED->"원하는 식사만 모아 보기 · 4×3 / 4×4";WidgetKind.WEEKLY_ALL->"한 주의 모든 식사 요약 · 4×4 이상"},style=MaterialTheme.typography.bodyMedium)
                    if(manager.isRequestPinAppWidgetSupported) OutlinedButton(onClick={
                        val accepted=manager.requestPinAppWidget(ComponentName(context,widgetProviders.getValue(kind)),null,null)
                        vm.message.value=if(accepted) "런처에서 추가를 확인하고 위젯의 설정 버튼으로 맞춤 설정하세요" else "홈 화면의 위젯 목록에서 추가해 주세요"
                    }) {Text("홈 화면에 추가")}
                }
            }
        }
        item {SectionTitle("설치된 위젯", "각 위젯의 설정 버튼에서도 변경할 수 있어요")}
        if(widgets.isEmpty()) item {Text("아직 설정한 위젯이 없습니다",style=MaterialTheme.typography.bodyMedium)}
        items(widgets,key={it.id}) {c->
            OutlinedCard(onClick={context.startActivity(Intent(context,WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,c.id))},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text("${c.kind.label} #${c.id}",style=MaterialTheme.typography.titleMedium);Text(c.school?.name ?: "기본 학교 사용");Text("설정 편집",style=MaterialTheme.typography.labelMedium)}
            }
        }
    }
}
@Composable fun WidgetConfigScreen(initial:WidgetConfig,vm:MealViewModel,onCancel:()->Unit,onSave:(WidgetConfig)->Unit) {
    var draft by remember(initial) {mutableStateOf(initial)}
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var query by rememberSaveable {mutableStateOf("")}
    LaunchedEffect(Unit) {vm.prepareSchools()}
    var saving by rememberSaveable {mutableStateOf(false)}
    val search by vm.search.collectAsStateWithLifecycle()
    val p by vm.preferences.collectAsStateWithLifecycle()
    val error=RuleValidator.validate(draft.schedule.rules)
    Scaffold(topBar={TopAppBar(title={Text("위젯 설정")},navigationIcon={TextButton(onClick=onCancel){Text("취소")}},actions={TextButton(onClick={saving=true;onSave(draft)},enabled=error==null && !saving){Text(if(saving) "저장 중" else "완료")}})}) {insets->
        Column(Modifier.padding(insets)) {
            PrimaryTabRow(selectedTabIndex=tab) {listOf("기본","표시","시간 규칙").forEachIndexed {i,s->Tab(selected=tab==i,onClick={tab=i},text={Text(s)})}}
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                when(tab) {
                    0->{
                        SectionTitle(initial.kind.label,"위젯 ID ${draft.id} · 이 위젯에만 적용합니다")
                        Text(draft.school?.name ?: p.school?.name ?: "학교를 선택해 주세요",style=MaterialTheme.typography.titleLarge)
                        Text(draft.school?.address.orEmpty(),style=MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(query,{query=it;vm.search(it)},Modifier.fillMaxWidth(),label={Text("다른 학교 이름")},singleLine=true)
                        OutlinedButton(onClick={vm.search(query)},enabled=query.isNotBlank() && !search.loading){Text("학교 검색")}
                        if(search.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        search.error?.let {Notice(it)}
                        search.results.forEach {school->OutlinedCard(onClick={draft=draft.copy(school=school)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(school.name);Text(school.address,style=MaterialTheme.typography.bodySmall)}}}
                        if(draft.kind in listOf(WidgetKind.WEEKLY_SELECTED,WidgetKind.WEEKLY_ALL)) {
                            Text("날짜 범위",style=MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(draft.rangeMode==RangeMode.CALENDAR,{draft=draft.copy(rangeMode=RangeMode.CALENDAR)},{Text("월–일")});FilterChip(draft.rangeMode==RangeMode.ROLLING,{draft=draft.copy(rangeMode=RangeMode.ROLLING)},{Text("등교일 5일")})}
                        }
                        if(draft.kind==WidgetKind.WEEKLY_SELECTED) {
                            Text("주간에 표시할 식사")
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){MealType.entries.forEach {t->FilterChip(t in draft.weeklyTypes,{draft=draft.copy(weeklyTypes=if(t in draft.weeklyTypes) draft.weeklyTypes-t else draft.weeklyTypes+t)},{Text(t.label)})}}
                            if(draft.weeklyTypes.isEmpty()) Notice("표시할 식사를 하나 이상 선택해 주세요")
                        }
                        if(!vm.graph.configured(p)) Notice("앱의 데이터 설정에서 서버 연결을 설정하면 급식을 내려받습니다. 저장된 캐시는 연결 없이도 볼 수 있습니다.")
                        TextButton(onClick={draft=draft.copy(manualDate=null,manualType=null,manualUntil=null,dateOffset=0,schedule=draft.schedule.copy(mode=SelectionMode.AUTO))}){Text("수동 탐색 초기화 · 자동으로")}
                    }
                    1->{
                        PresetChoices(draft.preset){draft=draft.copy(preset=it,display=if(it==DisplayPreset.CUSTOM) draft.display else DisplayOptions.preset(it))}
                        DisplayPreview(draft.display,draft.school?.name ?: p.school?.name)
                        Text("글자 크기 ${"%.0f".format(draft.fontScale*100)}%")
                        Slider(draft.fontScale,{draft=draft.copy(fontScale=it)},valueRange=.8f..1.4f,steps=5)
                        Text("배경 불투명도 ${draft.opacity}%")
                        Slider(draft.opacity.toFloat(),{draft=draft.copy(opacity=it.toInt())},valueRange=25f..100f)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("숲","라벤더","살구").forEachIndexed {i,s->FilterChip(draft.color==i,{draft=draft.copy(color=i)},{Text(s)})}}
                        ToggleRow("초 단위 라이브 카운트다운",draft.liveCountdown,{draft=draft.copy(liveCountdown=it)},"네이티브 타이머의 음수는 예정 시각 이후 경과입니다. 갱신 지연 중 자동 정지는 보장되지 않습니다. 기본값은 갱신 시점 기준 정적 표시입니다.")
                        ToggleRow("간결한 메뉴",draft.compact,{draft=draft.copy(compact=it)})
                        DisplayControls(draft.display){draft=draft.copy(display=it,preset=DisplayPreset.CUSTOM)}
                    }
                    else->{ScheduleControls(draft.schedule){draft=draft.copy(schedule=it)};error?.let {Notice(it)}}
                }
            }
        }
    }
}
