package com.client.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.client.app.ui.screens.ClientScreen
import com.client.app.ui.screens.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
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