@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wisehome.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.model.Room
import com.example.wisehome.ui.components.SectionCard
import com.example.wisehome.ui.theme.Space

/**
 * Floor plans a user can actually manage: add, rename, resize, delete, and edit the
 * rooms within each one.
 *
 * Rooms are inclusive grid rectangles, the same representation the dashboard draws
 * from, so anything created here appears on the Home tab immediately via Realtime.
 */
@Composable
fun FloorManagementSection(
    floors: List<Floor>,
    rooms: List<Room>,
    onAddFloor: (name: String, imageUrl: String, cols: Int, rows: Int) -> Unit,
    onUpdateFloor: (Floor, name: String, imageUrl: String, cols: Int, rows: Int) -> Unit,
    onDeleteFloor: (Floor) -> Unit,
    onAddRoom: (floorId: String, label: String, x0: Int, y0: Int, x1: Int, y1: Int) -> Unit,
    onUpdateRoom: (Room, label: String, x0: Int, y0: Int, x1: Int, y1: Int) -> Unit,
    onDeleteRoom: (Room) -> Unit
) {
    var floorEditor by remember { mutableStateOf<FloorEditorTarget?>(null) }
    var roomEditor by remember { mutableStateOf<RoomEditorTarget?>(null) }
    var confirmFloorDelete by remember { mutableStateOf<Floor?>(null) }

    SectionCard(title = "Floor plans") {
        floors.forEach { floor ->
            val floorRooms = rooms.filter { it.floorId == floor.id }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(floor.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${floor.gridCols}×${floor.gridRows} grid · ${floorRooms.size} rooms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { floorEditor = FloorEditorTarget(floor) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${floor.name}")
                }
                IconButton(onClick = { confirmFloorDelete = floor }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${floor.name}")
                }
            }

            floorRooms.forEach { room ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = Space.l)
                ) {
                    Text(
                        "${room.label}  (${room.x0},${room.y0})–(${room.x1},${room.y1})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { roomEditor = RoomEditorTarget(floor, room) }) { Text("Edit") }
                    TextButton(onClick = { onDeleteRoom(room) }) { Text("Remove") }
                }
            }

            TextButton(
                onClick = { roomEditor = RoomEditorTarget(floor, null) },
                modifier = Modifier.padding(start = Space.s)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Add room to ${floor.name}")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Space.s))
        }

        Button(
            onClick = { floorEditor = FloorEditorTarget(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  Add floor")
        }
    }

    floorEditor?.let { target ->
        FloorEditorDialog(
            floor = target.floor,
            onDismiss = { floorEditor = null },
            onSave = { name, imageUrl, cols, rows ->
                if (target.floor == null) onAddFloor(name, imageUrl, cols, rows)
                else onUpdateFloor(target.floor, name, imageUrl, cols, rows)
                floorEditor = null
            }
        )
    }

    roomEditor?.let { target ->
        RoomEditorDialog(
            floor = target.floor,
            room = target.room,
            onDismiss = { roomEditor = null },
            onSave = { label, x0, y0, x1, y1 ->
                if (target.room == null) onAddRoom(target.floor.id, label, x0, y0, x1, y1)
                else onUpdateRoom(target.room, label, x0, y0, x1, y1)
                roomEditor = null
            }
        )
    }

    confirmFloorDelete?.let { floor ->
        val deviceWarning = "Its rooms and every device on it are deleted too. This cannot be undone."
        AlertDialog(
            onDismissRequest = { confirmFloorDelete = null },
            title = { Text("Delete ${floor.name}?") },
            text = { Text(deviceWarning) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFloor(floor)
                    confirmFloorDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFloorDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private data class FloorEditorTarget(val floor: Floor?)
private data class RoomEditorTarget(val floor: Floor, val room: Room?)

/** Plans bundled with the app; `image_url` stores the file name only. */
private val BUNDLED_PLANS = listOf(
    "floorplans/ground.png" to "Ground floor plan",
    "floorplans/first.png" to "Upper floor plan",
    "floorplans/exterior.png" to "Exterior / garden plan"
)

@Composable
private fun FloorEditorDialog(
    floor: Floor?,
    onDismiss: () -> Unit,
    onSave: (name: String, imageUrl: String, cols: Int, rows: Int) -> Unit
) {
    var name by remember { mutableStateOf(floor?.name ?: "") }
    var imageUrl by remember { mutableStateOf(floor?.imageUrl ?: BUNDLED_PLANS.first().first) }
    var cols by remember { mutableStateOf((floor?.gridCols ?: 6).toString()) }
    var rows by remember { mutableStateOf((floor?.gridRows ?: 5).toString()) }
    var planMenuOpen by remember { mutableStateOf(false) }

    val colsValue = cols.toIntOrNull()
    val rowsValue = rows.toIntOrNull()
    // Upper bound keeps a typo like "60" from producing an unusable grid.
    val valid = name.isNotBlank() && colsValue in 1..12 && rowsValue in 1..12

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (floor == null) "Add floor" else "Edit floor") },
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

                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    OutlinedTextField(
                        value = cols,
                        onValueChange = { cols = it.filter(Char::isDigit).take(2) },
                        label = { Text("Columns") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp)
                    )
                    OutlinedTextField(
                        value = rows,
                        onValueChange = { rows = it.filter(Char::isDigit).take(2) },
                        label = { Text("Rows") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp)
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = planMenuOpen,
                    onExpandedChange = { planMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = BUNDLED_PLANS.find { it.first == imageUrl }?.second ?: "No plan image",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Floor plan image") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = planMenuOpen) },
                        modifier = Modifier.menuAnchor(
                            androidx.compose.material3.MenuAnchorType.PrimaryNotEditable
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = planMenuOpen,
                        onDismissRequest = { planMenuOpen = false }
                    ) {
                        BUNDLED_PLANS.forEach { (url, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    imageUrl = url
                                    planMenuOpen = false
                                }
                            )
                        }
                    }
                }

                if (!valid) {
                    Text(
                        "Name is required; grid must be between 1 and 12 in each direction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(name, imageUrl, colsValue!!, rowsValue!!) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RoomEditorDialog(
    floor: Floor,
    room: Room?,
    onDismiss: () -> Unit,
    onSave: (label: String, x0: Int, y0: Int, x1: Int, y1: Int) -> Unit
) {
    var label by remember { mutableStateOf(room?.label ?: "") }
    var x0 by remember { mutableStateOf((room?.x0 ?: 0).toString()) }
    var y0 by remember { mutableStateOf((room?.y0 ?: 0).toString()) }
    var x1 by remember { mutableStateOf((room?.x1 ?: 0).toString()) }
    var y1 by remember { mutableStateOf((room?.y1 ?: 0).toString()) }

    val xs = listOf(x0, x1).map { it.toIntOrNull() }
    val ys = listOf(y0, y1).map { it.toIntOrNull() }
    // Rectangles must stay inside the floor's grid, otherwise the room renders
    // cells that no device can ever occupy.
    val valid = label.isNotBlank() &&
        xs.all { it != null && it in 0 until floor.gridCols } &&
        ys.all { it != null && it in 0 until floor.gridRows }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (room == null) "Add room" else "Edit room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    "${floor.name} · grid is ${floor.gridCols}×${floor.gridRows}, " +
                        "so columns are 0–${floor.gridCols - 1} and rows 0–${floor.gridRows - 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Room name") },
                    singleLine = true
                )

                Text("Top-left cell", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    GridField("Column", x0) { x0 = it }
                    GridField("Row", y0) { y0 = it }
                }

                Text("Bottom-right cell", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    GridField("Column", x1) { x1 = it }
                    GridField("Row", y1) { y1 = it }
                }

                if (!valid) {
                    Text(
                        "Name is required and every cell must be inside the grid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(label, xs[0]!!, ys[0]!!, xs[1]!!, ys[1]!!) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GridField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(2)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(110.dp)
    )
}
