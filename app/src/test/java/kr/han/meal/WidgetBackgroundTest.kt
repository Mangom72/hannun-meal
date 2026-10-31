package kr.han.meal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.han.meal.data.local.*
import kr.han.meal.domain.model.*
import kr.han.meal.widget.WidgetBackgroundResolver
import kr.han.meal.core.AppGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetBackgroundTest {
    @Test fun legacyDefaultsAndNewFieldsRoundTrip() {
        val old=Json.decodeFromString<WidgetConfig>("""{"id":42,"color":2}""")
        assertTrue(old.backgroundEnabled);assertEquals(94,old.opacity)
        assertEquals(WidgetBackgroundMode.COLOR,old.backgroundMode);assertNull(old.customBackgroundColor)
        val custom=old.copy(backgroundEnabled=false,backgroundMode=WidgetBackgroundMode.IMAGE,backgroundImageId="a".repeat(32),customBackgroundColor=Color.RED,opacity=0)
        assertEquals(custom,Json.decodeFromString<WidgetConfig>(Json.encodeToString(custom)))
    }
    @Test fun strictTokensAndDecodeBudgets() {
        listOf(null,"","../secret","/tmp/image","content://photo","A".repeat(32),"a".repeat(31)).forEach {assertFalse(WidgetBackgroundStore.validId(it))}
        assertTrue(WidgetBackgroundStore.validId("a".repeat(32)))
        assertTrue(WidgetBackgroundStore.validSource(10000,10000))
        assertFalse(WidgetBackgroundStore.validSource(10001,10000))
        assertFalse(WidgetBackgroundStore.validSource(32769,1))
        assertFalse(WidgetBackgroundStore.validSource(0,512))
        assertEquals(Color.BLACK,WidgetBackgroundResolver.foreground(Color.WHITE))
        assertEquals(Color.WHITE,WidgetBackgroundResolver.foreground(Color.BLACK))
    }
    @Test fun importNormalizesCachesAndProtectsSharedReferences()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val settings=SettingsStore(app);val store=WidgetBackgroundStore(app,settings)
        val source=File.createTempFile("widget-test",".png",app.cacheDir)
        source.outputStream().use {Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,it)}
        val id=store.import(Uri.fromFile(source))
        try {
            val bitmap=withContext(Dispatchers.IO) {store.load(id)}!!
            assertTrue(bitmap.width<=512 && bitmap.height<=512)
            assertTrue(bitmap.width*bitmap.height<=WidgetBackgroundStore.MAX_PIXELS)
            assertFalse(bitmap.isMutable)
            assertSame(bitmap,withContext(Dispatchers.IO) {store.load(id)})
            settings.saveWidget(WidgetConfig(98765,backgroundImageId=id))
            settings.saveWidget(WidgetConfig(98766,backgroundImageId=id))
            store.discard(listOf(id))
            assertNotNull(withContext(Dispatchers.IO) {store.load(id)})
            settings.removeWidget(98765);store.discard(listOf(id))
            assertNotNull(withContext(Dispatchers.IO) {store.load(id)})
            settings.removeWidget(98766);store.discard(listOf(id))
            assertNull(withContext(Dispatchers.IO) {store.load(id)})
            assertNull(store.load("../secret"))
        } finally {settings.removeWidget(98765);settings.removeWidget(98766);store.discard(listOf(id));source.delete()}
    }
    @Test fun exifRotationPixelsMetadataAndResolverReuse()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val graph=AppGraph(app,startBackground=false)
        val source=File.createTempFile("widget-rotation",".jpg",app.cacheDir)
        val bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(40f,0f,80f,40f,Paint().apply {color=Color.BLUE})
        }
        source.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)}
        bitmap.recycle()
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_ARTIST,"synthetic-test")
            saveAttributes()
        }
        val id=graph.widgetBackgrounds.import(Uri.fromFile(source))
        try {
            withContext(Dispatchers.IO) {
                val result=graph.widgetBackgrounds.load(id)!!
                assertEquals(40,result.width);assertEquals(80,result.height)
                val top=result.getPixel(20,10);val bottom=result.getPixel(20,70)
                assertTrue(Color.red(top)>220 && Color.blue(top)<30)
                assertTrue(Color.blue(bottom)>220 && Color.red(bottom)<30)
                val normalized=File(app.noBackupFilesDir,"widget_backgrounds/$id.png")
                val exif=ExifInterface(normalized.path)
                assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
                // A metadata-free PNG may expose an absent orientation as UNDEFINED.
                val orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_UNDEFINED)
                assertTrue(orientation==ExifInterface.ORIENTATION_UNDEFINED || orientation==ExifInterface.ORIENTATION_NORMAL)
                val resolver=WidgetBackgroundResolver(graph)
                val config=WidgetConfig(1,backgroundMode=WidgetBackgroundMode.IMAGE,backgroundImageId=id)
                val first=resolver.resolve(config)
                assertSame(first.bitmap,resolver.resolve(config).bitmap)
                assertFalse(first.bitmap!!.isMutable)
                assertTrue(first.bitmap.allocationByteCount<=512*1024)
                val white=resolver.resolve(config.copy(backgroundMode=WidgetBackgroundMode.COLOR,customBackgroundColor=0x00ffffff))
                assertEquals(Color.WHITE,white.bitmap!!.getPixel(0,0))
                assertEquals(Color.BLACK,white.foreground)
                val black=resolver.resolve(config.copy(backgroundMode=WidgetBackgroundMode.COLOR,customBackgroundColor=0))
                assertEquals(Color.BLACK,black.bitmap!!.getPixel(0,0))
                assertEquals(Color.WHITE,black.foreground)
            }
        } finally {graph.widgetBackgrounds.discard(listOf(id));source.delete();graph.scope.cancel();graph.database.close()}
    }
    @Test fun cancellationAtDispatcherReturnRemovesCommittedFile()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val store=WidgetBackgroundStore(app,SettingsStore(app))
        val directory=File(app.noBackupFilesDir,"widget_backgrounds").apply {mkdirs()}
        val before=directory.list()!!.toSet()
        val source=File.createTempFile("widget-cancel",".png",app.cacheDir)
        source.outputStream().use {Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,it)}
        val queue=LinkedBlockingQueue<Runnable>()
        val dispatcher=object:CoroutineDispatcher() {
            override fun dispatch(context:CoroutineContext,block:Runnable) {queue.put(block)}
        }
        try {
            val job=launch(dispatcher,start=CoroutineStart.UNDISPATCHED) {store.import(Uri.fromFile(source));fail("Cancelled ID must not be delivered")}
            // IO has completed the rename and queued its return, but the caller has not resumed.
            val continuation=queue.poll(10,TimeUnit.SECONDS) ?: error("Import did not return")
            assertEquals(before.size+1,directory.list()!!.size)
            job.cancel();continuation.run()
            // NonCancellable cleanup returns to the controlled caller dispatcher as well.
            while(!job.isCompleted) (queue.poll(10,TimeUnit.SECONDS) ?: error("Cleanup did not return")).run()
            assertEquals(before,directory.list()!!.toSet())
        } finally {source.delete()}
    }
    @Test fun corruptAndOversizedInputsFailGenerically()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val store=WidgetBackgroundStore(app,SettingsStore(app))
        val file=File.createTempFile("widget-invalid",".bin",app.cacheDir)
        try {
            suspend fun rejected() {
                try {store.import(Uri.fromFile(file));fail("Expected rejection")}
                catch(e:IllegalArgumentException) {assertEquals("이미지를 불러올 수 없습니다",e.message)}
            }
            file.writeBytes(byteArrayOf(1,2,3));rejected()
            java.io.RandomAccessFile(file,"rw").use {it.setLength(WidgetBackgroundStore.MAX_INPUT_BYTES.toLong()+1)}
            rejected()
        } finally {file.delete()}
    }
}
