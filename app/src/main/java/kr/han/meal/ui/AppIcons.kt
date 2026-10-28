package kr.han.meal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small bundled vector set keeps debug APKs free of the full extended icon font library. */
object AppIcons {
    private fun icon(name:String,path:String)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).addPath(PathParser().parsePathString(path).toNodes(),fill=SolidColor(Color.Black)).build()
    val School=icon("School","M12,3 L1,9 L5,11.18 L5,17.18 L12,21 L19,17.18 L19,11.18 L21,10.09 L21,17 L23,17 L23,9 Z M12,5.28 L18.82,9 L12,12.72 L5.18,9 Z M7,12.27 L12,15 L17,12.27 L17,16 L12,18.73 L7,16 Z")
    val Restaurant=icon("Restaurant","M3,2 L5,2 L5,8 L6,8 L6,2 L8,2 L8,8 L9,8 L9,2 L11,2 L11,8 C11,10 10,11 8,11 L8,22 L6,22 L6,11 C4,11 3,10 3,8 Z M17,2 C14,6 14,12 18,12 L18,22 L20,22 L20,2 Z")
    val Widgets=icon("Widgets","M3,3 L11,3 L11,11 L3,11 Z M5,5 L5,9 L9,9 L9,5 Z M3,13 L11,13 L11,21 L3,21 Z M5,15 L5,19 L9,19 L9,15 Z M13,13 L21,13 L21,21 L13,21 Z M15,15 L15,19 L19,19 L19,15 Z M17,1 L23,7 L17,13 L11,7 Z M17,4 L14,7 L17,10 L20,7 Z")
}
