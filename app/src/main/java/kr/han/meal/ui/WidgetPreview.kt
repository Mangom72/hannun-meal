package kr.han.meal.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.han.meal.domain.model.*

internal fun previewConfig(p:AppSettings,kind:WidgetKind)=WidgetConfig(
    id=-1-kind.ordinal,kind=kind,school=p.school,schedule=p.schedule,display=p.display,preset=p.preset,
    liveCountdown=p.liveCountdown,opacity=p.opacity,fontScale=p.fontScale,compact=p.compact,color=p.color)

/** The production RemoteViews, at actual dp size. No network jobs and no active widget actions. */
@Composable internal fun WidgetPreview(vm:MealViewModel,config:WidgetConfig,height:Int=240,width:Int=340) {
    val p by vm.preferences.collectAsStateWithLifecycle()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val configuration=LocalConfiguration.current
    val context=LocalContext.current
    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max=width.dp).fillMaxWidth()) {
            val measuredWidth=maxWidth.value.toInt().coerceAtLeast(100)
            val remote=remember(config,p,cache,measuredWidth,height,configuration) {vm.graph.renderer.render(config,config.school ?: p.school,p,cache,measuredWidth,height,requestData=false)}
            AndroidView(factory={ctx->object:FrameLayout(ctx) {
                override fun onInterceptTouchEvent(event:MotionEvent?)=true
            }.apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}},update={host->
                host.removeAllViews()
                val rendered=remote.apply(context,host)
                fun disableActions(view:View) {
                    view.setOnClickListener(null);view.isClickable=false;view.isFocusable=false
                    view.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    if(view is ViewGroup) for(index in 0 until view.childCount) disableActions(view.getChildAt(index))
                }
                disableActions(rendered)
                rendered.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                host.addView(rendered,FrameLayout.LayoutParams(-1,-1))
            },modifier=Modifier.fillMaxWidth().height(height.dp).semantics {contentDescription="${config.kind.label} 미리보기. 저장된 급식 기준입니다."})
        }
    }
}
