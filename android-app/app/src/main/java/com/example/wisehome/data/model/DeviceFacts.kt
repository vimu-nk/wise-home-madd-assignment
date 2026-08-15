package com.example.wisehome.data.model

/**
 * The small set of type-specific facts needed to *label* a device anywhere in the
 * UI — room cards, grid badges, list rows — without opening its detail sheet and
 * loading the full extension row.
 */
data class DeviceFacts(
    val deviceId: String,
    val lockMechanism: LockMechanism? = null,
    val sensorType: SensorType? = null,
    val switchCount: Int = 0,
    val switchesOn: Int = 0,
    val thermostatMode: ThermostatMode? = null,
    val targetTempC: Double? = null,
    val controlsDeviceId: String? = null,
    val currentTempC: Double? = null,
    val fanSpeed: FanSpeed? = null,
    val sensorReading: Double? = null,
    val sensorUnit: String? = null,
    /** Latest camera frame, so list rows can show a thumbnail without opening the sheet. */
    val cameraSnapshotUrl: String? = null
)
