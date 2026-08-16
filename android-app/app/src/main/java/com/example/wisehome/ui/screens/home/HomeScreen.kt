@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.Room
import com.example.wisehome.ui.components.EmptyState
import com.example.wisehome.ui.components.SectionCard
import com.example.wisehome.ui.theme.Space

/**
 * Home: pick a floor, see that floor.
 *
 * There used to be a room-cards screen between the floor tabs and the map. A house has
 * exactly one of each floor, so that was a menu in front of a menu — the floor map and
 * the floor's devices (grouped by room) are shown directly instead.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val floors by viewModel.floors.collectAsState()
    val selectedFloorId by viewModel.selectedFloorId.collectAsState()
    val devices by viewModel.devicesOnSelectedFloor.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val facts by viewModel.deviceFacts.collectAsState()
    val allRooms by viewModel.rooms.collectAsState()
    val editorError by viewModel.editorError.collectAsState()

    var addingDeviceToRoom by remember { mutableStateOf<Room?>(null) }
    var editingDevice by remember { mutableStateOf<Device?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    val selectedIndex = floors.indexOfFirst { it.id == selectedFloorId }.coerceAtLeast(0)
    val currentFloor = floors.getOrNull(selectedIndex)
    val floorRooms = currentFloor?.let { roomsForFloor(it, allRooms) }.orEmpty()

    // First match wins, so a device inside two overlapping rooms is listed once. What no
    // room covers goes to "Not in a room" rather than disappearing from the list.
    val devicesByRoom = remember(floorRooms, devices) {
        floorRooms.associateWith { room ->
            devices.filter { device -> floorRooms.firstOrNull { it.contains(device) } == room }
        }
    }
    val unplacedDevices = remember(floorRooms, devices) {
        devices.filter { device -> floorRooms.none { it.contains(device) } }
    }

    Scaffold(
        // The NavHost already applies the bottom-bar inset; without this the inset
        // is applied twice and leaves dead space at the bottom of every screen.
        contentWindowInsets = WindowInsets(0),
        topBar = { CenterAlignedTopAppBar(title = { Text("WiseHome") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                floors.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                currentFloor == null -> Unit

                else -> {
                    PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = Space.l) {
                        floors.forEachIndexed { index, floor ->
                            Tab(
                                selected = index == selectedIndex,
                                onClick = { viewModel.selectFloor(floor.id) },
                                text = { Text(floor.name) }
                            )
                        }
                    }

                    if (floorRooms.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.HomeWork,
                            title = "No rooms on this floor",
                            body = "Add rooms to ${currentFloor.name} in Settings → Floor plans, " +
                                "then add devices to them here."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(Space.l),
                            verticalArrangement = Arrangement.spacedBy(Space.s)
                        ) {
                            item {
                                SectionCard(
                                    title = "Floor map · ${currentFloor.gridCols}×${currentFloor.gridRows} grid"
                                ) {
                                    FloorMap(
                                        floor = currentFloor,
                                        rooms = floorRooms,
                                        devices = devices,
                                        facts = facts,
                                        floorPlanRes = floorPlanDrawable(currentFloor.imageUrl),
                                        onDeviceClick = { viewModel.selectDevice(it.id) }
                                    )
                                }
                            }

                            items(floorRooms, key = { "room-${it.id}" }) { room ->
                                val roomDevices = devicesByRoom[room].orEmpty()
                                RoomCard(
                                    label = room.label,
                                    devices = roomDevices,
                                    facts = facts,
                                    onAddDevice = { addingDeviceToRoom = room }
                                ) {
                                    roomDevices.forEach { device ->
                                        DeviceRow(
                                            device = device,
                                            facts = facts,
                                            onClick = { viewModel.selectDevice(device.id) },
                                            onToggle = { on -> viewModel.setDevicePower(device, on) }
                                        )
                                    }
                                }
                            }

                            if (unplacedDevices.isNotEmpty()) {
                                item(key = "room-unplaced") {
                                    RoomCard(
                                        label = "Not in a room",
                                        devices = unplacedDevices,
                                        facts = facts,
                                        onAddDevice = null
                                    ) {
                                        unplacedDevices.forEach { device ->
                                            DeviceRow(
                                                device = device,
                                                facts = facts,
                                                onClick = { viewModel.selectDevice(device.id) },
                                                onToggle = { on -> viewModel.setDevicePower(device, on) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedDevice?.let { device ->
        DeviceDetailSheet(
            device = device,
            viewModel = viewModel,
            onDismiss = { viewModel.selectDevice(null) },
            onEdit = { editingDevice = device }
        )
    }

    // Cells already taken on this floor — the editor refuses to stack two devices in
    // one cell, which would hide one of them behind the other on the map.
    val occupiedCells = remember(devices) { devices.map { it.gridX to it.gridY }.toSet() }

    addingDeviceToRoom?.let { room ->
        currentFloor?.let { floor ->
            DeviceEditorDialog(
                floor = floor,
                room = room,
                device = null,
                occupiedCells = occupiedCells,
                onDismiss = { addingDeviceToRoom = null },
                onSave = { draft ->
                    viewModel.addDevice(
                        floorId = floor.id,
                        name = draft.name,
                        type = draft.type,
                        gridX = draft.gridX,
                        gridY = draft.gridY,
                        sensorType = draft.sensorType,
                        switchCount = draft.switchCount,
                        safetyKind = draft.safetyKind,
                        applianceType = draft.applianceType
                    )
                    addingDeviceToRoom = null
                }
            )
        }
    }

    editingDevice?.let { device ->
        currentFloor?.let { floor ->
            DeviceEditorDialog(
                floor = floor,
                room = floorRooms.firstOrNull { it.contains(device) },
                device = device,
                occupiedCells = occupiedCells,
                onDismiss = { editingDevice = null },
                onSave = { draft ->
                    viewModel.updateDevice(
                        device = device,
                        name = draft.name,
                        gridX = draft.gridX,
                        gridY = draft.gridY,
                        floorId = floor.id,
                        applianceType = draft.applianceType ?: device.applianceType
                    )
                    editingDevice = null
                }
            )
        }
    }

    editorError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearEditorError,
            title = { Text("Couldn't save") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearEditorError) { Text("OK") } }
        )
    }
}
