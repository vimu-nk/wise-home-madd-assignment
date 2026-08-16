package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.StateTone
import com.example.wisehome.ui.format.deviceStateTone
import com.example.wisehome.ui.theme.Space

/**
 * One room, as a card containing its devices.
 *
 * This is the old room card with its contents unfolded: the same identity line and
 * summary, but the devices sit inside it instead of behind a tap into a separate screen.
 *
 * [onAddDevice] is null for the "not in a room" group — there is no room to add into.
 */
@Composable
fun RoomCard(
    label: String,
    devices: List<Device>,
    facts: Map<String, DeviceFacts>,
    onAddDevice: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tones = devices.map { device ->
        deviceStateTone(device, DeviceContext(facts = facts[device.id]))
    }
    val activeCount = tones.count { it == StateTone.ACTIVE }
    val needsAttention = tones.count { it == StateTone.FAULT || it == StateTone.ATTENTION }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    iconForRoom(label),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(start = Space.s)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(if (devices.size == 1) "1 device" else "${devices.size} devices")
                            if (devices.isNotEmpty()) {
                                append(if (activeCount == 0) " · all off" else " · $activeCount on")
                            }
                            if (needsAttention > 0) append(" · $needsAttention need attention")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (needsAttention > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onAddDevice != null) {
                    TextButton(onClick = onAddDevice) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add a device to $label",
                            modifier = Modifier.size(18.dp)
                        )
                        Text(" Add")
                    }
                }
            }

            if (devices.isEmpty()) {
                Text(
                    "No devices here yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider()
                content()
            }
        }
    }
}
