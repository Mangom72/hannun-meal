package kr.han.meal.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kr.han.meal.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable internal fun WidgetConfigPreview(vm:MealViewModel,draft:WidgetConfig,wide:Boolean) {
    Column(Modifier.fillMaxWidth().then(if(wide) Modifier.fillMaxHeight().verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement=if(wide) Arrangement.Center else Arrangement.Top) {
        Text("미리보기",style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh,MaterialTheme.colorScheme.surfaceContainerLow)),
            RoundedCornerShape(28.dp)).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(28.dp)).padding(16.dp)) {
            WidgetPreview(vm,draft,height=if(draft.kind==WidgetKind.NEXT) 220 else 330)
        }
        Spacer(Modifier.height(12.dp))
        Text("${draft.kind.label} · 저장된 급식 기준",style=MaterialTheme.typography.bodyMedium)
        Text("실제 배경과 크기는 홈 화면에서 확인해 주세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal data class WidgetSwatch(val name:String,val argb:Int,val legacy:Int?=null)
internal val widgetSwatches=listOf(
    WidgetSwatch("숲",0xFFF0F7F1.toInt(),0),
    WidgetSwatch("라벤더",0xFFF2F0FF.toInt(),1),
    WidgetSwatch("살구",0xFFFFF4E3.toInt(),2),
    WidgetSwatch("밤바다",0xFF193445.toInt()),
    WidgetSwatch("먹색",0xFF252A30.toInt()),
    WidgetSwatch("크림",0xFFFFF7E7.toInt())
)
internal fun WidgetConfig.withSwatch(swatch:WidgetSwatch)=copy(
    color=swatch.legacy ?: color,customBackgroundColor=if(swatch.legacy==null) swatch.argb else null)
internal fun WidgetConfig.isSwatchSelected(swatch:WidgetSwatch)=
    if(swatch.legacy!=null) customBackgroundColor==null && color==swatch.legacy else customBackgroundColor==swatch.argb
internal fun unusedWidgetDraftImages(importedIds:Collection<String>,selectedId:String?):List<String> =
    importedIds.distinct().filter {it!=selectedId}
internal fun widgetImagePickerIntent()=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE);type="image/*";putExtra(Intent.EXTRA_LOCAL_ONLY,true)
}

