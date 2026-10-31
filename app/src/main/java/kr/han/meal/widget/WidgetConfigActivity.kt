package kr.han.meal.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kr.han.meal.core.graph
import kr.han.meal.domain.model.*
import kr.han.meal.ui.*
import kr.han.meal.worker.WorkScheduler

class WidgetConfigActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val info=AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID || info==null || info.provider.packageName!=packageName || widgetProviders.values.none {it.name==info.provider.className}) {finish();return}
        lifecycleScope.launch {
            val p=graph.settings.settings.first()
            val existing=graph.settings.widgets.first().firstOrNull {it.id==id}
            val kind=widgetProviders.entries.firstOrNull {it.value.name==info.provider.className}?.key ?: WidgetKind.NEXT
            val config=existing ?: WidgetConfig(id,kind,p.school,p.schedule,p.display,p.preset,liveCountdown=p.liveCountdown,opacity=p.opacity,fontScale=p.fontScale,compact=p.compact,color=p.color)
            var saving by mutableStateOf(false)
            var saveError by mutableStateOf<String?>(null)
            setContent {MealTheme(p) {WidgetConfigScreen(config,viewModel(),onCancel={if(!saving) finish()},saving=saving,saveError=saveError) {saved ->
                if(saving) return@WidgetConfigScreen
                saving=true;saveError=null
                lifecycleScope.launch {
                    try {graph.settings.saveWidget(saved)}
                    catch(e:CancellationException) {throw e}
                    catch(_:Exception) {saving=false;saveError="설정을 저장하지 못했습니다. 다시 시도해 주세요";return@launch}
                    // Persistence is the commit point. Rendering/cleanup failure must not report an unsaved configuration.
                    graph.scope.launch {
                        runCatching {graph.widgetBackgrounds.discard(listOfNotNull(existing?.backgroundImageId).filter {it!=saved.backgroundImageId})}
                        runCatching {graph.renderer.renderAll()}
                        runCatching {WorkScheduler.request(this@WidgetConfigActivity)}
                    }
                    setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()
                }
            } } }
        }
    }
}
