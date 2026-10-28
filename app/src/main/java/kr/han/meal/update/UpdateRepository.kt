package kr.han.meal.update

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.time.Clock

/** One serialized updater; no untrusted path, URL, certificate, or version reaches the installer unchecked. */
class UpdateRepository(
    private val store:UpdateStore,
    private val transport:GitHubTransport,
    private val directory:File,
    private val trust:ApkTrust,
    private val clock:Clock=Clock.systemUTC()
) {
    private val mutex=Mutex()
    private val operation=MutableStateFlow(UpdateOperation.IDLE)
    private val progress=MutableStateFlow(0)
    val state:Flow<UpdateState> = combine(store.settings,store.snapshot,operation,progress) {settings,snapshot,op,value->
        val visible=if(snapshot.candidate?.manifest?.versionCode?.let {it<=trust.currentVersion}==true) snapshot.copy(candidate=null,readyVersion=null) else snapshot
        val usable=if(visible.readyVersion!=null && visible.candidate!=null && !apk(visible.candidate).isFile) visible.copy(readyVersion=null) else visible
        UpdateState(settings,usable,op,value)
    }
    suspend fun configure(config:UpdateConfig)=mutex.withLock {store.configure(config)}
    data class Result(val success:Boolean,val retry:Boolean=false)
    suspend fun check(downloadAutomatically:Boolean=true):Result=withContext(Dispatchers.IO) {mutex.withLock {
        operation.value=UpdateOperation.CHECKING
        try {
            val settings=store.settings.first()
            if(!UpdateRules.validRepository(settings.config.repository)) throw UpdateException(UpdateError.CONFIG)
            val repo=settings.config.repository
            val release=transport.latest(repo)
            val manifestAsset=release.assets.singleOrNull {it.name=="update.json"} ?: throw UpdateException(UpdateError.INVALID)
            val manifest=transport.manifest(repo,manifestAsset)
            UpdateRules.validate(manifest,trust.sdk,trust.certificate)
            val asset=release.assets.singleOrNull {it.name==manifest.apkAssetName} ?: throw UpdateException(UpdateError.INVALID)
            if(asset.id<=0 || asset.size!=manifest.size) throw UpdateException(UpdateError.INVALID)
            if(manifest.versionCode<=trust.currentVersion) {
                store.save(UpdateSnapshot(checkedAt=clock.millis()))
                cleanup()
            } else {
                val candidate=UpdateCandidate(repo,release.tag_name,asset.id,manifest)
                val old=store.snapshot.first()
                val ready=old.candidate==candidate && old.readyVersion==manifest.versionCode && apk(candidate).isFile
                store.save(UpdateSnapshot(clock.millis(),candidate,if(ready) manifest.versionCode else null))
                if(downloadAutomatically && settings.config.autoDownload) downloadLocked(candidate)
            }
            Result(true)
        } catch(e:CancellationException) {throw e
        } catch(e:Exception) {failure(e)
        } finally {operation.value=UpdateOperation.IDLE}
    } }
    suspend fun download():Result=withContext(Dispatchers.IO) {mutex.withLock {
        try {
            val candidate=store.snapshot.first().candidate ?: throw UpdateException(UpdateError.INVALID)
            if(candidate.repository!=store.settings.first().config.repository) throw UpdateException(UpdateError.INVALID)
            downloadLocked(candidate);Result(true)
        } catch(e:CancellationException) {throw e
        } catch(e:Exception) {failure(e)
        } finally {operation.value=UpdateOperation.IDLE}
    } }
    private suspend fun downloadLocked(candidate:UpdateCandidate) {
        UpdateRules.validate(candidate.manifest,trust.sdk,trust.certificate)
        val file=apk(candidate)
        if(file.isFile) {
            try {verify(file,candidate.manifest);markReady(candidate);return} catch(e:UpdateException) {file.delete()}
        }
        operation.value=UpdateOperation.DOWNLOADING;progress.value=0
        if(!directory.exists() && !directory.mkdirs()) throw UpdateException(UpdateError.STORAGE)
        val pending=File(directory,"download.part")
        try {
            transport.download(candidate.repository,candidate.assetId,pending,candidate.manifest.size) {progress.value=it}
            verify(pending,candidate.manifest)
            if(!pending.renameTo(file)) throw UpdateException(UpdateError.STORAGE)
            cleanup(file)
            markReady(candidate)
        } finally {pending.delete()}
    }
    private fun apk(candidate:UpdateCandidate)=File(directory,"hannun-${candidate.manifest.versionCode}.apk")
    private fun verify(file:File,manifest:UpdateManifest) {UpdateRules.verifyHash(file,manifest);trust.verify(file,manifest)}
    private suspend fun markReady(candidate:UpdateCandidate) {store.save(UpdateSnapshot(clock.millis(),candidate,candidate.manifest.versionCode))}
    private suspend fun failure(error:Exception):Result {
        val reason=when(error) {is UpdateException->error.reason;is java.io.FileNotFoundException,is SecurityException->UpdateError.STORAGE;is IOException->UpdateError.NETWORK;else->UpdateError.INVALID}
        val old=store.snapshot.first()
        store.save(old.copy(error=reason.label))
        return Result(false,reason.transient)
    }
    private fun cleanup(keep:File?=null) {directory.listFiles()?.filter {it!=keep && (it.name.endsWith(".apk") || it.name.endsWith(".part"))}?.forEach {it.delete()}}
    suspend fun installerIntent(context:Context):Intent=withContext(Dispatchers.IO) {mutex.withLock {
        val snapshot=store.snapshot.first();val candidate=snapshot.candidate ?: throw UpdateException(UpdateError.INVALID)
        if(snapshot.readyVersion!=candidate.manifest.versionCode || candidate.repository!=store.settings.first().config.repository) throw UpdateException(UpdateError.INVALID)
        try {verify(apk(candidate),candidate.manifest)} catch(e:UpdateException) {
            apk(candidate).delete()
            store.save(snapshot.copy(readyVersion=null,error=e.reason.label))
            throw e
        }
        UpdateInstaller.intent(context,apk(candidate))
    } }
}
