@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.model.Room
import com.example.wisehome.data.model.SensorType
import com.example.wisehome.ui.theme.Space

/** What the user picked in the editor; the ViewModel turns it into rows. */
data class DeviceDraft(
    val name: String,
    val type: DeviceType,
    val gridX: Int,
    val gridY: Int,
    val sensorType: SensorType,
    val switchCount: Int,
    val safetyKind: String,
    val applianceType: String?
)

private val TYPE_LABELS = listOf(
    DeviceType.OUTLET to "Outlet",
    DeviceType.MULTISWITCH to "Multi-switch unit",
    DeviceType.SCHEDULED_LIGHT to "Scheduled light",
    DeviceType.SCHEDULED_SAFETY to "Safety-capped appliance",
    DeviceType.CAMERA to "Security camera",
    DeviceType.SENSOR to "Sensor",
    DeviceType.SMART_LOCK to "Smart lock",
    DeviceType.THERMOSTAT to "Thermostat",
    DeviceType.AC_UNIT to "Air conditioner",
    DeviceType.SMART_PLUG_METERED to "Metered plug",
    DeviceType.APPLIANCE to "Appliance"
)

private val SENSOR_LABELS = listOf(
    SensorType.MOTION to "Motion",
    SensorType.DOOR_WINDOW to "Door / window",
    SensorType.SMOKE to "Smoke",
    SensorType.GAS to "Gas",
    SensorType.WATER_LEAK to "Water leak"
)

private val SAFETY_KINDS = listOf(
    "iron" to "Iron",
    "hair_dryer" to "Hair dryer",
    "space_heater" to "Space heater",
    "water_heater" to "Water heater"
)

private val SWITCH_COUNTS = listOf(2, 3, 5)

/**
 * Add or edit a device.
 *
 * Type is fixed once created: changing it would orphan the extension row (a camera's
 * snapshot, a multiswitch's switches) and there is no sensible migration between them.
 * Delete and re-add instead.
 */
@Composable
fun DeviceEditorDialog(
    floor: Floor,
    room: Room?,
    device: Device?,
    occupiedCells: Set<Pair<Int, Int>>,
    onDismiss: () -> Unit,
    onSave: (DeviceDraft) -> Unit
) {
    var name by remember { mutableStateOf(device?.name ?: "") }
    var type by remember { mutableStateOf(device?.type ?: DeviceType.OUTLET) }
    var sensorType by remember { mutableStateOf(SensorType.MOTION) }
    var switchCount by remember { mutableStateOf(2) }
    var safetyKind by remember { mutableStateOf("iron") }
    var applianceType by remember { mutableStateOf(device?.applianceType ?: "") }
    var typeMenuOpen by remember { mutableStateOf(false) }

    // A new device defaults to the first free cell in the room being viewed, so the
    // common case needs no coordinate typing at all.
    val defaultCell = remember(room, occupiedCells) {
        room?.let { r ->
            (r.y0..r.y1).flatMap { y -> (r.x0..r.x1).map { x -> x to y } }
                .firstOrNull { it !in occupiedCells }
        } ?: (0 to 0)
    }
    var gridX by remember { mutableStateOf((device?.gridX ?: defaultCell.first).toString()) }
    var gridY by remember { mutableStateOf((device?.gridY ?: defaultCell.second).toString()) }

    val x = gridX.toIntOrNull()
    val y = gridY.toIntOrNull()
    val cellFree = x != null && y != null &&
        ((x to y) == (device?.gridX to device?.gridY) || (x to y) !in occupiedCells)
    val inGrid = x != null && y != null && x in 0 until floor.gridCols && y in 0 until floor.gridRows
    val valid = name.isNotBlank() && inGrid && cellFree

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (device == null) "Add device" else "Edit ${device.name}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Space.m),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )

                if (device == null) {
                    ExposedDropdownMenuBox(
                        expanded = typeMenuOpen,
                        onExpandedChange = { typeMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = TYPE_LABELS.first { it.first == type }.second,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Type") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen)
                            },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = typeMenuOpen,
                            onDismissRequest = { typeMenuOpen = false }
                        ) {
                            TYPE_LABELS.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        type = value
                                        typeMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "Type: ${TYPE_LABELS.first { it.first == device.type }.second} " +
                            "(fixed — delete and re-add to change it)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Type-specific setup, only for new devices: these choices create the
                // extension row and cannot be re-applied afterwards without rebuilding it.
                if (device == null) {
                    when (type) {
                        DeviceType.SENSOR -> ChipRow("Sensor type", SENSOR_LABELS, sensorType) {
                            sensorType = it
                        }
                        DeviceType.MULTISWITCH -> ChipRow(
                            "Switches",
                            SWITCH_COUNTS.map { it to "$it" },
                            switchCount
                        ) { switchCount = it }
                        DeviceType.SCHEDULED_SAFETY -> ChipRow("Appliance", SAFETY_KINDS, safetyKind) {
                            safetyKind = it
                        }
                        DeviceType.APPLIANCE -> OutlinedTextField(
                            value = applianceType,
                            onValueChange = { applianceType = it },
                            label = { Text("Appliance kind (tv, fridge, fan…)") },
                            singleLine = true
                        )
                        else -> Unit
                    }
                }

                Text(
                    "Position on ${floor.name} · columns 0–${floor.gridCols - 1}, " +
                        "rows 0–${floor.gridRows - 1}" +
                        (room?.let { " · ${it.label} covers (${it.x0},${it.y0})–(${it.x1},${it.y1})" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    OutlinedTextField(
                        value = gridX,
                        onValueChange = { gridX = it.filter(Char::isDigit).take(2) },
                        label = { Text("Column") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp)
                    )
                    OutlinedTextField(
                        value = gridY,
                        onValueChange = { gridY = it.filter(Char::isDigit).take(2) },
                        label = { Text("Row") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp)
                    )
                }

                if (!valid) {
                    Text(
                        when {
                            name.isBlank() -> "Name is required."
                            !inGrid -> "That cell is outside this floor's grid."
                            else -> "Another device already occupies that cell."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        DeviceDraft(
                            name = name,
                            type = device?.type ?: type,
                            gridX = x!!,
                            gridY = y!!,
                            sensorType = sensorType,
                            switchCount = switchCount,
                            safetyKind = safetyKind,
                            applianceType = applianceType.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) }
                )
            }
        }
    }
}
