package com.filmmaster.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.filmmaster.app.ui.CameraScreen
import com.filmmaster.app.ui.FilmMasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FilmMasterTheme {
                CameraScreen()
            }
        }
    }
}
