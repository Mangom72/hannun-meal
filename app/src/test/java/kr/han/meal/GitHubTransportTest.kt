package kr.han.meal

import kotlinx.coroutines.test.runTest
import kr.han.meal.update.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class GitHubTransportTest {
    @Test fun latestPublicReleaseNeedsNoAuthentication()=runTest {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"tag_name":"v1.1.1","draft":false,"prerelease":false,"assets":[{"id":5,"name":"update.json","size":100}]}"""))
            val release=GitHubTransport(server.url("/")).latest("owner/public")
            assertEquals("v1.1.1",release.tag_name)
            val request=server.takeRequest();assertEquals("/repos/owner/public/releases/latest",request.path)
            assertNull(request.getHeader("Authorization"));assertEquals("2022-11-28",request.getHeader("X-GitHub-Api-Version"))
        } finally {server.shutdown()}
    }
    @Test fun authenticationNotFoundAndRateLimitRemainDistinct()=runTest {
        val server=MockWebServer();server.start()
        try {
            val client=GitHubTransport(server.url("/"))
            for((code,expected) in listOf(401 to UpdateError.AUTH,404 to UpdateError.NOT_FOUND,429 to UpdateError.RATE_LIMIT,503 to UpdateError.SERVER)) {
                server.enqueue(MockResponse().setResponseCode(code))
                try {client.latest("owner/public");fail("must reject HTTP $code")} catch(e:UpdateException) {assertEquals(expected,e.reason);assertFalse(e.message!!.contains("never-log-this-token"))}
            }
        } finally {server.shutdown()}
    }
    @Test fun publicAssetRedirectSendsNoCredentials()=runTest {
        val api=MockWebServer();val cdn=MockWebServer();api.start();cdn.start()
        val file=File.createTempFile("updater", ".apk")
        try {
            api.enqueue(MockResponse().setResponseCode(302).setHeader("Location",cdn.url("/signed-download")))
            cdn.enqueue(MockResponse().setBody("apk"))
            val transport=GitHubTransport(baseUrl=api.url("/"),allowRedirect={it.host=="localhost"})
            transport.download("owner/public",7,file,3) {}
            assertNull(api.takeRequest().getHeader("Authorization"))
            assertNull(cdn.takeRequest().getHeader("Authorization"));assertEquals("apk",file.readText())
        } finally {file.delete();api.shutdown();cdn.shutdown()}
    }
    @Test fun untrustedOrCleartextRedirectIsRejected()=runTest {
        val server=MockWebServer();server.start()
        val file=File.createTempFile("updater", ".apk")
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","http://example.invalid/steal"))
            try {GitHubTransport(server.url("/")).download("owner/public",7,file,3) {};fail("redirect must be blocked")} catch(e:UpdateException) {assertEquals(UpdateError.INVALID,e.reason)}
            assertEquals(1,server.requestCount)
        } finally {file.delete();server.shutdown()}
    }
    @Test fun oversizedAndTruncatedDownloadsAreRejected()=runTest {
        val server=MockWebServer();server.start();val file=File.createTempFile("updater", ".apk")
        try {
            val client=GitHubTransport(server.url("/"))
            server.enqueue(MockResponse().setBody("too-large"))
            try {client.download("owner/public",1,file,3) {};fail("oversize")} catch(e:UpdateException) {assertEquals(UpdateError.INVALID,e.reason)}
            server.enqueue(MockResponse().setBody("a"))
            try {client.download("owner/public",1,file,3) {};fail("truncated")} catch(e:UpdateException) {assertEquals(UpdateError.INVALID,e.reason)}
        } finally {file.delete();server.shutdown()}
    }
    @Test fun manifestDownloadEnforcesDeclaredSizeAndSchemaParsing()=runTest {
        val server=MockWebServer();server.start()
        try {
            val body=Json.encodeToString(testManifest());val bytes=body.toByteArray()
            server.enqueue(MockResponse().setBody(body))
            val result=GitHubTransport(server.url("/")).manifest("owner/public",GitHubAsset(4,"update.json",bytes.size.toLong()))
            assertEquals(testManifest(),result)
            assertEquals("application/octet-stream",server.takeRequest().getHeader("Accept"))
        } finally {server.shutdown()}
    }
}
