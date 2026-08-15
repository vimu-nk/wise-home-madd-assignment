package com.example.wisehome

import android.os.Bundle
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
}
