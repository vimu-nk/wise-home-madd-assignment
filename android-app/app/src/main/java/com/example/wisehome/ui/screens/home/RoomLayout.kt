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
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.Floor

/**
 * Cosmetic room outlines drawn over each floor's abstract grid (spec section 5).
 * Not stored in the DB — the schema tracks only device grid_x/grid_y, not room
 * shapes. Coordinates are inclusive grid-cell ranges.
 */
data class Room(val label: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val cols: Int get() = x1 - x0 + 1
    val rows: Int get() = y1 - y0 + 1
    fun contains(device: Device): Boolean =
        device.gridX in x0..x1 && device.gridY in y0..y1
}

private val GROUND_FLOOR_ROOMS = listOf(
    Room("Foyer", 0, 0, 1, 0),
    Room("Living Room", 2, 0, 3, 2),
    Room("Kitchen", 4, 0, 5, 2),
    Room("Dining Area", 0, 1, 1, 2),
    Room("Guest Bedroom", 0, 3, 1, 4),
    Room("Garage", 2, 3, 5, 4)
)

private val FIRST_FLOOR_ROOMS = listOf(
    Room("Master Bedroom", 0, 0, 1, 1),
    Room("Bedroom 2", 3, 0, 4, 1),
    Room("Study / Office", 5, 0, 5, 3),
    Room("Bathroom", 2, 2, 2, 3),
    Room("Balcony", 0, 4, 1, 4),
    Room("Landing / Hallway", 2, 4, 4, 4)
)

private val EXTERIOR_ROOMS = listOf(
    Room("Walking Gate", 0, 0, 1, 1),
    Room("Driveway Gate", 3, 0, 4, 1),
    Room("Front Approach", 2, 2, 5, 3),
    Room("Back Garden", 5, 4, 7, 5)
)

/**
 * Rooms for a floor. Falls back to a single room spanning the whole grid rather
 * than an empty list — a floor renamed in the DB used to render a blank screen
 * with no explanation.
 */
fun roomLayoutFor(floor: Floor): List<Room> =
    when (floor.name.trim().lowercase()) {
        "ground floor" -> GROUND_FLOOR_ROOMS
        "first floor" -> FIRST_FLOOR_ROOMS
        "exterior / garden", "exterior/garden", "exterior" -> EXTERIOR_ROOMS
        else -> listOf(
            Room(floor.name, 0, 0, (floor.gridCols - 1).coerceAtLeast(0), (floor.gridRows - 1).coerceAtLeast(0))
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
