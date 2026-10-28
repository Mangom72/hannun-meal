package kr.han.meal.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.han.meal.domain.model.AppSettings

/** Explicit role pairs avoid the purple default on a green container. Kept internal for contrast checks. */
internal fun mealColors(dark:Boolean, preset:Int):ColorScheme {
    val index=preset.coerceIn(0,2)
    val primary=listOf(0xFF135C48,0xFF55518B,0xFF79541E)[index]
    val lightContainer=listOf(0xFFD9EDE1,0xFFE8E1FF,0xFFFFE5BC)[index]
    val lightInk=listOf(0xFF103D2F,0xFF302B59,0xFF4E320A)[index]
    val darkPrimary=listOf(0xFFA4D7BE,0xFFCCC1FF,0xFFF0C386)[index]
    val darkContainer=listOf(0xFF214E3C,0xFF413C6B,0xFF5B4018)[index]
    return if(dark) darkColorScheme(
        primary=Color(darkPrimary),onPrimary=Color(lightInk),primaryContainer=Color(darkContainer),onPrimaryContainer=Color(lightContainer),
        secondary=Color(0xFFB8CCC0),onSecondary=Color(0xFF243A30),secondaryContainer=Color(0xFF30473B),onSecondaryContainer=Color(0xFFD4E8DA),
        tertiary=Color(0xFFEBC080),onTertiary=Color(0xFF422D08),tertiaryContainer=Color(0xFF594019),onTertiaryContainer=Color(0xFFFFDEAA),
        background=Color(0xFF111914),onBackground=Color(0xFFE0E8DF),surface=Color(0xFF111914),onSurface=Color(0xFFE0E8DF),
        surfaceVariant=Color(0xFF404A42),onSurfaceVariant=Color(0xFFBDCABF),outline=Color(0xFF87958B),outlineVariant=Color(0xFF404A42),
        surfaceContainerLowest=Color(0xFF0C140F),surfaceContainerLow=Color(0xFF19221C),surfaceContainer=Color(0xFF1D261F),surfaceContainerHigh=Color(0xFF273029),surfaceContainerHighest=Color(0xFF323B33)
    ) else lightColorScheme(
        primary=Color(primary),onPrimary=Color.White,primaryContainer=Color(lightContainer),onPrimaryContainer=Color(lightInk),
        secondary=Color(0xFF4D6355),onSecondary=Color.White,secondaryContainer=Color(0xFFE3EEE5),onSecondaryContainer=Color(0xFF2B4234),
        tertiary=Color(0xFF725220),onTertiary=Color.White,tertiaryContainer=Color(0xFFFFE5BC),onTertiaryContainer=Color(0xFF4E320A),
        background=Color(0xFFF6F8F4),onBackground=Color(0xFF17251F),surface=Color(0xFFF6F8F4),onSurface=Color(0xFF17251F),
        surfaceVariant=Color(0xFFDEE7DE),onSurfaceVariant=Color(0xFF47584C),outline=Color(0xFF758578),outlineVariant=Color(0xFFCED9CF),
        surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF0F4EE),surfaceContainer=Color(0xFFEAF0E8),surfaceContainerHigh=Color(0xFFE4EBE2),surfaceContainerHighest=Color(0xFFDDE5DA)
    )
}
internal fun mealTypography(scale:Float):Typography {
    val base=Typography(
        headlineLarge=TextStyle(fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold),
        headlineMedium=TextStyle(fontSize=24.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold),
        headlineSmall=TextStyle(fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold),
        titleLarge=TextStyle(fontSize=20.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),
        titleMedium=TextStyle(fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold),
        bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp)
    )
    fun TextStyle.scaled()=copy(fontFamily=FontFamily.SansSerif,fontSize=fontSize*scale.coerceIn(.8f,1.4f),lineHeight=lineHeight*scale.coerceIn(.8f,1.4f))
    return Typography(displayLarge=base.displayLarge.scaled(),displayMedium=base.displayMedium.scaled(),displaySmall=base.displaySmall.scaled(),headlineLarge=base.headlineLarge.scaled(),headlineMedium=base.headlineMedium.scaled(),headlineSmall=base.headlineSmall.scaled(),titleLarge=base.titleLarge.scaled(),titleMedium=base.titleMedium.scaled(),titleSmall=base.titleSmall.scaled(),bodyLarge=base.bodyLarge.scaled(),bodyMedium=base.bodyMedium.scaled(),bodySmall=base.bodySmall.scaled(),labelLarge=base.labelLarge.scaled(),labelMedium=base.labelMedium.scaled(),labelSmall=base.labelSmall.scaled())
}
@Composable fun MealTheme(p:AppSettings,content:@Composable ()->Unit) {
    val dark=when(p.theme) {"DARK"->true;"LIGHT"->false;else->isSystemInDarkTheme()}
    val colors=if(p.dynamicColor && Build.VERSION.SDK_INT>=31) {
        if(dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else mealColors(dark,p.color)
    val view=LocalView.current
    if(!view.isInEditMode) SideEffect {
        (view.context as? android.app.Activity)?.let {activity->WindowCompat.getInsetsController(activity.window,view).apply {
            isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
        }}
    }
    MaterialTheme(colorScheme=colors,typography=mealTypography(p.fontScale),shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}
