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
import androidx.compose.material3.lightColorScheme
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
 * Эталонная палитра белого минимализма для Samsung Galaxy S23 Ultra (Braun / Dieter Rams / Apple Snow White).
 */
private val S23UltraPureWhiteScheme = lightColorScheme(
    primary = Color(0xFF09090B),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF0EA5E9),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF09090B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF09090B),
    surfaceVariant = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFF71717A),
    outline = Color(0xFFE2E8F0),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF)
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logManager: AppLogManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Конфигурация прозрачного Edge-to-Edge с черными системными глифами
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        // Аппаратная фиксация 120.000 Гц на дисплейном контроллере Dynamic AMOLED 2X S23 Ultra
        DisplayRateManager.setHighRefreshRate(window, true)

        setContent {
            MaterialTheme(colorScheme = S23UltraPureWhiteScheme) {
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