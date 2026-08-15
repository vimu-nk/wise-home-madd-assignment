package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.ui.components.ControlRow
import com.example.wisehome.ui.components.DeviceIconBadge
import com.example.wisehome.ui.components.RowChevron
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.deviceDisplay
import com.example.wisehome.ui.theme.Space

/**
 * The abstract grid overlay required by the assignment, sized sensibly.
 *
 * The previous version used `aspectRatio(cols/rows)`, which produced a very short
 * full-width band for a 2x1 room (with the rest of the screen blank beneath) and a
 * tall narrow column for a 1x4 room. Fixed-size cells, centred, keep the map
 * exactly as large as the room needs.
 */
@Composable
fun RoomMap(
    room: Room,
    devices: List<Device>,
    facts: Map<String, DeviceFacts>,
    onDeviceClick: (Device) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant
    val roomFill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val preferredCell = 64.dp
        val maxCell = maxWidth / room.cols
        val cell = if (maxCell < preferredCell) maxCell else preferredCell
        val gridWidth = cell * room.cols
        val gridHeight = cell * room.rows

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(gridWidth, gridHeight)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cw = size.width / room.cols
                    val ch = size.height / room.rows

                    drawRoundRect(
                        color = roomFill,
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(16f, 16f)
                    )

                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    for (col in 0..room.cols) {
                        drawLine(
                            gridLineColor,
                            Offset(col * cw, 0f),
                            Offset(col * cw, size.height),
                            pathEffect = dash
                        )
                    }
                    for (row in 0..room.rows) {
                        drawLine(
                            gridLineColor,
                            Offset(0f, row * ch),
                            Offset(size.width, row * ch),
                            pathEffect = dash
                        )
                    }
                }

                devices.forEach { device ->
                    val ctx = DeviceContext(facts = facts[device.id])
                    val display = deviceDisplay(device, ctx)
                    Box(
                        modifier = Modifier
                            .offset(
                                x = cell * (device.gridX - room.x0),
                                y = cell * (device.gridY - room.y0)
                            )
                            .size(cell)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onDeviceClick(device) },
                        contentAlignment = Alignment.Center
                    ) {
                        DeviceIconBadge(
                            icon = iconForDevice(device, ctx),
                            tint = colorForTone(display.tone),
                            contentDescription = "${device.name}, ${display.stateLabel}",
                            size = cell * 0.66f
                        )
                    }
                }
            }
        }
    }
}

/** A device as a list row — the primary way to control things in a room. */
@Composable
fun DeviceRow(
    device: Device,
    facts: Map<String, DeviceFacts>,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = DeviceContext(facts = facts[device.id])
    val display = deviceDisplay(device, ctx)
    val tint = colorForTone(display.tone)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onClick() }
    ) {
        ControlRow(
            title = device.name,
            subtitle = display.stateLabel,
            leading = {
                DeviceIconBadge(
                    icon = iconForDevice(device, ctx),
                    tint = tint,
                    contentDescription = null
                )
            }
        ) {
            if (display.isControllable) {
                Switch(
                    checked = device.status == DeviceStatus.ON,
                    onCheckedChange = onToggle
                )
            } else {
                RowChevron()
            }
        }
    }
}
