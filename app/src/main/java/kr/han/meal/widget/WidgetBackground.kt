package kr.han.meal.widget

import android.graphics.*
import android.util.LruCache
import kr.han.meal.R
import kr.han.meal.core.AppGraph
import kr.han.meal.domain.model.*
import kotlin.math.pow

data class WidgetBackground(val bitmap:Bitmap?=null,val foreground:Int?=null,val enabled:Boolean=true)

/** Size-independent custom backgrounds; legacy palettes retain resource day/night resolution. */
class WidgetBackgroundResolver(private val g:AppGraph) {
    private val images=object:LruCache<String,Bitmap>(4*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    private val solids=LruCache<Int,Bitmap>(32)
    @Synchronized fun resolve(c:WidgetConfig):WidgetBackground {
        if(!c.backgroundEnabled) return WidgetBackground(enabled=false)
        if(c.backgroundMode==WidgetBackgroundMode.IMAGE) {
            val source=g.widgetBackgrounds.load(c.backgroundImageId) ?: return WidgetBackground()
            val id=c.backgroundImageId!!
            val bitmap=images.get(id) ?: source.copy(Bitmap.Config.ARGB_8888,true).let {work ->
                Canvas(work).drawColor(Color.argb(g.app.resources.getInteger(R.integer.widget_image_scrim_alpha).coerceIn(0,255),0,0,0))
                work.copy(Bitmap.Config.ARGB_8888,false).also {work.recycle();images.put(id,it)}
            }
            return WidgetBackground(bitmap,Color.WHITE)
        }
        val color=c.customBackgroundColor?.let {it or (0xff shl 24)} ?: return WidgetBackground()
        val bitmap=solids.get(color) ?: Bitmap.createBitmap(intArrayOf(color),1,1,Bitmap.Config.ARGB_8888).also {solids.put(color,it)}
        return WidgetBackground(bitmap,foreground(color))
    }
    companion object {
        fun foreground(color:Int):Int {
            fun channel(shift:Int):Double {val v=((color ushr shift) and 255)/255.0;return if(v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4)}
            val luminance=.2126*channel(16)+.7152*channel(8)+.0722*channel(0)
            return if((luminance+.05)/.05 >= 1.05/(luminance+.05)) Color.BLACK else Color.WHITE
        }
    }
}
