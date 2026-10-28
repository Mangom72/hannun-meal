package kr.han.meal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.han.meal.ui.MealApp

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent { MealApp(viewModel(),intent.getStringExtra("date"),intent.getStringExtra("mealType"),intent.getStringExtra("schoolId"),intent.getBooleanExtra("weekly",false)) }
    }
}
