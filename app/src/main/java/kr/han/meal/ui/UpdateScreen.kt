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
        SectionTitle("설치된 버전 ${BuildConfig.VERSION_NAME}", "빌드 ${BuildConfig.VERSION_CODE}")
        Text("GitHub에서 업데이트를 받습니다. 다운로드한 파일을 검증한 뒤 설치할 수 있습니다.",style=MaterialTheme.typography.bodyMedium)
        Text(if(state.snapshot.checkedAt>0) "마지막 확인 ${syncLabel(state.snapshot.checkedAt)}" else "아직 업데이트를 확인하지 않았습니다",style=MaterialTheme.typography.bodyMedium)
        if(active) {
            Text(if(state.operation==UpdateOperation.DOWNLOADING) "다운로드 ${state.progress}%" else "새 버전 확인 중")
            if(state.operation==UpdateOperation.DOWNLOADING) LinearProgressIndicator(progress={state.progress/100f},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.snapshot.error?.let {Notice(it)}
        val candidate=state.snapshot.candidate
        if(candidate!=null) {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("${candidate.manifest.versionName} 업데이트",style=MaterialTheme.typography.titleLarge)
                    Text("${"%.1f".format(candidate.manifest.size/1048576.0)} MB")
                    if(candidate.manifest.notes.isNotBlank()) Text(candidate.manifest.notes,style=MaterialTheme.typography.bodyMedium)
                    if(state.snapshot.readyVersion==candidate.manifest.versionCode) {
                        Text("파일 검증이 끝났습니다. 설치할 수 있습니다")
                        Button(onClick={vm.installUpdate {intent->if(intent.action==Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) permission.launch(intent) else startInstaller(intent)}},enabled=!active) {Text("업데이트 설치")}
                    } else Button(onClick=vm::downloadUpdate,enabled=!active) {Text("다운로드")}
                }
            }
        } else if(state.snapshot.checkedAt>0 && state.snapshot.error==null) Notice("설치된 버전이 최신입니다")
        OutlinedButton(onClick=vm::checkUpdates,enabled=!active) {Text("업데이트 확인")}
        HorizontalDivider()
        GroupLabel("자동 확인과 다운로드")
        ToggleRow("12시간마다 자동 확인",settings.config.automatic,{value->vm.configureUpdates(settings.config.copy(automatic=value))})
        ToggleRow("새 버전 자동 다운로드",settings.config.autoDownload,{value->vm.configureUpdates(settings.config.copy(autoDownload=value))})
        ToggleRow("자동 확인과 다운로드는 Wi-Fi에서만",settings.config.wifiOnly,{value->vm.configureUpdates(settings.config.copy(wifiOnly=value))},"데이터 사용량에 따라 요금이 부과되는 연결은 제외합니다. 배터리가 부족하면 자동 작업을 미룹니다. 직접 다운로드하면 현재 연결을 사용합니다.")
        Text("절전 설정에 따라 자동 확인이 늦어질 수 있습니다. 다운로드가 끝나면 앱에서 설치를 확인해 주세요.")
    }
}
