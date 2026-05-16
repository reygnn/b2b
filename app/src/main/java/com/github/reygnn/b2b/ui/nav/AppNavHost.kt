package com.github.reygnn.b2b.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.reygnn.b2b.ui.settings.SettingsScreen
import com.github.reygnn.b2b.ui.whitelist.WhitelistScreen

object Routes {
    const val WHITELIST = "whitelist"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.WHITELIST) {
        composable(Routes.WHITELIST) {
            WhitelistScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
