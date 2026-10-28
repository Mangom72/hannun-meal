package kr.han.meal.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.han.meal.BuildConfig
import kr.han.meal.update.*

@Composable fun UpdateScreen(vm:MealViewModel) {
    val context=LocalContext.current
    val state by vm.graph.updateState.collectAsStateWithLifecycle()
    val settings=state.settings
    val active=state.operation!=UpdateOperation.IDLE
    fun startInstaller(intent:Intent) {
        try {context.startActivity(intent)} catch(e:Exception) {vm.message.value="시스템 설치 화면을 열 수 없습니다"}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls()) vm.installUpdate(::startInstaller)
        else vm.message.value="이 앱의 설치 허용을 켜야 업데이트를 설치할 수 있습니다"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionTitle("한눈급식을 최신으로", "현재 ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.VERSION_CODE}")
        Text("공식 공개 릴리스에서 업데이트를 확인하고 다운로드합니다. 파일 검증을 마치면 Android 설치 화면으로 연결합니다.",style=MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        ToggleRow("12시간마다 자동 확인",settings.config.automatic,{value->vm.configureUpdates(settings.config.copy(automatic=value))})
        ToggleRow("새 버전 자동 다운로드",settings.config.autoDownload,{value->vm.configureUpdates(settings.config.copy(autoDownload=value))})
        ToggleRow("자동 작업은 Wi-Fi에서만",settings.config.wifiOnly,{value->vm.configureUpdates(settings.config.copy(wifiOnly=value))},"종량제 연결을 제외하며 배터리 부족 시 자동 작업을 미룹니다. 수동 다운로드는 현재 연결을 사용합니다.")
        Text("마지막 확인 ${syncLabel(state.snapshot.checkedAt.takeIf {it>0})}",style=MaterialTheme.typography.bodySmall)
        if(active) {
            Text(if(state.operation==UpdateOperation.DOWNLOADING) "다운로드 ${state.progress}% · 완료 후 서명 검증" else "새 버전 확인 중")
            if(state.operation==UpdateOperation.DOWNLOADING) LinearProgressIndicator(progress={state.progress/100f},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.snapshot.error?.let {Notice(it)}
        val candidate=state.snapshot.candidate
        if(candidate!=null) {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("${candidate.manifest.versionName} 업데이트",style=MaterialTheme.typography.titleLarge)
                    Text("${"%.1f".format(candidate.manifest.size/1048576.0)} MB · APK 해시와 기존 서명 확인")
                    if(candidate.manifest.notes.isNotBlank()) Text(candidate.manifest.notes,style=MaterialTheme.typography.bodyMedium)
                    if(state.snapshot.readyVersion==candidate.manifest.versionCode) {
                        Text("검증된 파일이 준비되었습니다")
                        Button(onClick={vm.installUpdate {intent->if(intent.action==Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) permission.launch(intent) else startInstaller(intent)}},enabled=!active) {Text("업데이트 설치")}
                    } else Button(onClick=vm::downloadUpdate,enabled=!active) {Text("지금 다운로드")}
                }
            }
        } else if(state.snapshot.checkedAt>0 && state.snapshot.error==null) Notice("설치된 버전이 최신입니다")
        OutlinedButton(onClick=vm::checkUpdates,enabled=!active) {Text("지금 업데이트 확인")}
        Notice("절전 설정에 따라 자동 확인이 늦어질 수 있습니다. 다운로드가 끝나면 앱에서 설치를 확인해 주세요.")
    }
}
