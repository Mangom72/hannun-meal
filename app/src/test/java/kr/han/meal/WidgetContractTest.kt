package kr.han.meal

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.view.LayoutInflater
import android.view.View
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import kr.han.meal.widget.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class WidgetContractTest {
    @Test fun allFourProvidersHaveConfigurationMetadata() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val pm=context.packageManager
        val ns="http://schemas.android.com/apk/res/android"
        assertEquals(4,widgetProviders.size)
        widgetProviders.values.forEach {provider->
            val info=pm.getReceiverInfo(ComponentName(context,provider),android.content.pm.PackageManager.GET_META_DATA)
            assertFalse(info.exported)
            val resource=info.metaData.getInt("android.appwidget.provider")
            assertTrue(resource!=0)
            val xml=context.resources.getXml(resource)
            while(xml.eventType!=org.xmlpull.v1.XmlPullParser.START_TAG && xml.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) xml.next()
            assertEquals("appwidget-provider",xml.name)
            assertEquals("kr.han.meal.widget.WidgetConfigActivity",xml.getAttributeValue(ns,"configure"))
            assertNotNull(xml.getAttributeValue(ns,"minWidth"));assertNotNull(xml.getAttributeValue(ns,"minHeight"))
            xml.close()
        }
        assertTrue(pm.getActivityInfo(ComponentName(context,WidgetConfigActivity::class.java),0).exported)
    }
    @Test fun remoteViewsLayoutAndChronometerInflate() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val rv=RemoteViews(context.packageName,R.layout.widget_meal)
        val row=RemoteViews(context.packageName,R.layout.widget_row)
        row.setTextViewText(R.id.row_label,"중식");row.setTextViewText(R.id.row_menu,"로컬 테스트")
        rv.addView(R.id.widget_body,row)
        rv.setChronometer(R.id.widget_countdown,android.os.SystemClock.elapsedRealtime()+60_000,"식사까지 %s",true)
        rv.setChronometerCountDown(R.id.widget_countdown,true)
        val view=rv.apply(context,LinearLayout(context))
        assertEquals(1,view.findViewById<LinearLayout>(R.id.widget_body).childCount)
        assertTrue(view.findViewById<Chronometer>(R.id.widget_countdown).isCountDown)
    }
    @Test fun pendingIntentsAreScopedToInstanceAndAction() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val a=WidgetIntents.action(context,101,"next")
        val b=WidgetIntents.action(context,102,"next")
        val c=WidgetIntents.action(context,101,"previous")
        assertNotEquals(a,b);assertNotEquals(a,c)
        assertEquals(101,org.robolectric.Shadows.shadowOf(a).savedIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,0))
        val date=java.time.LocalDate.parse("2026-10-26")
        val first=WidgetIntents.open(context,101,date,kr.han.meal.domain.model.MealType.LUNCH,"B10:1",false)
        val second=WidgetIntents.open(context,102,date,kr.han.meal.domain.model.MealType.LUNCH,"J10:2",false)
        assertNotEquals(first,second)
        assertEquals("B10:1",org.robolectric.Shadows.shadowOf(first).savedIntent.getStringExtra("schoolId"))
    }
    @Test fun invalidConfigurationIdIsCanceled() {
        val activity=org.robolectric.Robolectric.buildActivity(WidgetConfigActivity::class.java,android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)).create().get()
        assertTrue(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_CANCELED,org.robolectric.Shadows.shadowOf(activity).resultCode)
    }

    @Test fun backgroundPermissionsStayMinimal() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val permissions=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.USE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.FOREGROUND_SERVICE" in permissions)
    }

    @Test fun updateFileProviderExposesOnlyPrivateUpdateCache() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val authority="${context.packageName}.updates.files"
        val info=context.packageManager.resolveContentProvider(authority,0)!!
        assertFalse(info.exported);assertTrue(info.grantUriPermissions)
        val apk=java.io.File(context.cacheDir,"updates/verified.apk")
        val uri=androidx.core.content.FileProvider.getUriForFile(context,authority,apk)
        assertEquals("content",uri.scheme)
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(context,authority,java.io.File(context.filesDir,"datastore/updates.preferences_pb"))
        }
    }

}
