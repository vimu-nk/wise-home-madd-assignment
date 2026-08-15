package com.example.wisehome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.wisehome.data.AppPreferences
import com.example.wisehome.data.RepositoryProvider
import com.example.wisehome.notifications.AlertNotifier
import com.example.wisehome.ui.navigation.WiseHomeApp
import com.example.wisehome.ui.theme.WiseHomeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppPreferences.init(this)

        // Re-read everything whenever the socket reconnects...
        RepositoryProvider.startConnectionWatch()

        AlertNotifier.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

        // Surface backend-raised alerts (safety cut-offs) as system notifications while
        // the app is alive. Collected on STARTED, not RESUMED: an alert that lands with
        // the app merely backgrounded is exactly the one worth showing.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RepositoryProvider.alerts.newAlerts.collect { alert ->
                    AlertNotifier.notify(this@MainActivity, alert)
                }
            }
        }

        // ...and whenever the user comes back to the app. Between the two, state
        // can never be left stale by a change made while we weren't listening.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                RepositoryProvider.refreshAll()
            }
        }

        setContent {
            val themeMode by AppPreferences.themeMode.collectAsState()
            WiseHomeTheme(themeMode = themeMode) {
                WiseHomeApp()
            }
        }
    }

    /**
     * Asked once, without blocking anything: alerts are also visible in the Alerts tab,
     * so a user who declines loses the buzz but not the information.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }
}
