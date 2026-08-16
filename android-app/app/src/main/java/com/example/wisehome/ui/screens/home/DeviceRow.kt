package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.ui.components.ControlRow
import com.example.wisehome.ui.components.DeviceIconBadge
import com.example.wisehome.ui.components.RowChevron
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.deviceDisplay

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
                // Cameras show their latest frame in place of the generic icon — it
                // updates live as the simulator rotates snapshots.
                val snapshotUrl = ctx.facts?.cameraSnapshotUrl
                if (device.type == DeviceType.CAMERA && !snapshotUrl.isNullOrBlank() &&
                    device.status != DeviceStatus.ERROR && device.status != DeviceStatus.DISCONNECTED
                ) {
                    AsyncImage(
                        model = snapshotUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(MaterialTheme.shapes.small)
                    )
                } else {
                    DeviceIconBadge(
                        icon = iconForDevice(device, ctx),
                        tint = tint,
                        contentDescription = null
                    )
                }
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
