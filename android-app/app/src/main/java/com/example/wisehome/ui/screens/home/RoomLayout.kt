package com.example.wisehome.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balcony
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.model.Room

/**
 * Rooms for a floor, taken from the `rooms` table.
 *
 * These used to be hardcoded lists keyed by floor name, which made floors created at
 * runtime unusable — an unrecognised name fell through to a single room covering the
 * whole grid. The seeded rows reproduce those original layouts exactly.
 *
 * The whole-grid fallback is kept for a floor that genuinely has no rooms yet (one
 * just added from Settings): its devices still render instead of vanishing.
 */
fun roomsForFloor(floor: Floor, allRooms: List<Room>): List<Room> {
    val mine = allRooms.filter { it.floorId == floor.id }
    if (mine.isNotEmpty()) return mine

    return listOf(
        Room(
            id = "whole-floor-${floor.id}",
            floorId = floor.id,
            label = floor.name,
            x0 = 0,
            y0 = 0,
            x1 = (floor.gridCols - 1).coerceAtLeast(0),
            y1 = (floor.gridRows - 1).coerceAtLeast(0)
        )
    )
}

fun iconForRoom(label: String): ImageVector = when {
    label.contains("foyer", true) -> Icons.Filled.MeetingRoom
    label.contains("living", true) -> Icons.Filled.Weekend
    label.contains("kitchen", true) -> Icons.Filled.Kitchen
    label.contains("dining", true) -> Icons.Filled.Restaurant
    label.contains("bedroom", true) -> Icons.Filled.Bed
    label.contains("garage", true) -> Icons.Filled.DirectionsCar
    label.contains("bath", true) -> Icons.Filled.Bathtub
    label.contains("study", true) || label.contains("office", true) -> Icons.Filled.Work
    label.contains("balcony", true) -> Icons.Filled.Balcony
    label.contains("landing", true) || label.contains("hallway", true) -> Icons.Filled.Stairs
    label.contains("gate", true) -> Icons.Filled.Fence
    label.contains("garden", true) || label.contains("approach", true) -> Icons.Filled.Grass
    else -> Icons.Filled.Chair
}
