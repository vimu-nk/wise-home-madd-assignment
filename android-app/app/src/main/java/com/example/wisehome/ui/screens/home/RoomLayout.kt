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

/** label, x0, y0, x1, y1 — inclusive grid rectangles, identical to what the
 *  `rooms` migration seeds for the three floors of the demo house. */
private val BUILT_IN_LAYOUTS: Map<String, List<List<Any>>> = mapOf(
    "ground floor" to listOf(
        listOf("Foyer", 0, 0, 1, 0),
        listOf("Living Room", 2, 0, 3, 2),
        listOf("Kitchen", 4, 0, 5, 2),
        listOf("Dining Area", 0, 1, 1, 2),
        listOf("Guest Bedroom", 0, 3, 1, 4),
        listOf("Garage", 2, 3, 5, 4)
    ),
    "first floor" to listOf(
        listOf("Master Bedroom", 0, 0, 1, 1),
        listOf("Bedroom 2", 3, 0, 4, 1),
        listOf("Study / Office", 5, 0, 5, 3),
        listOf("Bathroom", 2, 2, 3, 3),
        listOf("Balcony", 0, 4, 1, 4),
        listOf("Landing / Hallway", 2, 4, 4, 4)
    ),
    "exterior / garden" to listOf(
        listOf("Walking Gate", 0, 0, 1, 1),
        listOf("Driveway Gate", 3, 0, 4, 1),
        listOf("Front Approach", 2, 2, 5, 3),
        listOf("Back Garden", 5, 4, 7, 5)
    )
)

private fun builtInRooms(floor: Floor): List<Room> {
    val key = floor.name.trim().lowercase().replace("/", " / ").replace(Regex("\\s+"), " ")
    val layout = BUILT_IN_LAYOUTS[key] ?: return emptyList()
    return layout.mapIndexed { index, spec ->
        Room(
            // Stable across recompositions so LazyColumn keys don't churn.
            id = "builtin-${floor.id}-$index",
            floorId = floor.id,
            label = spec[0] as String,
            x0 = spec[1] as Int,
            y0 = spec[2] as Int,
            x1 = spec[3] as Int,
            y1 = spec[4] as Int
        )
    }
}

/**
 * Rooms for a floor.
 *
 * The `rooms` table is the source of truth — [BUILT_IN_LAYOUTS] is a *display* fallback
 * for the seeded demo house, so the app shows real room names on a database where the
 * rooms migration hasn't been applied. Database rooms always win, so applying it later
 * changes nothing visually, and rooms edited in Settings behave normally.
 *
 * Room membership is positional ([Room.contains]), so devices land in a built-in room
 * without any row existing for it.
 *
 * A floor with neither — one just created from Settings — falls through to a single
 * region spanning the grid, so its devices still render instead of vanishing.
 */
fun roomsForFloor(floor: Floor, allRooms: List<Room>): List<Room> {
    val fromDb = allRooms.filter { it.floorId == floor.id }
    if (fromDb.isNotEmpty()) return fromDb

    val builtIn = builtInRooms(floor)
    if (builtIn.isNotEmpty()) return builtIn

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
