package kr.han.meal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kr.han.meal.MainActivity
import kr.han.meal.domain.model.MealType
import java.time.LocalDate

object WidgetIntents {
    private const val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    fun action(context:Context,id:Int,action:String):PendingIntent=PendingIntent.getBroadcast(context,id,
        Intent(context,WidgetActionReceiver::class.java).setAction("kr.han.meal.$action").setData(Uri.parse("hannun://widget/$id/$action")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),flags)
    fun configure(context:Context,id:Int):PendingIntent=PendingIntent.getActivity(context,id,
        Intent(context,WidgetConfigActivity::class.java).setData(Uri.parse("hannun://widget/$id/config")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),flags)
    fun open(context:Context,id:Int,date:LocalDate,type:MealType,school:String,weekly:Boolean):PendingIntent=PendingIntent.getActivity(context,id,
        Intent(context,MainActivity::class.java).setData(Uri.parse("hannun://widget/$id/$date/${type.name}/$weekly")).putExtra("date",date.toString()).putExtra("mealType",type.name).putExtra("schoolId",school).putExtra("weekly",weekly),flags)
}
