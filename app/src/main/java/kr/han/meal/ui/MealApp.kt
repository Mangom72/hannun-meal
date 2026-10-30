@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package kr.han.meal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.han.meal.data.repository.CacheSnapshot
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.*
import kr.han.meal.domain.scheduling.MealTiming
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val korean=Locale.KOREAN
fun dateLabel(date: LocalDate)=date.format(DateTimeFormatter.ofPattern("M월 d일 E요일",korean))
fun shortDate(date: LocalDate)=date.format(DateTimeFormatter.ofPattern("M/d (E)",korean))
fun syncLabel(ms: Long?)=ms?.let { Instant.ofEpochMilli(it).atZone(SEOUL).format(DateTimeFormatter.ofPattern("M/d HH:mm")) } ?: "아직 동기화하지 않음"
data class DetailTarget(val date: LocalDate,val type: MealType,val school: String)

@Composable fun MealApp(vm: MealViewModel, initialDate: String?=null, initialType: String?=null, initialSchool: String?=null, initialWeekly: Boolean=false) {
    val p by vm.preferences.collectAsStateWithLifecycle()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle()
    val configs by vm.widgets.collectAsStateWithLifecycle()
    val openedSchool = configs.mapNotNull {it.school}.firstOrNull {it.id==initialSchool} ?: initialSchool?.split(":")?.takeIf {it.size==2}?.let {School(it[0],it[1],"위젯에서 선택한 학교")}
    val weeklyPreferences=if(initialWeekly && openedSchool!=null) p.copy(school=openedSchool) else p
    val busy by vm.busy.collectAsStateWithLifecycle()
    val online by vm.graph.online.collectAsStateWithLifecycle()
    val updateState by vm.graph.updateState.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if(initialWeekly) "weekly" else "home") }
    var detail by remember { mutableStateOf<DetailTarget?>(null) }
    val snack=remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it);vm.message.value=null } }
    LaunchedEffect(initialDate,initialType,p.school) {
        if(initialDate!=null && initialType!=null) runCatching { DetailTarget(LocalDate.parse(initialDate),MealType.valueOf(initialType),initialSchool ?: p.school?.id.orEmpty()) }.getOrNull()?.let { detail=it }
    }
    LaunchedEffect(selected) { (selected as? Selection.NeedsData)?.let { vm.ensureRange(it.date,it.date.plusDays(20)) } }
    var history by rememberSaveable {mutableStateOf(listOf<String>())}
    val savedPages=rememberSaveableStateHolder()
    fun navigate(target:String) {if(target!=page) {history=history+page;page=target}}
    fun back() {page=history.lastOrNull() ?: "home";history=history.dropLast(1)}
    fun destination(target:String) {history=emptyList();page=target}
    val primary=listOf(Triple("home","오늘",Icons.Outlined.Home),Triple("weekly","주간",Icons.Outlined.DateRange),Triple("settings","설정",Icons.Outlined.Settings))
    val isRoot=page in primary.map {it.first}
    val activeRoot=navigationRoot(page,history)
    BackHandler(enabled=detail==null && (!isRoot || page!="home")) {if(history.isNotEmpty()) back() else destination("home")}
    MealTheme(p) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rail=maxWidth>=720.dp
            Row(Modifier.fillMaxSize()) {
                if(rail) NavigationRail(Modifier.fillMaxHeight(),containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.height(24.dp))
                    primary.forEach {(key,label,icon)->NavigationRailItem(selected=activeRoot==key,onClick={destination(key)},icon={Icon(icon,null)},label={Text(label)},modifier=Modifier.padding(vertical=8.dp))}
                }
                Scaffold(modifier=Modifier.weight(1f),snackbarHost={SnackbarHost(snack)},
                    topBar={TopAppBar(title={
                        if(page in listOf("home","weekly") && (if(page=="weekly") weeklyPreferences.school else p.school)!=null)
                            SchoolToolbarTitle((if(page=="weekly") weeklyPreferences.school else p.school)!!,p.fontScale,caption=if(page=="weekly") "주간 급식" else "한눈급식") {navigate("school")}
                        else Text(when(page) {"home"->"한눈급식";"weekly"->"주간 급식";"widgets"->"위젯";"school"->"학교 선택";"schedule"->"자동 전환";"appearance"->"화면 설정";"data"->"데이터와 개인정보";"updates"->"앱 업데이트";else->"설정"},style=MaterialTheme.typography.titleLarge)
                    },navigationIcon={if(!isRoot) IconButton(onClick=::back) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"뒤로")}},actions={
                        if(page in listOf("home","weekly")) IconButton(onClick=vm::refresh,enabled=!busy) {Icon(Icons.Outlined.Refresh,"급식 새로고침")}
                    })},bottomBar={if(!rail) NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
                        primary.forEach {(key,label,icon)->NavigationBarItem(selected=activeRoot==key,onClick={destination(key)},icon={Icon(icon,null)},label={Text(label)})}
                    }}
                ) {insets->
                    ReadingPane(if(page in listOf("home","weekly","widgets")) MealLayout.expandedWidth else MealLayout.settingsWidth) {
                        Column(Modifier.padding(insets).fillMaxSize()) {
                            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(!online) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {Text("오프라인입니다. 저장된 급식만 볼 수 있습니다",Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium)}
                            if(updateState.snapshot.candidate!=null && page!="updates") TextButton(onClick={navigate("updates")}) {Text("${updateState.snapshot.candidate!!.manifest.versionName} 업데이트")}
                            savedPages.SaveableStateProvider(page) {
                                when(page) {
                                    "home"->HomeScreen(p,cache,selected,vm,{navigate("school")},{navigate("data")}) {detail=it}
                                    "weekly"->WeeklyScreen(weeklyPreferences,cache,vm,initialDate) {detail=it}
                                    "school"->SchoolScreen(vm) {back()}
                                    "schedule"->ScheduleScreen(p.schedule,vm) {schedule->vm.update {it.copy(schedule=schedule)};vm.message.value="자동 전환 설정을 저장했습니다";back()}
                                    "appearance"->AppearanceScreen(p,vm,vm::update)
                                    "data"->DataScreen(p,cache,vm)
                                    "updates"->UpdateScreen(vm)
                                    "widgets"->WidgetManagementScreen(vm)
                                    else->SettingsScreen(p,::navigate)
                                }
                            }
                        }
                    }
                }
            }
        }
        detail?.let { target -> MealDetailDialog(target,cache,p.schedule,schoolName=(listOfNotNull(p.school)+configs.mapNotNull {it.school}).firstOrNull {it.id==target.school}?.name) { detail=null } }
    }
}

