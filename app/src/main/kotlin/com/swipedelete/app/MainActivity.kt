package com.swipedelete.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.swipedelete.app.nav.AppNavHost
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SwipeDeleteTheme {
                AppNavHost()
            }
        }
    }
}
