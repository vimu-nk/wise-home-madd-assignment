@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.wisehome.ui.screens.alerts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Iron
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wisehome.data.model.Alert
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.ui.components.DeviceIconBadge
import com.example.wisehome.ui.components.EmptyState
import com.example.wisehome.ui.format.StateTone
import com.example.wisehome.ui.format.rememberRelativeTime
import com.example.wisehome.ui.screens.home.colorForTone
import com.example.wisehome.ui.theme.Space
import kotlinx.coroutines.launch

@Composable
fun AlertsScreen(viewModel: AlertsViewModel = viewModel()) {
    val alerts by viewModel.alerts.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    val unread = alerts.filterNot { it.acknowledged }
    val earlier = alerts.filter { it.acknowledged }
    val devicesById = devices.associateBy { it.id }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Alerts") },
                actions = {
                    if (unread.isNotEmpty()) {
                        IconButton(onClick = { viewModel.acknowledgeAll() }) {
                            Icon(Icons.Filled.DoneAll, contentDescription = "Mark all as read")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (alerts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Filled.NotificationsNone,
                    title = "All clear",
                    body = "Nothing needs your attention right now."
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            if (unread.isNotEmpty()) {
                item { SectionHeader("New") }
                items(unread, key = { it.id }) { alert ->
                    AlertCard(
                        alert = alert,
                        device = devicesById[alert.deviceId],
                        onAcknowledge = {
                            viewModel.setAcknowledged(alert.id, true)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Marked as read",
                                    actionLabel = "Undo"
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.setAcknowledged(alert.id, false)
                                }
                            }
                        }
                    )
                }
            }

            if (earlier.isNotEmpty()) {
                item { SectionHeader("Earlier") }
                items(earlier, key = { it.id }) { alert ->
                    AlertCard(alert = alert, device = devicesById[alert.deviceId], onAcknowledge = null)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Space.s)
        )
    }
}

@Composable
private fun AlertCard(alert: Alert, device: Device?, onAcknowledge: (() -> Unit)?) {
    val tone = if (alert.acknowledged) StateTone.IDLE else severityToneFor(device)
    val icon = iconForAlert(device)
    val timestamp = rememberRelativeTime(alert.createdAt)

    Card(
        // Fixed height keeps every alert card identical regardless of message
        // length; the message itself is clamped to two lines to match.
        modifier = Modifier.fillMaxWidth().height(96.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(Space.l),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceIconBadge(icon = icon, tint = colorForTone(tone), contentDescription = null)

            Column(modifier = Modifier.weight(1f).padding(start = Space.m)) {
                Text(
                    alert.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(device?.name, timestamp).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onAcknowledge != null) {
                IconButton(onClick = onAcknowledge, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Mark as read",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun severityToneFor(device: Device?): StateTone = when {
    device == null -> StateTone.ATTENTION
    device.type == DeviceType.SENSOR -> StateTone.FAULT
    else -> StateTone.ATTENTION
}

private fun iconForAlert(device: Device?): ImageVector = when (device?.type) {
    DeviceType.SCHEDULED_SAFETY -> Icons.Filled.Iron
    DeviceType.SENSOR -> Icons.Filled.LocalFireDepartment
    null -> Icons.Filled.WarningAmber
    else -> Icons.Filled.WarningAmber
}
