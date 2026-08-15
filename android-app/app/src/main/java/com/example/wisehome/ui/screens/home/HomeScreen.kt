@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wisehome.ui.screens.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wisehome.ui.components.EmptyState
import com.example.wisehome.ui.components.SectionCard
import com.example.wisehome.ui.theme.Space

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val floors by viewModel.floors.collectAsState()
    val selectedFloorId by viewModel.selectedFloorId.collectAsState()
    val selectedRoomLabel by viewModel.selectedRoomLabel.collectAsState()
    val devices by viewModel.devicesOnSelectedFloor.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val facts by viewModel.deviceFacts.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    val selectedIndex = floors.indexOfFirst { it.id == selectedFloorId }.coerceAtLeast(0)
    val currentFloor = floors.getOrNull(selectedIndex)
    val currentRoom = currentFloor
        ?.let { floor -> roomLayoutFor(floor).find { it.label == selectedRoomLabel } }

    BackHandler(enabled = currentRoom != null) { viewModel.selectRoom(null) }

    Scaffold(
        // The NavHost already applies the bottom-bar inset; without this the inset
        // is applied twice and leaves dead space at the bottom of every screen.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(currentRoom?.label ?: "WiseHome") },
                navigationIcon = {
                    if (currentRoom != null) {
                        IconButton(onClick = { viewModel.selectRoom(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                floors.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                currentFloor == null -> Unit

                currentRoom == null -> {
                    PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = Space.l) {
                        floors.forEachIndexed { index, floor ->
                            Tab(
                                selected = index == selectedIndex,
                                onClick = { viewModel.selectFloor(floor.id) },
                                text = { Text(floor.name) }
                            )
                        }
                    }
                    if (devices.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.HomeWork,
                            title = "No devices on this floor",
                            body = "Devices added to ${currentFloor.name} will appear here."
                        )
                    } else {
                        RoomListView(
                            floor = currentFloor,
                            devices = devices,
                            facts = facts,
                            onRoomClick = { viewModel.selectRoom(it.label) }
                        )
                    }
                }

                else -> {
                    val roomDevices = devices.filter { currentRoom.contains(it) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Space.l),
                        verticalArrangement = Arrangement.spacedBy(Space.m)
                    ) {
                        item {
                            SectionCard(
                                title = "Room map · ${currentRoom.cols}×${currentRoom.rows} grid"
                            ) {
                                RoomMap(
                                    room = currentRoom,
                                    devices = roomDevices,
                                    facts = facts,
                                    onDeviceClick = { viewModel.selectDevice(it.id) }
                                )
                            }
                        }

                        item {
                            Text(
                                text = if (roomDevices.size == 1) "1 device" else "${roomDevices.size} devices",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Space.s)
                            )
                        }

                        items(roomDevices, key = { it.id }) { device ->
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

    selectedDevice?.let { device ->
        DeviceDetailSheet(
            device = device,
            viewModel = viewModel,
            onDismiss = { viewModel.selectDevice(null) }
        )
    }
}
