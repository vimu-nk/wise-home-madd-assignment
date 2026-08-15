package com.example.wisehome.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A room as an inclusive rectangle of grid cells on its floor.
 *
 * Room shapes used to be hardcoded in the UI layer keyed by floor name, which meant
 * a floor added at runtime had no rooms at all. They live in the database so floors
 * can be created and edited from the app.
 */
@Serializable
data class Room(
    val id: String,
    @SerialName("floor_id") val floorId: String,
    val label: String,
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int
) {
    val cols: Int get() = x1 - x0 + 1
    val rows: Int get() = y1 - y0 + 1

    fun contains(device: Device): Boolean =
        device.gridX in x0..x1 && device.gridY in y0..y1
}
