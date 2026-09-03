// >>> FILE: app/src/main/java/com/client/app/MainActivity.kt
package com.client.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.client.app.ui.screens.ClientScreen
import com.client.app.ui.screens.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

private val S23UltraDarkScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF09090B),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF09090B),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF18181B),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF27272A),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFAFAFA)
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = S23UltraDarkScheme) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "client") {
                    composable("client") {
                        ClientScreen(onNavigateSettings = { nav.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}