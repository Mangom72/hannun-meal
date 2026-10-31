package kr.han.meal.data.local

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Looper
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import kotlin.math.*

/** Private, metadata-free widget images. IDs are capabilities, never paths or source URIs. */
class WidgetBackgroundStore(private val context: Context, private val settings: SettingsStore) {
    private val directory get() = File(context.noBackupFilesDir,"widget_backgrounds")
    private val cache = object : LruCache<String,Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    companion object {
        private val token = Regex("[0-9a-f]{32}")
        fun validId(id:String?) = id != null && token.matches(id)
        const val MAX_INPUT_BYTES = 20 * 1024 * 1024
        const val MAX_PIXELS = 131072
        fun validSource(width:Int,height:Int) = width in 1..32768 && height in 1..32768 && width.toLong()*height<=100_000_000L
    }
    suspend fun import(uri:Uri):String {
        // Own the dispatcher-return cancellation boundary: a renamed file must not lose its ID.
        var committed:File?=null
        try {return withContext(Dispatchers.IO) {
        var source:File?=null
        var output:File?=null
        val intermediates=mutableListOf<Bitmap>()
        try {
            check(directory.isDirectory || directory.mkdirs())
            val inputFile=File.createTempFile("import-",".tmp",directory)
            source=inputFile
            context.contentResolver.openInputStream(uri)?.use { input ->
                inputFile.outputStream().use { out ->
                    val buffer=ByteArray(8192); var total=0
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count=input.read(buffer); if(count<0) break
                        total+=count; require(total<=MAX_INPUT_BYTES); out.write(buffer,0,count)
                    }
                }
            } ?: error("Unavailable")
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(inputFile.path,bounds)
            require(validSource(bounds.outWidth,bounds.outHeight))
            var sample=1
            while(bounds.outWidth/sample>1024 || bounds.outHeight/sample>1024 || (bounds.outWidth/sample).toLong()*(bounds.outHeight/sample)>524288) sample*=2
            val decoded=BitmapFactory.decodeFile(inputFile.path,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}) ?: error("Invalid")
            intermediates+=decoded
            val orientation=try {ExifInterface(inputFile.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}
                catch(_:Exception) {ExifInterface.ORIENTATION_NORMAL}
            val matrix=Matrix().apply {
                when(orientation) {
                    2 -> setScale(-1f,1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f,-1f)
                    5 -> {setRotate(90f);postScale(-1f,1f)}
                    6 -> setRotate(90f)
                    7 -> {setRotate(270f);postScale(-1f,1f)}
                    8 -> setRotate(270f)
                }
            }
            val oriented=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
            intermediates+=oriented
            val scale=minOf(1.0,512.0/max(oriented.width,oriented.height),sqrt(MAX_PIXELS.toDouble()/(oriented.width.toLong()*oriented.height)))
            val normalized=Bitmap.createScaledBitmap(oriented,max(1,floor(oriented.width*scale).toInt()),max(1,floor(oriented.height*scale).toInt()),true)
            intermediates+=normalized
            val id=UUID.randomUUID().toString().replace("-","")
            val outputFile=File.createTempFile("normalized-",".tmp",directory)
            output=outputFile
            outputFile.outputStream().use {check(normalized.compress(Bitmap.CompressFormat.PNG,100,it))}
            require(outputFile.length()<=1024*1024)
            currentCoroutineContext().ensureActive()
            val finalFile=File(directory,"$id.png")
            check(outputFile.renameTo(finalFile))
            committed=finalFile
            id
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {throw IllegalArgumentException("이미지를 불러올 수 없습니다")}
        finally {
            source?.delete();output?.delete()
            intermediates.distinct().forEach {if(!it.isRecycled) it.recycle()}
        }
        }} catch(e:CancellationException) {
            withContext(NonCancellable+Dispatchers.IO) {committed?.delete()}
            throw e
        }
    }
    /** Call from IO, including preview rendering. Cache entries are immutable and size-independent. */
    @Synchronized fun load(id:String?):Bitmap? {
        if(!validId(id)) return null
        cache.get(id!!)?.let {return it}
        if(Looper.myLooper()==Looper.getMainLooper()) return null
        return try {
            val file=File(directory,"$id.png")
            if(!file.isFile || file.length()>1024*1024) return null
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(file.path,bounds)
            if(bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512 || bounds.outWidth*bounds.outHeight>MAX_PIXELS) return null
            val decoded=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inPreferredConfig=Bitmap.Config.ARGB_8888;inMutable=false}) ?: return null
            val immutable=if(!decoded.isMutable) decoded else decoded.copy(Bitmap.Config.ARGB_8888,false).also {decoded.recycle()}
            immutable.also {cache.put(id,it)}
        } catch(_:Exception) {null}
    }
    suspend fun discard(ids:Collection<String>) = withContext(Dispatchers.IO) {
        val referenced=settings.widgets.first().mapNotNull {it.backgroundImageId}.toSet()
        ids.distinct().filter {validId(it) && it !in referenced}.forEach {id ->
            synchronized(this@WidgetBackgroundStore) {cache.remove(id);File(directory,"$id.png").delete()}
        }
    }
}
