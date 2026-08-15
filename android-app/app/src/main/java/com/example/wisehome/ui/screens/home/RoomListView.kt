package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.data.model.Room
import com.example.wisehome.ui.components.StatePill
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.StateTone
import com.example.wisehome.ui.format.deviceStateTone
import com.example.wisehome.ui.theme.Space

/**
 * One card per room. Cards size to their content — the old fixed 88dp inner
 * column forced a large blank band into every card regardless of what was in it.
 */
@Composable
fun RoomListView(
    rooms: List<Room>,
    devices: List<Device>,
    facts: Map<String, DeviceFacts>,
    onRoomClick: (Room) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m)
    ) {
        items(rooms, key = { it.label }) { room ->
            RoomCard(
                room = room,
                devices = devices.filter { room.contains(it) },
                facts = facts,
                onClick = { onRoomClick(room) },
                // Each grid row is as tall as its tallest card; this makes the
                // shorter ones match instead of leaving ragged gaps.
                modifier = Modifier.fillMaxHeight()
            )
        }
    }
}

@Composable
private fun RoomCard(
    room: Room,
    devices: List<Device>,
    facts: Map<String, DeviceFacts>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tones = devices.map { device ->
        deviceStateTone(device, DeviceContext(facts = facts[device.id]))
    }
    val activeCount = tones.count { it == StateTone.ACTIVE }
    val needsAttention = tones.count { it == StateTone.FAULT || it == StateTone.ATTENTION }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconForRoom(room.label),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    room.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Space.s)
                )
            }

            Text(
                text = when {
                    devices.isEmpty() -> "No devices"
                    activeCount == 0 -> "${devices.size} devices · all off"
                    else -> "${devices.size} devices · $activeCount on"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Keeps the status strip aligned along the bottom of every card in a row.
            Spacer(Modifier.weight(1f))

            // Live status strip — one dot per device, so the card shows the room's
            // real state at a glance instead of padding out empty space.
            if (tones.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tones.take(6).forEach { tone ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(colorForTone(tone), CircleShape)
                        )
                    }
                    if (tones.size > 6) {
                        Text(
                            "+${tones.size - 6}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (needsAttention > 0) {
                StatePill(
                    label = if (needsAttention == 1) "1 needs attention" else "$needsAttention need attention",
                    tone = StateTone.ATTENTION
                )
            }
        }
    }
}
