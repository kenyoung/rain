package com.rtm.rain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

// Dark gray used for card / elevated-surface backgrounds in dark mode.
private val darkGray = Color(0xFF2A2A2A)

// Fully custom dark scheme: pure-black screen, dark-gray cards, white text.
private val appDarkScheme = darkColorScheme(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = darkGray,
    onSurfaceVariant = Color.White,
    surfaceContainer = darkGray,
    surfaceContainerLow = darkGray,
    surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = darkGray,
    surfaceContainerHighest = darkGray,
)

// Light mode: keep Compose's defaults.
private val appLightScheme = lightColorScheme()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) appDarkScheme else appLightScheme
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: RainViewModel = viewModel(
                        factory = RainViewModel.factory(applicationContext),
                    )
                    RainScreen(vm)
                }
            }
        }
    }
}
