package kr.han.meal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.han.meal.core.graph
import kr.han.meal.data.remote.NeisException
import kr.han.meal.data.repository.*
import kr.han.meal.domain.model.*
import kr.han.meal.domain.selection.*
import kr.han.meal.domain.scheduling.MealTiming
import kr.han.meal.worker.WorkScheduler
import java.time.*
import kr.han.meal.update.*
import android.content.Intent

data class SearchState(val loading: Boolean=false,val results: List<School> = emptyList(),val error: String?=null,val searched: Boolean=false)
class MealViewModel(app: Application): AndroidViewModel(app) {
    val graph=app.graph
    val preferences=graph.settings.settings.stateIn(viewModelScope,SharingStarted.Eagerly,AppSettings())
    val cache=graph.cache
    val widgets=graph.settings.widgets.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val tick=flow { while(true) { emit(graph.clock.instant()); delay(30_000) } }
    val selection=combine(preferences,cache,tick) { p,c,_ -> p.school?.let { school -> MealSelector(graph.clock).select(p.schedule) { d,t -> c.availability(school.id,d,t) } } ?: Selection.Warning("학교를 선택해 주세요") }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),Selection.Warning("학교를 선택해 주세요"))
    val search=MutableStateFlow(SearchState())
    val busy=MutableStateFlow(false)
    val message=MutableStateFlow<String?>(null)
    private var searchJob: Job?=null
    fun update(transform:(AppSettings)->AppSettings) { viewModelScope.launch { graph.settings.update(transform) } }
    private var directoryJob:Job?=null
    private var lastQuery=""
    private var lastSchoolType:String?=null
    fun prepareSchools() {
        if(directoryJob?.isActive==true) return
        directoryJob=viewModelScope.launch {
            val endpoint=graph.directoryEndpoint(graph.settings.settings.first())
            try {
                graph.schools.load(endpoint)
                if(!graph.schools.ready) search.value=SearchState(loading=true)
                else search(lastQuery,lastSchoolType)
                graph.schools.refresh(endpoint)
                search(lastQuery,lastSchoolType)
            } catch(e:CancellationException) {throw e} catch(e:Exception) {
                search.value=SearchState(results=withContext(Dispatchers.Default) {graph.schools.search(lastQuery,lastSchoolType)},error=if(e is NeisException) e.message else "학교 목록을 준비하지 못했습니다. 데이터 연결 설정을 확인해 주세요",searched=lastQuery.isNotBlank())
            }
        }
    }
    fun search(query:String,schoolType:String?=null) {
        lastQuery=query;lastSchoolType=schoolType
        searchJob?.cancel()
        searchJob=viewModelScope.launch {
            delay(120)
            val results=withContext(Dispatchers.Default) {graph.schools.search(query,schoolType)}
            search.value=SearchState(loading=!graph.schools.ready && directoryJob?.isActive==true,results=results,error=if(!graph.schools.ready && directoryJob?.isActive!=true) "전국 학교 목록을 준비해 주세요. 데이터 연결 설정과 네트워크를 확인하세요" else null,searched=query.isNotBlank())
        }
    }
    fun choose(school: School) { viewModelScope.launch { graph.settings.update { it.copy(school=school) };syncSchool(school) } }
    fun refresh() { viewModelScope.launch { preferences.value.school?.let { syncSchool(it,true) } ?: run { message.value="먼저 학교를 선택해 주세요" } } }
    private suspend fun syncSchool(school: School, force: Boolean=false) {
        busy.value=cache.value.coverage.none {it.schoolId==school.id && it.successAt!=null}
        try {
            val p=graph.settings.settings.first(); val today=LocalDate.now(graph.clock)
            val r=graph.repository.sync(graph.mealEndpoint(p),school,today.minusDays(7),today.plusDays(21),force)
            if(r is SyncResult.Stale) WorkScheduler.request(graph.app)
            message.value=when(r) {is SyncResult.Failure->r.message;is SyncResult.Stale->r.message;else->"급식 정보를 동기화했습니다"}
            graph.renderer.renderAll()
        } finally { busy.value=false }
    }
    fun ensureRange(from: LocalDate,to: LocalDate, targetSchool: School?=null) {
        val school=targetSchool ?: preferences.value.school ?: return
        if(!graph.configured(preferences.value)) return
        val snapshot=cache.value
        if(graph.repository.needsRefresh(snapshot,graph.mealEndpoint(preferences.value),school,from,to)) WorkScheduler.request(graph.app,from=from,to=to)
    }
    fun manual(date: LocalDate,type: MealType) { update { p -> p.copy(schedule=p.schedule.copy(mode=SelectionMode.MANUAL,manualDate=date.toString(),manualType=type,manualUntil=MealTiming.nextRefresh(p.schedule,graph.clock).toEpochMilli())) } }
    fun automatic() { update { it.copy(schedule=it.schedule.copy(mode=SelectionMode.AUTO,manualDate=null,manualUntil=null)) } }
    fun refreshDate(date:LocalDate) {WorkScheduler.request(graph.app,force=true,from=date,to=date)}
    fun clearCache() { viewModelScope.launch { graph.repository.clear();graph.renderer.renderAll() } }
    fun resetPreferences() { viewModelScope.launch { graph.settings.reset() } }
    fun saveWidget(config: WidgetConfig) { viewModelScope.launch { graph.settings.saveWidget(config);graph.renderer.renderAll();WorkScheduler.request(graph.app) } }
    fun configureUpdates(config:UpdateConfig) {viewModelScope.launch {
        try {graph.updates.configure(config);message.value="업데이트 설정을 저장했습니다"}
        catch(e:Exception) {if(e is CancellationException) throw e;message.value=if(e is UpdateException) e.reason.label else "업데이트 설정을 저장하지 못했습니다"}
    }}
    fun checkUpdates() {viewModelScope.launch {graph.updates.check(downloadAutomatically=false)}}
    fun downloadUpdate() {viewModelScope.launch {graph.updates.download()}}
    fun installUpdate(launch:(Intent)->Unit) {viewModelScope.launch {
        try {launch(graph.updates.installerIntent(graph.app))}
        catch(e:Exception) {if(e is CancellationException) throw e;message.value=if(e is UpdateException) e.reason.label else "업데이트 설치 파일을 확인하지 못했습니다"}
    }}

}
