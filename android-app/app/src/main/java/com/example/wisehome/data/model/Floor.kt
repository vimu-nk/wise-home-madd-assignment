package com.example.wisehome.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Floor(
    val id: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("grid_cols") val gridCols: Int,
    @SerialName("grid_rows") val gridRows: Int
)
