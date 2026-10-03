package com.client.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.client.app.logging.AppLogManager
import com.client.app.ui.display.DisplayRateManager
import com.client.app.ui.screens.ClientScreen
import com.client.app.ui.screens.LogViewerScreen
import com.client.app.ui.screens.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Чёрная палитра приложения (FM-лента голоса остаётся белой и задаёт цвета сама).
 */
private val ObsidianBlackScheme = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF09090B),
    secondary = Color(0xFF34A853),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF4285F4),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF0D0D0F),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF18181B),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainer = Color(0xFF111113),
    surfaceContainerHigh = Color(0xFF18181B),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272A),
    error = Color(0xFFEA4335),
    onError = Color(0xFFFFFFFF)
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logManager: AppLogManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Прозрачный Edge-to-Edge со светлыми системными значками на чёрном холсте
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // Аппаратная фиксация 120.000 Гц на дисплейном контроллере Dynamic AMOLED 2X S23 Ultra
        DisplayRateManager.setHighRefreshRate(window, true)

        setContent {
            MaterialTheme(colorScheme = ObsidianBlackScheme) {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "client",
                    enterTransition = {
                        fadeIn(animationSpec = tween(280, easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f))) +
                            slideInHorizontally(
                                initialOffsetX = { it / 5 },
                                animationSpec = tween(320, easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f))
                            )
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(220)) +
                            slideOutHorizontally(
                                targetOffsetX = { -it / 5 },
                                animationSpec = tween(260)
                            )
                    },
                    popEnterTransition = {
                        fadeIn(animationSpec = tween(280)) +
                            slideInHorizontally(
                                initialOffsetX = { -it / 5 },
                                animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f))
                            )
                    },
                    popExitTransition = {
                        fadeOut(animationSpec = tween(220)) +
                            slideOutHorizontally(
                                targetOffsetX = { it / 5 },
                                animationSpec = tween(260)
                            )
                    }
                ) {
                    // Главный экран: чистый белый лист без чата и сферы
                    composable("client") {
                        ClientScreen(
                            onNavigateSettings = { navController.navigate("settings") },
                            onNavigateLogs = { navController.navigate("logs") }
                        )
                    }

                    // Экран конфигурации параметров Gemini Live
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // Системный терминал логов
                    composable("logs") {
                        LogViewerScreen(
                            onBack = { navController.popBackStack() },
                            logManager = logManager
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Восстановление 120 Гц при возврате в приложение
        DisplayRateManager.setHighRefreshRate(window, true)
    }

    override fun onPause() {
        super.onPause()
        // Сброс фиксации для освобождения системного энергосбережения
        DisplayRateManager.setHighRefreshRate(window, false)
    }
}