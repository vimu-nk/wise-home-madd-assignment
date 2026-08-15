package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.Iron
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Microwave
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.data.model.LockMechanism
import com.example.wisehome.data.model.SensorType
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.StateTone
import com.example.wisehome.ui.format.deviceStateTone
import com.example.wisehome.ui.theme.StatusDisconnectedDark
import com.example.wisehome.ui.theme.StatusDisconnectedLight
import com.example.wisehome.ui.theme.StatusErrorDark
import com.example.wisehome.ui.theme.StatusErrorLight
import com.example.wisehome.ui.theme.StatusOffDark
import com.example.wisehome.ui.theme.StatusOffLight
import com.example.wisehome.ui.theme.StatusOnDark
import com.example.wisehome.ui.theme.StatusOnLight
import com.example.wisehome.ui.theme.SecondaryDark
import com.example.wisehome.ui.theme.SecondaryLight

/** Icon chosen from the device's real hardware, not just its coarse type. */
fun iconForDevice(device: Device, ctx: DeviceContext = DeviceContext.Empty): ImageVector =
    when (device.type) {
        DeviceType.SMART_LOCK -> when (ctx.lock?.mechanism ?: ctx.facts?.lockMechanism) {
            LockMechanism.SLIDING_GATE -> Icons.Filled.Fence
            LockMechanism.TURNSTILE -> Icons.Filled.DoorFront
            else -> Icons.Filled.Lock
        }
        DeviceType.SENSOR -> when (ctx.sensor?.sensorType ?: ctx.facts?.sensorType) {
            SensorType.DOOR_WINDOW -> Icons.Filled.DoorFront
            SensorType.SMOKE -> Icons.Filled.LocalFireDepartment
            SensorType.GAS -> Icons.Filled.Whatshot
            SensorType.WATER_LEAK -> Icons.Filled.WaterDrop
            else -> Icons.Filled.Sensors
        }
        DeviceType.APPLIANCE -> when (device.applianceType?.lowercase()) {
            "tv" -> Icons.Filled.Tv
            "washing_machine" -> Icons.Filled.LocalLaundryService
            "microwave" -> Icons.Filled.Microwave
            "fan" -> Icons.Filled.Air
            "water_heater" -> Icons.Filled.Whatshot
            else -> Icons.Filled.Kitchen
        }
        DeviceType.OUTLET -> Icons.Filled.Power
        DeviceType.MULTISWITCH -> Icons.Filled.ToggleOn
        DeviceType.SCHEDULED_SAFETY -> Icons.Filled.Iron
        DeviceType.SCHEDULED_LIGHT -> Icons.Filled.Lightbulb
        DeviceType.CAMERA -> Icons.Filled.Videocam
        DeviceType.THERMOSTAT -> Icons.Filled.Thermostat
        DeviceType.AC_UNIT -> Icons.Filled.AcUnit
        DeviceType.SMART_PLUG_METERED -> Icons.Filled.ElectricalServices
    }

@Composable
fun colorForTone(tone: StateTone): Color {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        StateTone.ACTIVE -> if (dark) StatusOnDark else StatusOnLight
        StateTone.IDLE -> if (dark) StatusOffDark else StatusOffLight
        StateTone.ATTENTION -> if (dark) SecondaryDark else SecondaryLight
        StateTone.FAULT -> if (dark) StatusErrorDark else StatusErrorLight
        StateTone.OFFLINE -> if (dark) StatusDisconnectedDark else StatusDisconnectedLight
    }
}

@Composable
fun colorForDevice(device: Device, ctx: DeviceContext = DeviceContext.Empty): Color =
    colorForTone(deviceStateTone(device, ctx))

@Composable
fun colorForStatus(status: DeviceStatus): Color = colorForTone(
    when (status) {
        DeviceStatus.ON -> StateTone.ACTIVE
        DeviceStatus.OFF -> StateTone.IDLE
        DeviceStatus.ERROR -> StateTone.FAULT
        DeviceStatus.DISCONNECTED -> StateTone.OFFLINE
    }
)
