package com.example.wisehome.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Alert(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val message: String,
    @SerialName("created_at") val createdAt: String,
    val acknowledged: Boolean = false
)
