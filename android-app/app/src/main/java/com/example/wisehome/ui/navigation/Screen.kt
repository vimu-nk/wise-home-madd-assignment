package com.example.wisehome.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Alerts : Screen("alerts", "Alerts", Icons.Filled.Notifications)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val bottomNavItems = listOf(Home, Alerts, Settings)
    }
}
