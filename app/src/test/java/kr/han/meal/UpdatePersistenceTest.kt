package kr.han.meal

import android.app.Application
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.han.meal.update.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class UpdatePersistenceTest {
    private class Trust:ApkTrust {
        override val currentVersion=BuildConfig.VERSION_CODE.toLong();override val sdk=36;override val certificate="ab".repeat(32)
        var verifications=0;var reject=false
        override fun verify(file:File,manifest:UpdateManifest) {verifications++;if(reject) throw UpdateException(UpdateError.SIGNATURE)}
    }
    private suspend fun fixture(block:suspend (UpdateStore,File)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val file=File(context.cacheDir,"update-${System.nanoTime()}.preferences_pb")
        val directory=File(context.cacheDir,"apk-${System.nanoTime()}")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store=UpdateStore(PreferenceDataStoreFactory.create(scope=scope,produceFile={file}),removeLegacyKey={})
        try {block(store,directory)} finally {scope.coroutineContext[Job]!!.cancelAndJoin();directory.deleteRecursively();file.delete()}
    }
    @Test fun obsoleteCredentialsAreRemovedAndSchedulingPreferencesArePreserved()=runTest {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val file=File(context.cacheDir,"legacy-${System.nanoTime()}.preferences_pb")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val data=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
        var keyRemoved=false
        val store=UpdateStore(data,removeLegacyKey={keyRemoved=true})
        try {
            data.edit {p->
                p[stringPreferencesKey("config")]=Json.encodeToString(UpdateConfig("owner/private",automatic=false,autoDownload=false,wifiOnly=false))
                p[stringPreferencesKey("token_ciphertext")]="obsolete-encrypted-value"
            }
            store.migrateLegacyCredentials()
            val config=store.settings.first().config
            assertEquals(BuildConfig.UPDATE_REPOSITORY,config.repository)
            assertFalse(config.automatic);assertFalse(config.autoDownload);assertFalse(config.wifiOnly)
            assertNull(data.data.first()[stringPreferencesKey("token_ciphertext")])
            assertTrue(keyRemoved)
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();file.delete()}
    }
    private fun enqueue(server:MockWebServer,manifest:UpdateManifest,apk:ByteArray="test-apk".toByteArray()) {
        val body=Json.encodeToString(manifest)
        server.enqueue(MockResponse().setBody("""{"tag_name":"v1.1.1","assets":[{"id":1,"name":"update.json","size":${body.toByteArray().size}},{"id":2,"name":"hannun-meal.apk","size":${manifest.size}}]}"""))
        server.enqueue(MockResponse().setBody(body));server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)))
    }
    @Test fun validUpdateIsVerifiedBeforeReadyAndReusedWithoutRedownload()=runTest {fixture {store,directory ->
        val server=MockWebServer();server.start()
        try {
            store.configure(UpdateConfig());val trust=Trust()
            val repo=UpdateRepository(store,GitHubTransport(server.url("/")),directory,trust)
            enqueue(server,testManifest())
            assertTrue(repo.check().success);assertEquals(3,server.requestCount);assertEquals(1,trust.verifications)
            assertEquals(testManifest().versionCode,repo.state.first().snapshot.readyVersion)
            assertTrue(repo.download().success);assertEquals(3,server.requestCount);assertEquals(2,trust.verifications)
        } finally {server.shutdown()}
    }}
    @Test fun tamperedApkNeverBecomesReadyAndPartialFileIsRemoved()=runTest {fixture {store,directory ->
        val server=MockWebServer();server.start()
        try {
            store.configure(UpdateConfig())
            val repo=UpdateRepository(store,GitHubTransport(server.url("/")),directory,Trust())
            enqueue(server,testManifest(),"evil-apk".toByteArray())
            assertFalse(repo.check().success);assertNull(repo.state.first().snapshot.readyVersion)
            assertFalse(File(directory,"download.part").exists());assertFalse(directory.listFiles().orEmpty().any {it.name.endsWith(".apk")})
        } finally {server.shutdown()}
    }}
    @Test fun archiveSignatureFailureCannotReachInstallerReadyState()=runTest {fixture {store,directory ->
        val server=MockWebServer();server.start()
        try {
            store.configure(UpdateConfig())
            val repo=UpdateRepository(store,GitHubTransport(server.url("/")),directory,Trust().apply {reject=true})
            enqueue(server,testManifest())
            assertFalse(repo.check().success);assertNull(repo.state.first().snapshot.readyVersion)
            assertEquals(UpdateError.SIGNATURE.label,repo.state.first().snapshot.error)
        } finally {server.shutdown()}
    }}
    @Test fun authFailurePreservesPreviouslyPreparedUpdate()=runTest {fixture {store,directory ->
        val server=MockWebServer();server.start()
        try {
            store.configure(UpdateConfig())
            val repo=UpdateRepository(store,GitHubTransport(server.url("/")),directory,Trust())
            enqueue(server,testManifest());assertTrue(repo.check().success)
            server.enqueue(MockResponse().setResponseCode(401))
            assertFalse(repo.check().success);assertEquals(testManifest().versionCode,repo.state.first().snapshot.readyVersion)
        } finally {server.shutdown()}
    }}
    @Test fun sameOrOlderVersionDoesNotDownloadOrDowngrade()=runTest {fixture {store,directory ->
        val server=MockWebServer();server.start()
        try {
            store.configure(UpdateConfig())
            val manifest=testManifest(version=BuildConfig.VERSION_CODE.toLong());val body=Json.encodeToString(manifest)
            server.enqueue(MockResponse().setBody("""{"tag_name":"v1.1.0","assets":[{"id":1,"name":"update.json","size":${body.toByteArray().size}},{"id":2,"name":"hannun-meal.apk","size":${manifest.size}}]}"""));server.enqueue(MockResponse().setBody(body))
            val repo=UpdateRepository(store,GitHubTransport(server.url("/")),directory,Trust())
            assertTrue(repo.check().success);assertNull(repo.state.first().snapshot.candidate);assertEquals(2,server.requestCount)
        } finally {server.shutdown()}
    }}
    @Test fun freshInstallChecksAndDownloadsPublicReleaseWithoutCredentials()=runTest {fixture {store,directory ->
        val server=MockWebServer();server.start()
        try {
            enqueue(server,testManifest())
            val repo=UpdateRepository(store,GitHubTransport(server.url("/")),directory,Trust())
            assertTrue(repo.check().success);assertEquals(3,server.requestCount)
            repeat(3) {assertNull(server.takeRequest().getHeader("Authorization"))}
            assertNotNull(repo.state.first().snapshot.readyVersion)
        } finally {server.shutdown()}
    }}
    @Test fun reclaimedApkCacheReturnsToDownloadableState()=runTest {fixture {store,directory ->
        val server=MockWebServer();server.start()
        try {
            store.configure(UpdateConfig())
            val repo=UpdateRepository(store,GitHubTransport(server.url("/")),directory,Trust())
            enqueue(server,testManifest());assertTrue(repo.check().success)
            directory.listFiles().orEmpty().forEach {it.delete()}
            assertNull(repo.state.first().snapshot.readyVersion)
            assertNotNull(repo.state.first().snapshot.candidate)
        } finally {server.shutdown()}
    }}

}
