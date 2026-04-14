package com.rtm.rain

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
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

                    // Reading the current WiFi SSID needs ACCESS_FINE_LOCATION
                    // on API 29+. Ask once on first launch; if the user grants
                    // it, re-run the WiFi check so the initial fetch proceeds
                    // without a manual Refresh tap.
                    val permLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (granted) vm.refresh()
                    }
                    LaunchedEffect(Unit) {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasPerm) {
                            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }

                    RainScreen(vm)
                }
            }
        }
    }
}
