package com.example.wisehome.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.wisehome.ui.screens.alerts.AlertsScreen
import com.example.wisehome.ui.screens.home.HomeScreen
import com.example.wisehome.ui.screens.settings.SettingsScreen

@Composable
fun WiseHomeApp() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { WiseHomeBottomBar(navController) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Alerts.route) { AlertsScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}

@Composable
private fun WiseHomeBottomBar(navController: androidx.navigation.NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationBar {
        Screen.bottomNavItems.forEach { screen ->
            val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(screen.icon, contentDescription = screen.label) },
                label = { Text(screen.label) }
            )
        }
    }
}
