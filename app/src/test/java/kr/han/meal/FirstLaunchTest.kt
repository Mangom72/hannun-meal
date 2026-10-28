package kr.han.meal

import android.view.ViewGroup
import kotlinx.coroutines.*
import kr.han.meal.core.MealApplication
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=MealApplication::class)
class FirstLaunchTest {
    @Test fun firstLaunchOpensWithoutSchool() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        val graph=(activity.application as MealApplication).graph
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(activity.isFinishing)
            assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).childCount>0)
            assertEquals("한눈급식",activity.getString(R.string.app_name))
            assertNull(graph.preferences.value.school)
        } finally {
            controller.pause().stop().destroy()
            runBlocking {graph.scope.coroutineContext[Job]!!.cancelAndJoin()}
            graph.database.close()
        }
    }
}
