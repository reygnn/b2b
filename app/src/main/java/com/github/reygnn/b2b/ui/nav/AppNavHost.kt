package com.github.reygnn.b2b.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.reygnn.b2b.ui.AppViewModel
import com.github.reygnn.b2b.ui.login.LoginScreen
import com.github.reygnn.b2b.ui.settings.SettingsScreen
import com.github.reygnn.b2b.ui.whitelist.WhitelistScreen

object Routes {
    const val LOGIN = "login"
    const val WHITELIST = "whitelist"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost(vm: AppViewModel = hiltViewModel()) {
    val isAuthed by vm.isAuthenticated.collectAsState()
    val nav = rememberNavController()
    val start = if (isAuthed) Routes.WHITELIST else Routes.LOGIN

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen()
        }
        composable(Routes.WHITELIST) {
            WhitelistScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }

    // React to auth state changes: kick from login → whitelist on sign-in,
    // and from any logged-in screen back to login on sign-out.
    LaunchedEffect(isAuthed) {
        val target = if (isAuthed) Routes.WHITELIST else Routes.LOGIN
        val current = nav.currentDestination?.route
        if (current != null && current != target) {
            nav.navigate(target) {
                popUpTo(nav.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
