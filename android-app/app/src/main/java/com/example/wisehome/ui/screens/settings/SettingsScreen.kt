@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wisehome.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wisehome.BuildConfig
import com.example.wisehome.data.TemperatureUnit
import com.example.wisehome.data.ThemeMode
import com.example.wisehome.ui.components.ControlRow
import com.example.wisehome.ui.components.SectionCard
import com.example.wisehome.ui.format.StateTone
import com.example.wisehome.ui.format.formatRelative
import com.example.wisehome.ui.screens.home.colorForTone
import com.example.wisehome.ui.theme.Space
import io.github.jan.supabase.realtime.Realtime

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsState()
    val unit by viewModel.temperatureUnit.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()
    val lastSynced by viewModel.lastSyncedAt.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { CenterAlignedTopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            ConnectionCard(
                status = status,
                lastSynced = lastSynced,
                refreshing = refreshing,
                onRefresh = viewModel::refreshNow
            )

            SectionCard(title = "Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                        ) {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Units") {
                Text("Temperature", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    TemperatureUnit.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = unit == value,
                            onClick = { viewModel.setTemperatureUnit(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, TemperatureUnit.entries.size)
                        ) {
                            Text(if (value == TemperatureUnit.CELSIUS) "Celsius (°C)" else "Fahrenheit (°F)")
                        }
                    }
                }
            }

            SectionCard(title = "Your home") {
                ControlRow(title = "Floors") { Text("${summary.floors.size}") }
                ControlRow(title = "Rooms") { Text("${summary.roomCount}") }
                ControlRow(title = "Devices") { Text("${summary.deviceCount}") }
                summary.floors.forEach { floor ->
                    Text(
                        "${floor.name} · ${floor.gridCols}×${floor.gridRows} grid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = "About") {
                Text(
                    "WiseHome — Smart Home Monitoring & Control",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    status: Realtime.Status,
    lastSynced: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val (label, tone) = when (status) {
        Realtime.Status.CONNECTED -> "Live · connected" to StateTone.ACTIVE
        Realtime.Status.CONNECTING -> "Reconnecting…" to StateTone.ATTENTION
        else -> "Offline" to StateTone.OFFLINE
    }

    SectionCard(title = "Connection") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(colorForTone(tone), CircleShape)
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = Space.s).weight(1f)
            )
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefresh) { Text("Refresh now") }
            }
        }
        Text(
            "Last synced ${formatRelative(lastSynced).replaceFirstChar { it.lowercase() }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            maskUrl(BuildConfig.SUPABASE_URL),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun maskUrl(url: String): String {
    val host = url.removePrefix("https://").removePrefix("http://").substringBefore('.')
    if (host.length <= 6) return url
    return "${host.take(4)}…${host.takeLast(2)}.supabase.co"
}
