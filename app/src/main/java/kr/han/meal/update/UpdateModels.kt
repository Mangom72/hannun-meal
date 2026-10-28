package kr.han.meal.update

import kotlinx.serialization.Serializable
import kr.han.meal.BuildConfig
import java.io.File
import java.security.MessageDigest

@Serializable data class UpdateConfig(
    val repository: String = BuildConfig.UPDATE_REPOSITORY,
    val automatic: Boolean = true,
    val autoDownload: Boolean = true,
    val wifiOnly: Boolean = true
)
@Serializable data class UpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkAssetName: String,
    val size: Long,
    val sha256: String,
    val certificateSha256: String,
    val notes: String = ""
)
@Serializable data class UpdateCandidate(val repository: String, val tag: String, val assetId: Long, val manifest: UpdateManifest)
@Serializable data class UpdateSnapshot(val checkedAt: Long = 0, val candidate: UpdateCandidate? = null, val readyVersion: Long? = null, val error: String? = null)
data class UpdateSettings(val config: UpdateConfig = UpdateConfig())
enum class UpdateOperation { IDLE, CHECKING, DOWNLOADING }
data class UpdateState(val settings: UpdateSettings = UpdateSettings(), val snapshot: UpdateSnapshot = UpdateSnapshot(), val operation: UpdateOperation = UpdateOperation.IDLE, val progress: Int = 0)
enum class UpdateError(val label: String, val transient: Boolean = false) {
    CONFIG("공식 업데이트 연결을 확인할 수 없습니다"),
    AUTH("업데이트 서버에 접근할 수 없습니다. 잠시 후 다시 시도해 주세요"),
    NOT_FOUND("아직 공개된 업데이트가 없습니다"),
    RATE_LIMIT("GitHub 요청 한도에 도달했습니다. 나중에 다시 확인해 주세요"),
    NETWORK("업데이트 서버에 연결할 수 없습니다. 저장된 급식은 계속 사용할 수 있습니다",true),
    SERVER("GitHub 서버 오류입니다. 잠시 후 다시 시도해 주세요",true),
    INVALID("업데이트 파일이나 메타데이터가 올바르지 않습니다"),
    INCOMPATIBLE("이 Android 버전에서 설치할 수 없는 업데이트입니다"),
    SIGNATURE("설치된 앱과 업데이트의 서명이 다릅니다. 정식 서명 APK로 최초 설치했는지 확인해 주세요"),
    STORAGE("업데이트 파일을 저장하지 못했습니다. 저장 공간을 확인해 주세요")
}
class UpdateException(val reason: UpdateError): Exception(reason.label)
object UpdateRules {
    const val APK_NAME = "hannun-meal.apk"
    const val MAX_APK_BYTES = 80L * 1024 * 1024
    fun validRepository(value: String): Boolean {
        val parts=value.split('/')
        return parts.size==2 && Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(parts[0]) &&
            Regex("[A-Za-z0-9_.-]{1,100}").matches(parts[1]) && parts[1] !in setOf(".","..")
    }
    fun validate(m: UpdateManifest, sdk: Int, currentSigner: String) {
        if(m.schemaVersion!=1 || m.applicationId!=BuildConfig.APPLICATION_ID || m.versionCode !in 1..Int.MAX_VALUE.toLong() || m.versionName.isBlank() || m.versionName.length>80 || m.minSdk<26 || m.apkAssetName!=APK_NAME || m.size !in 1..MAX_APK_BYTES || m.notes.length>8000 || !Regex("[a-fA-F0-9]{64}").matches(m.sha256) || !Regex("[a-fA-F0-9]{64}").matches(m.certificateSha256)) throw UpdateException(UpdateError.INVALID)
        if(m.minSdk>sdk) throw UpdateException(UpdateError.INCOMPATIBLE)
        if(!m.certificateSha256.equals(currentSigner,true)) throw UpdateException(UpdateError.SIGNATURE)
    }
    fun verifyHash(file: File, manifest: UpdateManifest) {
        if(file.length()!=manifest.size) throw UpdateException(UpdateError.INVALID)
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)} }
        if(!MessageDigest.isEqual(digest.digest(),manifest.sha256.chunked(2).map {it.toInt(16).toByte()}.toByteArray())) throw UpdateException(UpdateError.INVALID)
    }
}
fun sha256(bytes: ByteArray): String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
