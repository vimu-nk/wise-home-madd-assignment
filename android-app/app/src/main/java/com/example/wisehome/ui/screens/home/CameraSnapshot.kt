package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.formatRelative
import com.example.wisehome.ui.theme.Space

/**
 * The latest mock snapshot for a camera.
 *
 * The URL comes from `cameras.last_snapshot_url`, which the hardware simulator
 * rotates — and because that table is on Realtime, taking a new snapshot in the
 * simulator updates this image with no interaction on the phone.
 *
 * A camera reporting ERROR or DISCONNECTED shows the fault rather than a stale
 * frame: a picture from ten minutes ago on a dead camera is worse than no picture.
 */
@Composable
fun CameraSnapshot(device: Device, context: DeviceContext, modifier: Modifier = Modifier) {
    val camera = context.camera
    val url = camera?.lastSnapshotUrl
    val faulted = device.status == DeviceStatus.ERROR || device.status == DeviceStatus.DISCONNECTED
    var failed by remember(url) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            faulted -> SnapshotPlaceholder(
                icon = true,
                message = if (device.status == DeviceStatus.ERROR) "Camera reported a fault"
                else "Camera is disconnected"
            )

            url.isNullOrBlank() -> SnapshotPlaceholder(icon = false, message = "No snapshot yet")

            failed -> SnapshotPlaceholder(icon = false, message = "Snapshot could not be loaded")

            else -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = "Latest snapshot from ${device.name}",
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    camera?.lastSnapshotAt?.let { timestamp ->
        Text(
            "Snapshot taken ${formatRelative(timestamp)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SnapshotPlaceholder(icon: Boolean, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s)
    ) {
        Icon(
            if (icon) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
