@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wisehome.data.model.ControlMode
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.data.model.FanSpeed
import com.example.wisehome.data.model.SafetyConfig
import com.example.wisehome.data.model.ThermostatMode
import com.example.wisehome.ui.components.ControlRow
import com.example.wisehome.ui.components.DeviceIconBadge
import com.example.wisehome.ui.components.SectionCard
import com.example.wisehome.ui.components.StatePill
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.controlModeLabel
import com.example.wisehome.ui.format.deviceDisplay
import com.example.wisehome.ui.format.fanSpeedLabel
import com.example.wisehome.ui.format.formatDuration
import com.example.wisehome.ui.format.formatRelative
import com.example.wisehome.ui.format.formatTemp
import com.example.wisehome.ui.format.parseTimestamp
import com.example.wisehome.ui.format.thermostatModeLabel
import com.example.wisehome.ui.format.triggeredByLabel
import com.example.wisehome.ui.format.usageEventLabel
import com.example.wisehome.ui.theme.Space
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun DeviceDetailSheet(device: Device, viewModel: HomeViewModel, onDismiss: () -> Unit) {
    val extrasState by viewModel.deviceExtras.collectAsState()
    val facts by viewModel.deviceFacts.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val loading = extrasState is DeviceExtrasState.Loading
    val baseContext = (extrasState as? DeviceExtrasState.Ready)?.context ?: DeviceContext.Empty
    val context = baseContext.copy(facts = baseContext.facts ?: facts[device.id])

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.l)
        ) {
            DeviceHeader(device, context)

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.l),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            } else {
                DeviceControls(device, context, viewModel)
            }

            MetaSection(device, viewModel)
            HorizontalDivider()
            UsageHistorySection(device, context, viewModel)
        }
    }
}

