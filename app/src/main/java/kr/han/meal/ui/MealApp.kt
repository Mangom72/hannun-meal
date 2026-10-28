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
    BackHandler(enabled=detail==null && page in listOf("school","schedule","appearance","data","updates")) {page="settings"}
    MealTheme(p) {
        Scaffold(
            snackbarHost={ SnackbarHost(snack) },
            topBar={ TopAppBar(title={ if(page in listOf("home","weekly") && (if(page=="weekly") weeklyPreferences.school else p.school)!=null) SchoolToolbarTitle((if(page=="weekly") weeklyPreferences.school else p.school)!!,p.fontScale,caption=if(page=="weekly") "주간 급식" else "한눈급식") {page="school"} else Text(when(page) { "home"->"한눈급식";"weekly"->"주간 급식";"widgets"->"내 위젯";"school"->"학교 선택";"schedule"->"자동 전환";"appearance"->"화면 꾸미기";"data"->"데이터와 개인정보";"updates"->"앱 업데이트";else->"설정" },fontWeight=FontWeight.Bold) },
                navigationIcon={ if(page in listOf("home","weekly")) IconButton(onClick={page="school"}) {Icon(AppIcons.School,"학교 변경",tint=MaterialTheme.colorScheme.primary)} else if(page in listOf("school","schedule","appearance","data","updates")) IconButton(onClick={ page="settings" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"뒤로") } },
                actions={ if(page in listOf("home","weekly")) IconButton(onClick=vm::refresh,enabled=!busy) { Icon(Icons.Outlined.Refresh,"급식 새로고침") } }) },
            bottomBar={ NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf(Triple("home","오늘",Icons.Outlined.Home),Triple("weekly","주간",Icons.Outlined.DateRange),Triple("widgets","위젯",AppIcons.Widgets),Triple("settings","설정",Icons.Outlined.Settings)).forEach { (key,label,icon) ->
                    NavigationBarItem(selected=page==key || (key=="settings" && page in listOf("school","schedule","appearance","data","updates")),onClick={page=key},icon={Icon(icon,null)},label={Text(label)})
                }
            } }
        ) { insets ->
            Column(Modifier.padding(insets).fillMaxSize()) {
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(!online) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {Text("오프라인 · 저장된 급식을 표시합니다",Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)}
                if(updateState.snapshot.candidate!=null && page!="updates") Surface(color=MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("새 버전 ${updateState.snapshot.candidate!!.manifest.versionName}",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                        TextButton(onClick={page="updates"}) {Text("업데이트")}
                    }
                }
                when(page) {
                    "home" -> HomeScreen(p,cache,selected,vm, {page="school"},{page="data"}) { detail=it }
                    "weekly" -> WeeklyScreen(weeklyPreferences,cache,vm,initialDate) { detail=it }
                    "school" -> SchoolScreen(vm) { page="home" }
                    "schedule" -> ScheduleScreen(p.schedule) { s -> vm.update { it.copy(schedule=s) };page="settings" }
                    "appearance" -> AppearanceScreen(p,vm::update)
                    "data" -> DataScreen(p,cache,vm)
                    "updates" -> UpdateScreen(vm)
                    "widgets" -> WidgetManagementScreen(vm)
                    else -> SettingsScreen(p) { page=it }
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
    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(text,style=MaterialTheme.typography.bodyMedium)
            action?.let { TextButton(onClick=onAction) { Text(it) } }
        }
    }
}
@Composable fun DateNavigation(date:LocalDate,onPrevious:()->Unit,onNext:()->Unit,extra:String?=null,onDateClick:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onPrevious) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"이전 날짜")}
        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
            if(onDateClick!=null) TextButton(onClick=onDateClick,modifier=Modifier.heightIn(min=48.dp)) {Text(dateLabel(date),style=MaterialTheme.typography.titleMedium)}
            else Text(dateLabel(date),style=MaterialTheme.typography.titleMedium)
            extra?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        IconButton(onClick=onNext) {Icon(Icons.AutoMirrored.Filled.ArrowForward,"다음 날짜")}
    }
}
fun stateText(a: Availability)=when(a) {
    is Availability.Present -> ""
    is Availability.Absent -> "등록된 급식이 없습니다"
    Availability.Unknown -> "아직 내려받지 않은 날짜입니다"
    is Availability.Failed -> a.message
}
@Composable fun MealCard(date: LocalDate,type: MealType,status: Availability,highlight:Boolean=false,compact:Boolean=false,excluded:Boolean=false,allergens:Boolean=false,onClick:()->Unit) {
    Card(onClick=onClick,colors=CardDefaults.cardColors(containerColor=if(highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(if(compact) 14.dp else 18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(type.label,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                if(highlight) Text("선택한 날짜",style=MaterialTheme.typography.labelSmall)
                if(excluded) Text("자동 선택 제외",style=MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f));Icon(Icons.AutoMirrored.Filled.ArrowForward,"상세 보기",Modifier.size(18.dp))
            }
            if(status is Availability.Present) {
                if(compact) Text(status.meal.dishes.joinToString(" · ") { it.name },style=MaterialTheme.typography.bodyMedium)
                else status.meal.dishes.forEach { dish -> Text(dish.name+if(allergens && dish.allergens.isNotEmpty()) "  [${dish.allergens.joinToString(",")}]" else "",style=MaterialTheme.typography.bodyLarge) }
                if(status.meal.dishes.isEmpty()) Text("메뉴 상세를 확인해 주세요")
                if(status.meal.calories.isNotBlank()) Text(status.meal.calories,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(stateText(status),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun HomeScreen(p:AppSettings,cache:CacheSnapshot,selection:Selection,vm:MealViewModel,onSchool:()->Unit,onKey:()->Unit,onDetail:(DetailTarget)->Unit) {
    val today=LocalDate.now(vm.graph.clock)
    // Explicit browsing is local UI state, so weekends/excluded meal types remain inspectable.
    // The saved scheduling engine still determines the initial and automatic focus.
    var browsingDate by rememberSaveable {mutableStateOf<String?>(null)}
    var browsingType by rememberSaveable {mutableStateOf<String?>(null)}
    val automatic=mealFocus(selection,today,p.schedule.fixedType)
    val focus=if(browsingDate!=null) MealFocus(LocalDate.parse(browsingDate),MealType.valueOf(browsingType ?: automatic.type.name)) else automatic
    var showDate by remember {mutableStateOf(false)}
    fun browse(date:LocalDate,type:MealType) {browsingDate=date.toString();browsingType=type.name;vm.manual(date,type)}
    LaunchedEffect(focus.date,p.school) {vm.ensureRange(focus.date,focus.date.plusDays(1))}
    // Existing automatic return clears a temporary local browse at the saved rule boundary.
    LaunchedEffect(p.schedule.mode,p.schedule.manualUntil,selection) {
        if(browsingDate!=null && p.schedule.returnAutomatically && p.schedule.manualUntil?.let {vm.graph.clock.millis()>=it}==true) {browsingDate=null;browsingType=null}
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(p.school==null) item {Notice("학교를 선택하면 급식과 위젯을 사용할 수 있습니다.","학교 찾기",onSchool)}
        else {
            item {DateNavigation(focus.date,{browse(focus.date.minusDays(1),focus.type)},{browse(focus.date.plusDays(1),focus.type)},extra=null,onDateClick={showDate=true})}
            item {MealChoices(focus.type,{browse(focus.date,it)},onAutomatic=if(browsingDate!=null || p.schedule.mode==SelectionMode.MANUAL) {{browsingDate=null;browsingType=null;vm.automatic()}} else null)}
            if(focus.date.dayOfWeek.value in p.schedule.excludedDays || focus.type !in p.schedule.enabledTypes) item {Text("자동 선택에서 제외한 날짜 또는 식사입니다",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            val school=p.school
            val status=cache.availability(school.id,focus.date,focus.type)
            item {
                Surface(color=MaterialTheme.colorScheme.surfaceContainerLowest,shape=RoundedCornerShape(24.dp),tonalElevation=1.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("${if(focus.date==today) "오늘 " else ""}${focus.type.label}",Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.headlineSmall)
                            Column(horizontalAlignment=Alignment.End) {
                                Text(eatingTime(p.schedule,focus.type).toString(),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                                if(status is Availability.Present && status.meal.calories.isNotBlank()) Text(status.meal.calories,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(status is Availability.Present) IconButton(onClick={onDetail(DetailTarget(focus.date,focus.type,school.id))}) {Icon(Icons.AutoMirrored.Filled.ArrowForward,"급식 상세 열기")}
                        }
                        when(status) {
                            is Availability.Present->{
                                if(p.compact) Text(status.meal.dishes.joinToString(" · ") {it.name},style=MaterialTheme.typography.bodyLarge)
                                else MenuLines(status.meal,p.display.allergens,large=true)
                                val remaining=Duration.between(vm.graph.clock.instant(),MealTiming.eatingInstant(focus.date,focus.type,p.schedule))
                                if(!remaining.isNegative) Text("식사까지 ${remaining.toHours()}시간 ${remaining.toMinutes()%60}분",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                else if(!p.schedule.hidePassedCountdown) Text("식사 시간이 지났습니다",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

                            }
                            is Availability.Absent->{Text("등록된 ${focus.type.label}이 없습니다",style=MaterialTheme.typography.titleMedium);Text("휴일이나 급식이 없는 날일 수 있습니다. 다른 날짜 또는 식사를 선택해 주세요.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            Availability.Unknown->{Text("아직 내려받지 않은 급식입니다",style=MaterialTheme.typography.titleMedium);Text("연결되면 자동으로 갱신합니다. 새로고침으로 다시 확인할 수 있습니다.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            is Availability.Failed->Notice(status.message,"다시 시도") {vm.refreshDate(focus.date)}
                        }
                    }
                }
            }
            if(browsingDate==null && (selection is Selection.Warning || selection is Selection.Empty)) item {Notice(if(selection is Selection.Warning) selection.message else (selection as Selection.Empty).message)}
            item {Text(cache.latestError(school.id) ?: (if(cache.stale(school.id,vm.graph.clock.instant(),focus.date)) "저장된 급식 · " else "최근 동기화 · ")+syncLabel(cache.lastSync(school.id)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(!vm.graph.configured(p)) item {Notice("급식 서버를 연결하면 최신 메뉴를 받을 수 있습니다.","데이터 연결 설정",onKey)}
    }
    if(showDate) DatePickerOverlay(focus.date,{showDate=false}) {browse(it,focus.type)}
}

@Composable private fun WeeklyScreen(p:AppSettings,cache:CacheSnapshot,vm:MealViewModel,initialDate:String?,onDetail:(DetailTarget)->Unit) {
    val today=LocalDate.now(vm.graph.clock)
    var anchorText by rememberSaveable {mutableStateOf(initialDate ?: today.toString())}
    var rolling by rememberSaveable {mutableStateOf(false)}
    var rangeMenu by remember {mutableStateOf(false)}
    var filterNames by rememberSaveable {mutableStateOf(MealType.entries.map {it.name})}
    var selectedDay by rememberSaveable {mutableStateOf(initialDate ?: today.toString())}
    val filter=filterNames.map(MealType::valueOf).toSet()
    val anchor=LocalDate.parse(anchorText)
    val monday=anchor.minusDays((anchor.dayOfWeek.value-1).toLong())
    val dates=if(rolling) eligibleDates(anchor,p.schedule,5) else (0L..6L).map {monday.plusDays(it)}.filter {it.dayOfWeek.value !in p.schedule.excludedDays}
    val list=rememberLazyListState();val scope=rememberCoroutineScope()
    LaunchedEffect(anchorText,rolling) {if(dates.none {it.toString()==selectedDay}) selectedDay=dates.firstOrNull()?.toString() ?: anchorText}
    LaunchedEffect(anchorText,rolling,p.school,p.schedule.excludedDays) {if(dates.isNotEmpty()) vm.ensureRange(dates.first(),dates.last(),p.school)}
    LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={anchorText=anchor.minusDays(7).toString()}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"이전 주")}
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                    TextButton(onClick={rangeMenu=true},modifier=Modifier.heightIn(min=48.dp)) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(dates.takeIf {it.isNotEmpty()}?.let {"${it.first().monthValue}/${it.first().dayOfMonth} – ${it.last().monthValue}/${it.last().dayOfMonth}"} ?: dateLabel(anchor),style=MaterialTheme.typography.titleMedium)
                            Text(if(rolling) "등교일 5일 ▾" else "한 주 보기 ▾",style=MaterialTheme.typography.labelMedium)
                        }
                    }
                    DropdownMenu(expanded=rangeMenu,onDismissRequest={rangeMenu=false}) {
                        DropdownMenuItem(text={Text("한 주 보기")},onClick={rolling=false;rangeMenu=false},leadingIcon={if(!rolling) Icon(Icons.Outlined.Check,null)})
                        DropdownMenuItem(text={Text("등교일 5일")},onClick={rolling=true;rangeMenu=false},leadingIcon={if(rolling) Icon(Icons.Outlined.Check,null)})
                        DropdownMenuItem(text={Text("이번 주로")},onClick={anchorText=today.toString();selectedDay=today.toString();rangeMenu=false})
                    }
                }
                IconButton(onClick={anchorText=anchor.plusDays(7).toString()}) {Icon(Icons.AutoMirrored.Filled.ArrowForward,"다음 주")}
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                dates.forEachIndexed {index,date->FilterChip(date.toString()==selectedDay,onClick={selectedDay=date.toString();scope.launch {list.animateScrollToItem(3+(if(filter.isEmpty()) 1 else 0)+index)}},label={Text(shortDate(date)+(if(date==today) " 오늘" else ""))},modifier=Modifier.heightIn(min=48.dp))}
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {MealType.entries.forEach {type->FilterChip(type in filter,onClick={filterNames=if(type in filter) filterNames-type.name else filterNames+type.name},label={Text(type.label)},modifier=Modifier.heightIn(min=48.dp))}}
        }
        if(dates.isEmpty()) item {Notice("제외한 요일을 확인해 주세요. 표시할 등교일이 없습니다.")}
        if(filter.isEmpty()) item {Notice("조식·중식·석식 중 표시할 식사를 선택해 주세요.")}
        if(p.school==null) item {Notice("설정에서 학교를 선택하면 한 주의 급식을 확인할 수 있습니다.")}
        p.school?.let {school->dates.forEach {date->
            item(key=date.toString()) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(dateLabel(date)+(if(date==today) " · 오늘" else ""),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.semantics {heading()})
                    MealType.entries.filter {it in filter}.forEach {type->MealCard(date,type,cache.availability(school.id,date,type),highlight=date.toString()==selectedDay,compact=p.compact,allergens=p.display.allergens) {onDetail(DetailTarget(date,type,school.id))}}
                }
            }
        }}
    }
}