@Composable fun SectionTitle(title: String, subtitle: String?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun Notice(text: String, action: String?=null, onAction:()->Unit={}) {
    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(text,style=MaterialTheme.typography.bodyMedium)
            action?.let { TextButton(onClick=onAction) { Text(it) } }
        }
    }
}
@Composable fun DateNavigation(date:LocalDate,onPrevious:()->Unit,onNext:()->Unit,extra:String?=null,onDateClick:(()->Unit)?=null,onToday:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onPrevious) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"이전 날짜")}
        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
            if(onDateClick!=null) TextButton(onClick=onDateClick,modifier=Modifier.heightIn(min=48.dp)) {Text(dateLabel(date),style=MaterialTheme.typography.titleMedium)}
            else Text(dateLabel(date),style=MaterialTheme.typography.titleMedium)
            extra?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        IconButton(onClick=onNext) {Icon(Icons.AutoMirrored.Filled.ArrowForward,"다음 날짜")}
        onToday?.let {TextButton(onClick=it,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="오늘로"},contentPadding=PaddingValues(horizontal=8.dp)) {Text("오늘")}}
    }
}
fun stateText(a: Availability)=when(a) {
    is Availability.Present -> ""
    is Availability.Absent -> "등록된 급식이 없습니다"
    Availability.Unknown -> "아직 내려받지 않은 날짜입니다"
    is Availability.Failed -> a.message
}
@Composable private fun HomeScreen(p:AppSettings,cache:CacheSnapshot,selection:Selection,vm:MealViewModel,onSchool:()->Unit,onKey:()->Unit,onDetail:(DetailTarget)->Unit) {
    val today=LocalDate.now(vm.graph.clock)
    var browsingDate by rememberSaveable {mutableStateOf<String?>(null)}
    var browsingType by rememberSaveable {mutableStateOf<String?>(null)}
    val automatic=mealFocus(selection,today,p.schedule.fixedType)
    val focus=if(browsingDate!=null) MealFocus(LocalDate.parse(browsingDate),MealType.valueOf(browsingType ?: automatic.type.name)) else automatic
    var showDate by remember {mutableStateOf(false)}
    fun browse(date:LocalDate,type:MealType) {browsingDate=date.toString();browsingType=type.name;vm.manual(date,type)}
    LaunchedEffect(focus.date,p.school) {vm.ensureRange(focus.date,focus.date.plusDays(1))}
    LaunchedEffect(p.schedule.mode,p.schedule.manualUntil,selection) {
        if(browsingDate!=null && p.schedule.returnAutomatically && p.schedule.manualUntil?.let {vm.graph.clock.millis()>=it}==true) {browsingDate=null;browsingType=null}
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth.value/(LocalDensity.current.fontScale*p.fontScale)>=840
        val school=p.school
        if(school==null) Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SectionTitle("학교를 선택해 주세요")
            Button(onClick=onSchool) {Text("학교 찾기")}
        } else {
            val status=cache.availability(school.id,focus.date,focus.type)
            val menu:@Composable ()->Unit={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    DateNavigation(focus.date,{browse(focus.date.minusDays(1),focus.type)},{browse(focus.date.plusDays(1),focus.type)},
                        extra=if(focus.date==today) "오늘 급식" else if(focus.date==today.plusDays(1)) "내일 급식" else null,
                        onDateClick={showDate=true},onToday=if(focus.date!=today) {{browse(today,focus.type)}} else null)
                    MealChoices(focus.type,{browse(focus.date,it)},onAutomatic=if(browsingDate!=null || p.schedule.mode==SelectionMode.MANUAL) {{browsingDate=null;browsingType=null;vm.automatic()}} else null)
                    if(focus.date.dayOfWeek.value in p.schedule.excludedDays || focus.type !in p.schedule.enabledTypes) Text("자동 선택에서 제외한 날짜 또는 식사입니다",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color=MaterialTheme.colorScheme.primary,thickness=2.dp)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${focus.type.label} 메뉴",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics {heading()})
                            Text(listOfNotNull("식사 ${eatingTime(p.schedule,focus.type)}",(status as? Availability.Present)?.meal?.calories?.takeIf {it.isNotBlank()}).joinToString("  ·  "),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(status is Availability.Present) IconButton(onClick={onDetail(DetailTarget(focus.date,focus.type,school.id))}) {Icon(Icons.AutoMirrored.Filled.ArrowForward,"급식 상세 열기")}
                    }
                    when(status) {
                        is Availability.Present->{
                            if(p.compact) Text(status.meal.dishes.joinToString(" · ") {it.name},style=MaterialTheme.typography.bodyLarge)
                            else MenuLines(status.meal,p.display.allergens,large=true)
                            val remaining=Duration.between(vm.graph.clock.instant(),MealTiming.eatingInstant(focus.date,focus.type,p.schedule))
                            if(!remaining.isNegative) Text("식사까지 ${remaining.toHours()}시간 ${remaining.toMinutes()%60}분",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            else if(!p.schedule.hidePassedCountdown) Text("식사 시간이 지났습니다",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick={onDetail(DetailTarget(focus.date,focus.type,school.id))},contentPadding=PaddingValues(0.dp)) {Text("급식 상세 보기")}
                        }
                        is Availability.Absent->Notice("등록된 ${focus.type.label}이 없습니다. 다른 날짜나 식사를 선택해 주세요.")
                        Availability.Unknown->Notice("아직 내려받지 않은 급식입니다. 연결 상태를 확인하고 다시 시도해 주세요.","새로고침") {vm.refreshDate(focus.date)}
                        is Availability.Failed->Notice(status.message,"다시 시도") {vm.refreshDate(focus.date)}
                    }
                    if(browsingDate==null && (selection is Selection.Warning || selection is Selection.Empty)) Notice(if(selection is Selection.Warning) selection.message else (selection as Selection.Empty).message)
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    Text(cache.latestError(school.id) ?: (if(cache.stale(school.id,vm.graph.clock.instant(),focus.date)) "갱신 전 급식입니다. 마지막 동기화 " else "최근 동기화 ")+syncLabel(cache.lastSync(school.id)),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.align(Alignment.TopCenter).widthIn(max=if(wide) MealLayout.expandedWidth else MealLayout.readingWidth).fillMaxSize().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {menu()}
                if(wide) Column(Modifier.width(260.dp).verticalScroll(rememberScrollState()).padding(top=28.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    GroupLabel("같은 날의 다른 식사",shortDate(focus.date))
                    MealType.entries.filter {it!=focus.type}.forEach {type->
                        val other=cache.availability(school.id,focus.date,type)
                        Surface(onClick={browse(focus.date,type)},color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("${type.label}  ${eatingTime(p.schedule,type)}",style=MaterialTheme.typography.titleMedium)
                                Text(if(other is Availability.Present) other.meal.dishes.take(3).joinToString(" · ") {it.name}+(if(other.meal.dishes.size>3) " 외" else "") else stateText(other),style=MaterialTheme.typography.bodyMedium)
                                Text("${type.label} 보기",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
    if(showDate) DatePickerOverlay(focus.date,{showDate=false}) {browse(it,focus.type)}
}

@Composable private fun WeeklyScreen(p:AppSettings,cache:CacheSnapshot,vm:MealViewModel,initialDate:String?,onDetail:(DetailTarget)->Unit) {
    val today=LocalDate.now(vm.graph.clock)
    var anchorText by rememberSaveable {mutableStateOf(initialDate ?: today.toString())}
    var rolling by rememberSaveable {mutableStateOf(false)}
    var showExcluded by rememberSaveable {mutableStateOf(false)}
    var rangeMenu by remember {mutableStateOf(false)}
    var filterNames by rememberSaveable {mutableStateOf(MealType.entries.map {it.name})}
    var selectedDay by rememberSaveable {mutableStateOf(initialDate ?: today.toString())}
    val filter=filterNames.map(MealType::valueOf).toSet()
    val anchor=LocalDate.parse(anchorText)
    val dates=if(rolling) eligibleDates(anchor,p.schedule,5) else weekDates(anchor,p.schedule.excludedDays,showExcluded)
    val selected=LocalDate.parse(selectedDay).takeIf {it in dates} ?: dates.firstOrNull() ?: anchor
    LaunchedEffect(dates,p.school) {if(dates.isNotEmpty()) vm.ensureRange(dates.first(),dates.last(),p.school)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val effectiveWidth=maxWidth.value/(LocalDensity.current.fontScale*p.fontScale)
        val columns=if(effectiveWidth>=1000) 3 else if(effectiveWidth>=680) 2 else 1
        val scrollHeader=weeklyHeaderScrolls(maxHeight.value,LocalDensity.current.fontScale*p.fontScale)
        Column(Modifier.fillMaxSize().then(if(scrollHeader) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal=20.dp)) {
            // Controls remain in place while the meal list scrolls.
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={anchorText=anchor.minusDays(7).toString();selectedDay=selected.minusDays(7).toString()}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"이전 주")}
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                    TextButton(onClick={rangeMenu=true}) {
                        Text((dates.takeIf {it.isNotEmpty()}?.let {"${it.first().monthValue}/${it.first().dayOfMonth} – ${it.last().monthValue}/${it.last().dayOfMonth}"} ?: dateLabel(anchor))+" ▾",style=MaterialTheme.typography.titleMedium)

                    }
                    DropdownMenu(expanded=rangeMenu,onDismissRequest={rangeMenu=false}) {
                        DropdownMenuItem(text={Text("한 주 보기")},onClick={rolling=false;rangeMenu=false})
                        DropdownMenuItem(text={Text("등교일 5일")},onClick={rolling=true;rangeMenu=false})
                        DropdownMenuItem(text={Text(if(showExcluded) "제외 요일 숨기기" else "제외 요일도 보기")},onClick={showExcluded=!showExcluded;rolling=false;rangeMenu=false})
                        DropdownMenuItem(text={Text("이번 주로")},onClick={anchorText=today.toString();selectedDay=today.toString();rangeMenu=false})
                    }
                }
                IconButton(onClick={anchorText=anchor.plusDays(7).toString();selectedDay=selected.plusDays(7).toString()}) {Icon(Icons.AutoMirrored.Filled.ArrowForward,"다음 주")}
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                dates.forEach {date->
                    val chosen=date==selected
                    Surface(onClick={selectedDay=date.toString()},color=if(chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape=RoundedCornerShape(12.dp),modifier=Modifier.widthIn(min=56.dp).semantics {this.selected=chosen;contentDescription="${dateLabel(date)}${if(date==today) ", 오늘" else ""} 급식 보기"}) {
                        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT,Locale.KOREAN),style=MaterialTheme.typography.labelLarge)
                            Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.titleLarge)
                            Row(Modifier.heightIn(min=20.dp),horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.CenterVertically) {
                                if(chosen) Icon(Icons.Outlined.Check,null,Modifier.size(14.dp))
                                if(date==today) Text("오늘",style=MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MealType.entries.forEach {type->FilterChip(type in filter,onClick={filterNames=if(type in filter) filterNames-type.name else filterNames+type.name},label={Text(type.label)},modifier=Modifier.heightIn(min=48.dp))}
            }
            if(columns==1) Text("날짜를 선택하면 해당 급식을 표시합니다",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp))
            HorizontalDivider()
            if(dates.isEmpty()) Notice("제외한 요일을 확인해 주세요. 표시할 등교일이 없습니다.")
            if(filter.isEmpty()) Notice("표시할 식사를 하나 이상 선택해 주세요.")
            if(p.school==null) Notice("설정에서 학교를 선택해 주세요.")
            p.school?.let {school->
                val visible=comparisonDates(dates,selected,columns)
                Row(Modifier.then(if(scrollHeader) Modifier else Modifier.weight(1f)).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    visible.forEach {date->key(date) {
                        Column(Modifier.weight(1f).then(if(scrollHeader) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            Text(dateLabel(date)+(if(date==today) " (오늘)" else ""),style=MaterialTheme.typography.titleMedium,color=if(date==selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,modifier=Modifier.semantics {heading()})
                            if(date.dayOfWeek.value in p.schedule.excludedDays) Text("자동 선택에서 제외한 요일",style=MaterialTheme.typography.bodyMedium)
                            MealType.entries.filter {it in filter}.forEach {type->
                                val status=cache.availability(school.id,date,type)
                                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable {onDetail(DetailTarget(date,type,school.id))},verticalAlignment=Alignment.CenterVertically) {
                                        Text(type.label,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward,"${shortDate(date)} ${type.label} 상세",Modifier.size(20.dp))
                                    }
                                    if(status is Availability.Present) {
                                        if(p.compact) Text(status.meal.dishes.joinToString(" · ") {it.name},style=MaterialTheme.typography.bodyMedium)
                                        else MenuLines(status.meal,p.display.allergens)
                                        if(status.meal.calories.isNotBlank()) Text(status.meal.calories,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else Text(stateText(status),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    HorizontalDivider(Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }}
                }
            }
        }
    }
}
