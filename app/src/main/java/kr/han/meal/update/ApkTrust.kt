package kr.han.meal.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import java.io.File

interface ApkTrust {
    val currentVersion:Long
    val sdk:Int
    val certificate:String
    fun verify(file:File,manifest:UpdateManifest)
}
class AndroidApkTrust(private val context:Context):ApkTrust {
    override val currentVersion:Long get()=kr.han.meal.BuildConfig.VERSION_CODE.toLong()
    override val sdk:Int get()=Build.VERSION.SDK_INT
    private val flags:Int get()=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun signatures(info:PackageInfo):List<String> = (if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map {sha256(it.toByteArray())}.sorted()
    override val certificate:String get() {
        @Suppress("DEPRECATION")
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        return signatures(installed).singleOrNull() ?: throw UpdateException(UpdateError.SIGNATURE)
    }
    override fun verify(file:File,manifest:UpdateManifest) {
        @Suppress("DEPRECATION")
        val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags) ?: throw UpdateException(UpdateError.INVALID)
        if(archive.packageName!=context.packageName || PackageInfoCompat.getLongVersionCode(archive)!=manifest.versionCode || archive.versionName!=manifest.versionName || archive.applicationInfo?.minSdkVersion!=manifest.minSdk || manifest.versionCode<=currentVersion) throw UpdateException(UpdateError.INVALID)
        if(signatures(archive)!=listOf(certificate) || !manifest.certificateSha256.equals(certificate,true)) throw UpdateException(UpdateError.SIGNATURE)
    }
}
object UpdateInstaller {
    fun intent(context:Context,file:File):Intent {
        if(!context.packageManager.canRequestPackageInstalls()) return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,"package:${context.packageName}".toUri())
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates.files",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