@Composable internal fun WidgetBackgroundControls(
    vm:MealViewModel,draft:WidgetConfig,saving:Boolean,onImported:(String)->Unit,onBusyChanged:(Boolean)->Unit,onChange:(WidgetConfig)->Unit
) {
    var importing by remember {mutableStateOf(false)}
    var imageError by remember {mutableStateOf<String?>(null)}
    var missingImage by remember {mutableStateOf(false)}
    var customDialog by rememberSaveable {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val currentDraft by rememberUpdatedState(draft)
    val currentChange by rememberUpdatedState(onChange)
    val currentImported by rememberUpdatedState(onImported)
    val currentBusyChanged by rememberUpdatedState(onBusyChanged)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        val uri=if(result.resultCode==android.app.Activity.RESULT_OK) result.data?.data else null
        if(uri!=null) scope.launch {
            importing=true
            currentBusyChanged(true)
            imageError=null
            var created:String?=null
            var applied=false
            try {
                val id=vm.graph.widgetBackgrounds.import(uri)
                created=id
                currentImported(id)
                currentChange(currentDraft.copy(backgroundImageId=id,backgroundMode=WidgetBackgroundMode.IMAGE))
                applied=true
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {imageError="이미지를 가져오지 못했습니다. 다른 이미지를 선택해 주세요."}
            finally {
                if(!applied) created?.let {id->withContext(NonCancellable+Dispatchers.IO) {vm.graph.widgetBackgrounds.discard(listOf(id))}}
                importing=false
                currentBusyChanged(false)
            }
        }
    }
    LaunchedEffect(draft.backgroundImageId) {
        missingImage=draft.backgroundImageId!=null && withContext(Dispatchers.IO) {vm.graph.widgetBackgrounds.load(draft.backgroundImageId)==null}
    }
    val enabled=!saving && !importing
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("위젯 배경",style=MaterialTheme.typography.titleMedium)
            Text(if(draft.backgroundEnabled) "이미지나 색상으로 꾸미기" else "배경 없이 메뉴만 표시",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(draft.backgroundEnabled,{onChange(draft.copy(backgroundEnabled=it))},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="위젯 배경 사용"})
    }
    Column(Modifier.selectableGroup()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf(WidgetBackgroundMode.COLOR to "색상",WidgetBackgroundMode.IMAGE to "이미지").forEach {(mode,label)->
                Row(Modifier.weight(1f).heightIn(min=56.dp).selectable(draft.backgroundMode==mode,enabled=enabled && draft.backgroundEnabled,role=Role.RadioButton,onClick={onChange(draft.copy(backgroundMode=mode))}),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(draft.backgroundMode==mode,onClick=null,enabled=enabled && draft.backgroundEnabled)
                    Spacer(Modifier.width(8.dp));Text(label)
                }
            }
        }
    }
    if(draft.backgroundMode==WidgetBackgroundMode.COLOR) {
        FlowRow(Modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            widgetSwatches.forEach {swatch->
                val selected=draft.isSwatchSelected(swatch)
                Column(Modifier.widthIn(min=72.dp).selectable(selected,enabled=enabled && draft.backgroundEnabled,role=Role.RadioButton,onClick={onChange(draft.withSwatch(swatch))}).padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.size(38.dp).border(if(selected) 3.dp else 1.dp,if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,CircleShape).padding(5.dp).background(Color(swatch.argb),CircleShape))
                    Spacer(Modifier.height(4.dp));Text(swatch.name,style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        OutlinedButton(onClick={customDialog=true},enabled=enabled && draft.backgroundEnabled,modifier=Modifier.heightIn(min=48.dp)) {
            Text(if(draft.customBackgroundColor!=null) "직접 선택 · #${"%06X".format(draft.customBackgroundColor and 0xFFFFFF)}" else "색상 직접 선택")
        }
    } else {
        Text(when {importing->"이미지를 준비하고 있습니다…";missingImage->"저장된 이미지를 열 수 없습니다. 다시 선택해 주세요.";draft.backgroundImageId!=null->if(draft.backgroundEnabled) "선택한 이미지가 배경에 적용됩니다." else "배경을 켜면 선택한 이미지를 표시합니다.";else->"배경으로 사용할 이미지를 선택해 주세요."},style=MaterialTheme.typography.bodyMedium)
        if(importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={
                picker.launch(widgetImagePickerIntent())
            },enabled=enabled && draft.backgroundEnabled,modifier=Modifier.heightIn(min=48.dp)) {Text(if(draft.backgroundImageId==null) "이미지 선택" else "이미지 바꾸기")}
            if(draft.backgroundImageId!=null) TextButton(onClick={onChange(draft.copy(backgroundImageId=null));imageError=null},enabled=enabled && draft.backgroundEnabled,modifier=Modifier.heightIn(min=48.dp)) {Text("이미지 제거")}
        }
        Text("이미지는 기기 안에 복사하며 업로드하거나 원본을 변경하지 않습니다. 메뉴가 잘 보이도록 배경 이미지는 어둡게 표시합니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        imageError?.let {Notice(it)}
    }
    Text("배경 불투명도 ${draft.opacity}%",style=MaterialTheme.typography.bodyMedium)
    Slider(draft.opacity.toFloat(),{onChange(draft.copy(opacity=it.roundToInt()))},valueRange=0f..100f,enabled=enabled && draft.backgroundEnabled,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="배경 불투명도"})
    Text(if(draft.backgroundEnabled) "투명한 배경에서는 홈 화면에 따라 글자가 잘 보이지 않을 수 있습니다." else "배경을 다시 켜면 선택한 이미지·색상·불투명도를 그대로 사용합니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(customDialog) WidgetColorDialog(draft.customBackgroundColor ?: widgetSwatches.firstOrNull {it.legacy==draft.color}?.argb ?: widgetSwatches.first().argb,onDismiss={customDialog=false}) {
        if(!saving) {onChange(draft.copy(customBackgroundColor=it));customDialog=false}
    }
}

internal fun parseWidgetColor(hex:String):Int?=hex.trim().removePrefix("#").takeIf {it.length==6 && it.all {c->c in '0'..'9' || c.lowercaseChar() in 'a'..'f'}}?.toLongOrNull(16)?.let {(it or 0xFF000000).toInt()}

@Composable private fun WidgetColorDialog(initial:Int,onDismiss:()->Unit,onConfirm:(Int)->Unit) {
    var hex by rememberSaveable {mutableStateOf("%06X".format(initial and 0xFFFFFF))}
    val parsed=parseWidgetColor(hex)
    var lastValid by rememberSaveable {mutableIntStateOf(initial)}
    LaunchedEffect(parsed) {if(parsed!=null) lastValid=parsed}
    AlertDialog(onDismissRequest=onDismiss,title={Text("배경 색상 직접 선택")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp).background(Color(lastValid),RoundedCornerShape(12.dp)).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(12.dp)))
            OutlinedTextField(hex,{hex=it.take(7)},singleLine=true,label={Text("색상 코드")},supportingText={Text("RRGGBB 형식의 6자리 값")},isError=parsed==null,modifier=Modifier.fillMaxWidth())
            listOf("빨강" to 16,"초록" to 8,"파랑" to 0).forEach {(label,shift)->
                val value=(lastValid shr shift) and 255
                Text("$label $value",style=MaterialTheme.typography.labelLarge)
                Slider(value.toFloat(),{v->
                    val color=(lastValid and (255 shl shift).inv()) or (v.roundToInt() shl shift)
                    lastValid=color;hex="%06X".format(color and 0xFFFFFF)
                },valueRange=0f..255f,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="$label 값"})
            }
        }
    },confirmButton={TextButton(onClick={parsed?.let(onConfirm)},enabled=parsed!=null){Text("적용")}},dismissButton={TextButton(onClick=onDismiss){Text("취소")}})
}
