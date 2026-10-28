package kr.han.meal

import android.app.Application
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @Test fun adaptiveLayersStayInTheSafeCircleAndRenderWithCommonMasks() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        assertEquals("한눈급식",context.getString(R.string.app_name))
        val icon=context.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
        val out=File("build/reports/icon-previews").apply {mkdirs()}
        for(resource in listOf(R.drawable.ic_meal,R.drawable.ic_meal_mono)) {
            val mark=context.getDrawable(resource)!!
            val b=Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888)
            mark.setBounds(0,0,108,108);mark.draw(Canvas(b))
            var ink=0
            for(y in 0..107) for(x in 0..107) if(Color.alpha(b.getPixel(x,y))>0) {
                ink++;assertTrue("Foreground outside safe circle at $x,$y",(x-54)*(x-54)+(y-54)*(y-54)<=33*33)
            }
            assertTrue(ink>800);assertEquals(0,Color.alpha(b.getPixel(0,0)))
            b.recycle()
        }
        for(mask in listOf("circle","rounded-square","square")) for(themed in listOf(false,true)) {
            val size=192;val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            val clip=Path().apply {when(mask) {"circle"->addCircle(96f,96f,96f,Path.Direction.CW);"rounded-square"->addRoundRect(0f,0f,192f,192f,44f,44f,Path.Direction.CW);else->addRect(0f,0f,192f,192f,Path.Direction.CW)}}
            canvas.clipPath(clip);canvas.drawColor(Color.parseColor(if(themed) "#D9EDE1" else "#123F32"))
            val mark=context.getDrawable(if(themed) R.drawable.ic_meal_mono else R.drawable.ic_meal)!!.mutate()
            if(themed) mark.setTint(Color.parseColor("#135C48"))
            mark.setBounds(-size/4,-size/4,size*5/4,size*5/4);mark.draw(canvas)
            File(out,"$mask-${if(themed) "themed" else "color"}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
}
