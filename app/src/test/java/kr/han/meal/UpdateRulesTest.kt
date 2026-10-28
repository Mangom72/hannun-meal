package kr.han.meal

import kr.han.meal.update.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

fun testManifest(bytes:ByteArray="test-apk".toByteArray(),version:Long=BuildConfig.VERSION_CODE+1L)=UpdateManifest(1,BuildConfig.APPLICATION_ID,version,"1.1.1",26,UpdateRules.APK_NAME,bytes.size.toLong(),sha256(bytes),"ab".repeat(32))
class UpdateRulesTest {
    @Test fun repositoryNamesRejectPathsUrlsAndInjection() {
        assertTrue(UpdateRules.validRepository("Mangom72/hannun-meal"))
        listOf("https://github.com/user/repo","user/repo/extra","../repo","user/..","user/name\nheader","user/repo?key=x","user/").forEach {assertFalse(it,UpdateRules.validRepository(it))}
    }
    @Test fun acceptsCompatibleManifest() {UpdateRules.validate(testManifest(),36,"ab".repeat(32))}
    @Test fun rejectsWrongCertificate() {assertEquals(UpdateError.SIGNATURE,assertThrows(UpdateException::class.java) {UpdateRules.validate(testManifest(),36,"cd".repeat(32))}.reason)}
    @Test fun rejectsUnsupportedAndroid() {assertEquals(UpdateError.INCOMPATIBLE,assertThrows(UpdateException::class.java) {UpdateRules.validate(testManifest().copy(minSdk=37),36,"ab".repeat(32))}.reason)}
    @Test fun rejectsIdentityBoundsAndMalformedDigests() {
        val m=testManifest()
        listOf(m.copy(schemaVersion=2),m.copy(applicationId="malicious.app"),m.copy(versionCode=0),m.copy(versionCode=Int.MAX_VALUE+1L),m.copy(apkAssetName="../../evil.apk"),m.copy(size=UpdateRules.MAX_APK_BYTES+1),m.copy(size=0),m.copy(sha256="00"),m.copy(certificateSha256="00"),m.copy(notes="x".repeat(8001))).forEach {bad->assertEquals(UpdateError.INVALID,assertThrows(UpdateException::class.java) {UpdateRules.validate(bad,36,"ab".repeat(32))}.reason)}
    }
    @Test fun hashDetectsTamperingAndTruncation() {
        val bytes="test-apk".toByteArray();val file=File.createTempFile("update", ".apk")
        try {
            file.writeBytes(bytes);UpdateRules.verifyHash(file,testManifest(bytes))
            file.writeBytes("evil-apk".toByteArray());assertThrows(UpdateException::class.java) {UpdateRules.verifyHash(file,testManifest(bytes))}
            file.writeBytes(byteArrayOf());assertThrows(UpdateException::class.java) {UpdateRules.verifyHash(file,testManifest(bytes))}
        } finally {file.delete()}
    }
}