@Composable
private fun DeviceHeader(device: Device, context: DeviceContext) {
    val display = deviceDisplay(device, context)
    Row(verticalAlignment = Alignment.CenterVertically) {
        DeviceIconBadge(
            icon = iconForDevice(device, context),
            tint = colorForTone(display.tone),
            contentDescription = null,
            size = 48.dp
        )
        Column(modifier = Modifier.weight(1f).padding(start = Space.m)) {
            Text(device.name, style = MaterialTheme.typography.titleLarge)
            Text(
                display.typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatePill(display.stateLabel, display.tone)
    }
    display.detail?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceControls(device: Device, context: DeviceContext, viewModel: HomeViewModel) {
    val display = deviceDisplay(device, context)

    when (device.type) {
        DeviceType.SENSOR, DeviceType.CAMERA -> {
            SectionCard(title = "Reading") {
                Text(display.stateLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    display.detail ?: "This device reports its state; it has no manual control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DeviceType.SMART_LOCK -> {
            val locked = device.status == DeviceStatus.ON
            SectionCard(title = "Control") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = locked,
                        onClick = { viewModel.setLockState(device.id, true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(display.onVerb ?: "Lock") }
                    SegmentedButton(
                        selected = !locked,
                        onClick = { viewModel.setLockState(device.id, false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(display.offVerb ?: "Unlock") }
                }
            }
        }

        DeviceType.MULTISWITCH -> {
            val switches = context.switches
            SectionCard(title = display.stateLabel) {
                if (switches.isEmpty()) {
                    Text(
                        "No switches linked to this panel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    switches.forEachIndexed { index, sw ->
                        ControlRow(title = sw.label ?: "Switch ${sw.switchIndex}") {
                            Switch(
                                checked = sw.status == DeviceStatus.ON,
                                onCheckedChange = {
                                    viewModel.toggleSwitch(device.id, sw.id, sw.status)
                                }
                            )
                        }
                        if (index != switches.lastIndex) HorizontalDivider()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        TextButton(onClick = { viewModel.setAllSwitches(device.id, true) }) {
                            Text("All on")
                        }
                        TextButton(onClick = { viewModel.setAllSwitches(device.id, false) }) {
                            Text("All off")
                        }
                    }
                }
            }
        }

        DeviceType.SCHEDULED_SAFETY -> {
            SectionCard(title = "Control") {
                ControlRow(
                    title = if (device.status == DeviceStatus.ON) "Heating" else "Off",
                    subtitle = "Switches itself off automatically for safety"
                ) {
                    Switch(
                        checked = device.status == DeviceStatus.ON,
                        onCheckedChange = { viewModel.setDevicePower(device, it) }
                    )
                }
                context.safety?.let { SafetyTimer(it, device.status == DeviceStatus.ON) }
            }
        }

        DeviceType.THERMOSTAT, DeviceType.AC_UNIT -> ClimateControls(device, context, viewModel)

        else -> {
            SectionCard(title = "Control") {
                ControlRow(title = display.stateLabel, subtitle = display.detail) {
                    Switch(
                        checked = device.status == DeviceStatus.ON,
                        onCheckedChange = { viewModel.setDevicePower(device, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyTimer(config: SafetyConfig, running: Boolean) {
    if (!running || config.turnedOnAt == null) {
        Text(
            "Turn on to start the ${config.maxOnDurationSeconds / 60}-minute safety timer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val startedAt = parseTimestamp(config.turnedOnAt)
    if (startedAt == null) {
        Text(
            "Timer unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    var remaining by remember(config.turnedOnAt) {
        mutableStateOf(remainingSeconds(startedAt, config.maxOnDurationSeconds))
    }
    LaunchedEffect(config.turnedOnAt) {
        while (remaining > 0) {
            delay(1_000)
            remaining = remainingSeconds(startedAt, config.maxOnDurationSeconds)
        }
    }

    val fraction = if (config.maxOnDurationSeconds <= 0) 0f
    else (remaining.toFloat() / config.maxOnDurationSeconds).coerceIn(0f, 1f)

    Text(
        if (remaining > 0) "Auto-off in ${formatDuration(remaining)}"
        else "Switching off now for safety…",
        style = MaterialTheme.typography.bodyMedium
    )
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
}

private fun remainingSeconds(startedAt: Instant, maxSeconds: Int): Long {
    val elapsed = Duration.between(startedAt, Instant.now()).seconds
    return (maxSeconds - elapsed).coerceAtLeast(0)
}

@Composable
private fun ClimateControls(device: Device, context: DeviceContext, viewModel: HomeViewModel) {
    val thermostat = context.thermostat
    val acUnit = context.acUnit
    val acDevice = context.acDevice

    if (thermostat == null && acUnit == null) {
        SectionCard(title = "Climate") {
            Text(
                "No thermostat or AC unit is linked to this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (thermostat != null) {
        SectionCard(title = "Target temperature") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = {
                        viewModel.setThermostatTarget(thermostat.deviceId, thermostat.targetTempC - 0.5)
                    },
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Filled.Remove, contentDescription = "Lower target temperature") }

                Text(
                    formatTemp(thermostat.targetTempC),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(140.dp)
                )

                FilledTonalIconButton(
                    onClick = {
                        viewModel.setThermostatTarget(thermostat.deviceId, thermostat.targetTempC + 0.5)
                    },
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Filled.Add, contentDescription = "Raise target temperature") }
            }

            Text("Mode", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                ThermostatMode.entries.forEach { mode ->
                    FilterChip(
                        selected = thermostat.mode == mode,
                        onClick = {
                            viewModel.setThermostatMode(thermostat.deviceId, acDevice?.id, mode)
                        },
                        label = { Text(thermostatModeLabel(mode)) }
                    )
                }
            }
        }
    }

    if (acUnit != null && acDevice != null) {
        SectionCard(title = "Air conditioner") {
            Text("Fan speed", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FanSpeed.entries.forEach { fan ->
                    FilterChip(
                        selected = acUnit.fanSpeed == fan,
                        onClick = { viewModel.setAcFanSpeed(acDevice.id, fan) },
                        label = { Text(fanSpeedLabel(fan)) }
                    )
                }
            }
            acUnit.currentTempC?.let {
                Text(
                    "Room temperature ${formatTemp(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MetaSection(device: Device, viewModel: HomeViewModel) {
    SectionCard(title = "Settings") {
        val auto = device.controlMode == ControlMode.AUTO
        Text("How it's controlled", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !auto,
                onClick = { viewModel.setControlMode(device.id, ControlMode.MANUAL) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text(controlModeLabel(ControlMode.MANUAL)) }
            SegmentedButton(
                selected = auto,
                onClick = { viewModel.setControlMode(device.id, ControlMode.AUTO) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text(controlModeLabel(ControlMode.AUTO)) }
        }
        Text(
            "Position on the floor grid: column ${device.gridX + 1}, row ${device.gridY + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UsageHistorySection(
    device: Device,
    context: DeviceContext,
    viewModel: HomeViewModel
) {
    val visible by viewModel.usageHistoryVisible.collectAsState()
    val history by viewModel.usageHistory.collectAsState()
    var showAll by remember(device.id) { mutableStateOf(false) }

    TextButton(onClick = { viewModel.toggleUsageHistory() }) {
        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s))
        Text(if (visible) "Hide activity" else "View activity")
    }

    if (!visible) return

    if (history.isEmpty()) {
        Text(
            "Nothing recorded yet. Actions you take will show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val shown = if (showAll) history else history.take(10)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        shown.forEach { log ->
            ControlRow(
                title = usageEventLabel(log.eventType, context),
                subtitle = formatRelative(log.createdAt)
            ) {
                Text(
                    triggeredByLabel(log.triggeredBy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!showAll && history.size > 10) {
            TextButton(onClick = { showAll = true }) {
                Text("Show all ${history.size} events")
            }
        }
    }
}
