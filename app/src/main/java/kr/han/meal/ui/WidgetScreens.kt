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
    val p by vm.preferences.collectAsStateWithLifecycle()
    val manager=remember {AppWidgetManager.getInstance(context)}
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var smallPreview by rememberSaveable {mutableStateOf(true)}
    // The launcher is authoritative. Saved configs can outlive removal or be absent on new pins.
    var installed by remember {mutableStateOf(emptyList<WidgetConfig>())}
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner,widgets,p) {
        fun reload() {installed=widgetProviders.flatMap {(kind,provider)->manager.getAppWidgetIds(ComponentName(context,provider)).map {id->widgets.firstOrNull {it.id==id} ?: previewConfig(p,kind).copy(id=id)}}.distinctBy {it.id}}
        reload()
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex=tab) {listOf("추가하기","설치됨 ${installed.size}").forEachIndexed {i,label->Tab(tab==i,{tab=i},text={Text(label)})}}
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns=if(maxWidth.value/androidx.compose.ui.platform.LocalDensity.current.fontScale>=760) 2 else 1
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                if(tab==0) {
                    item {SectionTitle("위젯 미리보기", "저장된 급식으로 표시합니다. 크기는 홈 화면에서 조절할 수 있습니다.")}
                    items(WidgetKind.entries.chunked(columns)) {row->
                        Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                            row.forEach {kind->Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(kind.label,style=MaterialTheme.typography.titleMedium)
                                Text(when(kind){WidgetKind.NEXT->"한 끼와 남은 시간 (2×2, 4×2)";WidgetKind.DAILY->"하루 세 끼 (4×3)";WidgetKind.WEEKLY_SELECTED->"선택한 식사의 주간 메뉴 (4×3, 4×4)";WidgetKind.WEEKLY_ALL->"한 주의 모든 식사 (4×4 이상)"},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(kind==WidgetKind.NEXT) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    FilterChip(smallPreview,{smallPreview=true},{Text("작게 (2×2)")},modifier=Modifier.heightIn(min=48.dp))
                                    FilterChip(!smallPreview,{smallPreview=false},{Text("넓게 (4×2)")},modifier=Modifier.heightIn(min=48.dp))
                                }
                                WidgetPreview(vm,previewConfig(p,kind),height=if(kind==WidgetKind.NEXT) {if(smallPreview) 180 else 220} else 330,width=if(kind==WidgetKind.NEXT && smallPreview) 180 else 340)
                                if(manager.isRequestPinAppWidgetSupported) OutlinedButton(onClick={
                                    val accepted=manager.requestPinAppWidget(ComponentName(context,widgetProviders.getValue(kind)),null,null)
                                    vm.message.value=if(accepted) "홈 화면에 추가하려면 확인을 눌러 주세요" else "홈 화면의 위젯 목록에서 추가해 주세요"
                                }) {Text("${kind.label} 추가")}
                                HorizontalDivider()
                            }}
                        }
                    }
                    item {Text("홈 화면의 빈 곳을 길게 누르고 위젯 목록에서 한눈급식을 선택해도 추가할 수 있습니다. 추가한 위젯은 ‘설치됨’에서 설정합니다.",style=MaterialTheme.typography.bodyMedium)}
                } else {
                    if(installed.isEmpty()) item {Notice("홈 화면에 추가한 위젯이 없습니다.","위젯 추가하기") {tab=0}}
                    items(installed,key={it.id}) {config->
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("${config.kind.label} #${config.id}",style=MaterialTheme.typography.titleMedium)
                            Text(config.school?.name ?: p.school?.name ?: "학교 미선택",style=MaterialTheme.typography.bodyMedium)
                            WidgetPreview(vm,config,height=if(config.kind==WidgetKind.NEXT) 220 else 330)
                            OutlinedButton(onClick={context.startActivity(Intent(context,WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,config.id))}) {Text("이 위젯 설정")}
                            HorizontalDivider()
                        }
                    }
                }
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
        ReadingPane { Column(Modifier.padding(insets)) {
            PrimaryTabRow(selectedTabIndex=tab) {listOf("기본","표시","시간 규칙").forEachIndexed {i,s->Tab(selected=tab==i,onClick={tab=i},text={Text(s)})}}
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                when(tab) {
                    0->{
                        SectionTitle(initial.kind.label,"위젯 #${draft.id}에만 적용합니다")
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
                        if(!vm.graph.configured(p)) Notice("급식 서버에 연결할 수 없습니다. 앱 업데이트를 확인해 주세요. 저장된 급식은 계속 볼 수 있습니다.")
                        TextButton(onClick={draft=draft.copy(manualDate=null,manualType=null,manualUntil=null,dateOffset=0,schedule=draft.schedule.copy(mode=SelectionMode.AUTO))}){Text("자동 선택으로 돌아가기")}
                    }
                    1->{
                        PresetChoices(draft.preset){draft=draft.copy(preset=it,display=if(it==DisplayPreset.CUSTOM) draft.display else DisplayOptions.preset(it))}
                        WidgetPreview(vm,draft,height=if(draft.kind==WidgetKind.NEXT) 240 else 330)
                        Text("선호 글자 크기 ${"%.0f".format(draft.fontScale*100)}%")
                        Text("공간이 넉넉할 때 사용하는 크기입니다. 메뉴가 들어가도록 줄이며, 더 줄이기 어려우면 전체 보기로 안내합니다.",style=MaterialTheme.typography.bodySmall)
                        Slider(draft.fontScale,{draft=draft.copy(fontScale=it)},valueRange=.8f..1.4f,steps=5)
                        Text("배경 불투명도 ${draft.opacity}%")
                        Slider(draft.opacity.toFloat(),{draft=draft.copy(opacity=it.toInt())},valueRange=25f..100f)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("숲","라벤더","살구").forEachIndexed {i,s->FilterChip(draft.color==i,{draft=draft.copy(color=i)},{Text(s)})}}
                        ToggleRow("남은 시간 초 단위 표시",draft.liveCountdown,{draft=draft.copy(liveCountdown=it)},"식사 시간이 지나면 갱신될 때까지 경과 시간이 음수로 표시될 수 있습니다. 끄면 마지막 갱신 시점의 남은 시간을 표시합니다.")
                        ToggleRow("간결한 메뉴",draft.compact,{draft=draft.copy(compact=it)})
                        DisplayControls(draft.display){draft=draft.copy(display=it,preset=DisplayPreset.CUSTOM)}
                    }
                    else->{ScheduleControls(draft.schedule){draft=draft.copy(schedule=it)};error?.let {Notice(it)}}
                }
            }
        }
    }
}
}
