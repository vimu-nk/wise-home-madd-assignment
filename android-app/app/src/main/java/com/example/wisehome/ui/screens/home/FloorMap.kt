package com.example.wisehome.ui.screens.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.model.Room
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.deviceDisplay

/**
 * The whole floor as one abstract grid: room regions outlined and named, every device
 * on the floor placed at its own cell.
 *
 * This replaces the previous per-room map. A house has one of each floor, so making the
 * user pick a floor and then pick a room was a menu in front of a menu; the floor is the
 * useful unit and the rooms are regions within it.
 */
@Composable
fun FloorMap(
    floor: Floor,
    rooms: List<Room>,
    devices: List<Device>,
    facts: Map<String, DeviceFacts>,
    onDeviceClick: (Device) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes floorPlanRes: Int? = null
) {
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant
    val roomBorder = MaterialTheme.colorScheme.outline
    val roomTint = MaterialTheme.colorScheme.primaryContainer
    val plan = floorPlanRes?.let { ImageBitmap.imageResource(it) }
    // The plans are drawn on light paper. At any real opacity they turn into a bright
    // slab in dark theme that swallows the device badges, so they stay a faint underlay
    // there and can be stronger on a light background.
    val planAlpha = if (isSystemInDarkTheme()) 0.14f else 0.4f
    val badgeBackground = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val cols = floor.gridCols.coerceAtLeast(1)
    val rows = floor.gridRows.coerceAtLeast(1)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val preferredCell = 64.dp
        val maxCell = maxWidth / cols
        val cell = if (maxCell < preferredCell) maxCell else preferredCell
        val gridWidth = cell * cols
        val gridHeight = cell * rows

        // Below this, a one-cell-wide label is all ellipsis and no word, so it is
        // dropped instead — the section list under the map still names every room.
        val labelsFit = cell >= 44.dp
        val labelStyle =
            if (cell < 48.dp) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelMedium

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(gridWidth, gridHeight)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cw = size.width / cols
                    val ch = size.height / rows

                    if (plan != null) {
                        drawImage(
                            image = plan,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(plan.width, plan.height),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            alpha = planAlpha
                        )
                    }

                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    for (col in 0..cols) {
                        drawLine(
                            gridLineColor,
                            Offset(col * cw, 0f),
                            Offset(col * cw, size.height),
                            pathEffect = dash
                        )
                    }
                    for (row in 0..rows) {
                        drawLine(
                            gridLineColor,
                            Offset(0f, row * ch),
                            Offset(size.width, row * ch),
                            pathEffect = dash
                        )
                    }

                    // Alternating fill strength: two rooms sharing an edge would
                    // otherwise read as one region at a glance.
                    rooms.forEachIndexed { index, room ->
                        val topLeft = Offset(room.x0 * cw, room.y0 * ch)
                        val roomSize = Size(room.cols * cw, room.rows * ch)
                        drawRoundRect(
                            color = roomTint.copy(alpha = if (index % 2 == 0) 0.30f else 0.18f),
                            topLeft = topLeft,
                            size = roomSize,
                            cornerRadius = CornerRadius(10f, 10f)
                        )
                        drawRoundRect(
                            color = roomBorder,
                            topLeft = topLeft,
                            size = roomSize,
                            cornerRadius = CornerRadius(10f, 10f),
                            style = Stroke(width = 2.5f)
                        )
                    }
                }

                // Labels as real Text, not canvas drawing, so Compose does the
                // ellipsising: constrained to the room's own width, a long name can
                // never bleed into the room next door.
                if (labelsFit) {
                    rooms.forEach { room ->
                        // A region covering the whole grid is the "no rooms yet"
                        // fallback, and its name is the floor's — which the selected tab
                        // already states. Tagging it adds nothing.
                        if (room.cols >= cols && room.rows >= rows) return@forEach
                        Text(
                            text = room.label,
                            style = labelStyle,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .offset(x = cell * room.x0, y = cell * room.y0)
                                .width(cell * room.cols)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // Devices last, so nothing is drawn over them. Their cells are
                // unchanged — the badge is slightly smaller than before to leave the
                // label band clear, rather than being nudged out of alignment.
                devices.forEach { device ->
                    val ctx = DeviceContext(facts = facts[device.id])
                    val display = deviceDisplay(device, ctx)
                    val tone = colorForTone(display.tone)
                    // A device in the top row of its room shares that row with the room
                    // label. Rather than let the two overlap, the badge sits low in its
                    // own cell — it stays in the same cell, so the grid still reads
                    // straight across.
                    val sharesRowWithLabel = labelsFit &&
                        rooms.firstOrNull { it.contains(device) }?.y0 == device.gridY
                    Box(
                        modifier = Modifier
                            .offset(x = cell * device.gridX, y = cell * device.gridY)
                            .size(cell)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onDeviceClick(device) },
                        contentAlignment =
                            if (sharesRowWithLabel) Alignment.BottomCenter else Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = if (sharesRowWithLabel) cell * 0.06f else 0.dp)
                                .size(cell * 0.62f)
                                .background(badgeBackground, CircleShape)
                                .border(2.dp, tone, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconForDevice(device, ctx),
                                contentDescription = "${device.name}, ${display.stateLabel}",
                                tint = tone,
                                modifier = Modifier.size(cell * 0.34f)
                            )
                        }
                    }
                }
            }
        }
    }
}
