package com.example.wisehome.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UsageEventType {
    ON, OFF, ERROR,
    @SerialName("AUTO_CUTOFF") AUTO_CUTOFF,
    LOCKED, UNLOCKED,
    @SerialName("SENSOR_TRIGGERED") SENSOR_TRIGGERED,
    @SerialName("MODE_CHANGE") MODE_CHANGE
}

@Serializable
enum class TriggeredBy {
    @SerialName("user") USER,
    @SerialName("schedule") SCHEDULE,
    @SerialName("safety_worker") SAFETY_WORKER
}

@Serializable
data class UsageLog(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("switch_id") val switchId: String? = null,
    @SerialName("event_type") val eventType: UsageEventType,
    @SerialName("triggered_by") val triggeredBy: TriggeredBy,
    @SerialName("created_at") val createdAt: String
)
