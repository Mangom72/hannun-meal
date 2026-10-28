package kr.han.meal

import kr.han.meal.data.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.*

class SchoolDirectoryTest {
    @Test fun officialAlphanumericSchoolSurvivesDirectoryDownloadAndLocalReload()=runBlocking {
        val server=MockWebServer();server.start();val files=Files.createTempDirectory("schools").toFile()
        try {
            val text="""{"schemaVersion":1,"schools":[["N10","C035903","국립공주대학교사범대학부설고등학교","고등학교","충청남도","충청남도 공주시"]]}"""
            val hash=directoryHash(text.toByteArray())
            server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"generatedAt":"2026-10-26T00:00:00Z","count":1,"sha256":"$hash","file":"schools.$hash.json"}"""))
            server.enqueue(MockResponse().setBody(text))
            val endpoint=server.url("/").toString()
            val directory=SchoolDirectory(PublicJson(OkHttpClient()),files,clock,1)
            directory.refresh(endpoint)
            assertEquals("N10:C035903",directory.search("국립공주").single().id)
            val reloaded=SchoolDirectory(PublicJson(OkHttpClient()),files,clock,1)
            reloaded.load(endpoint)
            assertEquals("N10:C035903",reloaded.search("국립공주").single().id)
            assertEquals(2,server.requestCount)
        } finally {server.shutdown();files.deleteRecursively()}
    }
    private val clock=Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),ZoneOffset.UTC)
    private val data="""{"schemaVersion":1,"schools":[["B10","7010057","한빛초등학교","초등학교","서울특별시","서울 송파구"],["J10","7010058","한빛초등학교","초등학교","경기도","경기 수원시"],["B10","7010059","한빛고등학교","고등학교","서울특별시","서울 강남구"]]}"""
    private fun enqueue(server:MockWebServer,hash:String=directoryHash(data.toByteArray())) {
        server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"generatedAt":"2026-10-26T00:00:00Z","count":3,"sha256":"$hash","file":"schools.$hash.json"}"""))
        server.enqueue(MockResponse().setBody(data))
    }
    @Test fun localAutocompleteDuplicateNamesRegionAndTypeDoNotMakeNetworkRequests()=runBlocking {
        val server=MockWebServer();server.start();val files=Files.createTempDirectory("schools").toFile()
        try {
            enqueue(server)
            val dir=SchoolDirectory(PublicJson(OkHttpClient()),files,clock,1)
            dir.refresh(server.url("/").toString())
            assertEquals(2,dir.search("한빛초").size)
            assertEquals("J10:7010058",dir.search("경기 한빛초").single().id)
            assertEquals(1,dir.search("한빛","고등학교").size)
            assertEquals(2,dir.search("한빛초").map {it.id}.distinct().size)
            repeat(100) {dir.search("한빛")}
            assertEquals(2,server.requestCount)
            val reloaded=SchoolDirectory(PublicJson(OkHttpClient()),files,clock,1)
            reloaded.load(server.url("/").toString());assertEquals(3,reloaded.search("한빛").size)
            assertEquals(2,server.requestCount)
        } finally {server.shutdown();files.deleteRecursively()}
    }
    @Test fun checksumFailurePreservesPreviousDirectoryAcrossRestart()=runBlocking {
        val server=MockWebServer();server.start();val files=Files.createTempDirectory("schools").toFile()
        try {
            enqueue(server)
            SchoolDirectory(PublicJson(OkHttpClient()),files,clock,1).refresh(server.url("/").toString())
            val before=files.listFiles()!!.single().readBytes()
            enqueue(server,"a".repeat(64))
            val later=clock.withZone(ZoneOffset.UTC).let {Clock.offset(it,Duration.ofDays(2))}
            val dir=SchoolDirectory(PublicJson(OkHttpClient()),files,later,1)
            try {dir.refresh(server.url("/").toString());fail("bad checksum accepted")} catch(_:NeisException) {}
            assertEquals(3,dir.search("한빛").size)
            assertArrayEquals(before,files.listFiles()!!.single().readBytes())
        } finally {server.shutdown();files.deleteRecursively()}
    }
    @Test fun unchangedDirectoryRevalidationDownloadsOnlyTheSmallIndex()=runBlocking {
        val server=MockWebServer();server.start();val files=Files.createTempDirectory("schools").toFile()
        try {
            enqueue(server)
            val endpoint=server.url("/").toString()
            SchoolDirectory(PublicJson(OkHttpClient()),files,clock,1).refresh(endpoint)
            val hash=directoryHash(data.toByteArray())
            server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"generatedAt":"2026-10-26T00:00:00Z","count":3,"sha256":"$hash","file":"schools.$hash.json"}"""))
            val later=SchoolDirectory(PublicJson(OkHttpClient()),files,Clock.offset(clock,Duration.ofDays(2)),1)
            later.refresh(endpoint)
            assertEquals(3,server.requestCount);assertEquals(3,later.search("한빛").size)
        } finally {server.shutdown();files.deleteRecursively()}
    }

}
