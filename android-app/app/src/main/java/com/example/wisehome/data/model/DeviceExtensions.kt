package com.example.wisehome.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceSwitch(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("switch_index") val switchIndex: Int,
    val label: String? = null,
    val status: DeviceStatus = DeviceStatus.OFF
)

@Serializable
data class SafetyConfig(
    @SerialName("device_id") val deviceId: String,
    @SerialName("max_on_duration_seconds") val maxOnDurationSeconds: Int,
    @SerialName("turned_on_at") val turnedOnAt: String? = null,
    @SerialName("last_auto_cutoff_at") val lastAutoCutoffAt: String? = null
)

@Serializable
data class LightSchedule(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("days_of_week") val daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val enabled: Boolean = true
)

@Serializable
data class Camera(
    @SerialName("device_id") val deviceId: String,
    @SerialName("mock_stream_uri") val mockStreamUri: String? = null,
    @SerialName("last_snapshot_url") val lastSnapshotUrl: String? = null,
    @SerialName("last_snapshot_at") val lastSnapshotAt: String? = null
)

@Serializable
enum class ThermostatMode { COOL, HEAT, AUTO, OFF }

@Serializable
data class Thermostat(
    @SerialName("device_id") val deviceId: String,
    @SerialName("target_temp_c") val targetTempC: Double = 22.0,
    val mode: ThermostatMode = ThermostatMode.OFF,
    @SerialName("controls_device_id") val controlsDeviceId: String? = null
)

@Serializable
enum class FanSpeed { LOW, MED, HIGH, AUTO }

@Serializable
data class AcUnit(
    @SerialName("device_id") val deviceId: String,
    @SerialName("fan_speed") val fanSpeed: FanSpeed = FanSpeed.AUTO,
    @SerialName("current_temp_c") val currentTempC: Double? = null
)

@Serializable
enum class LockMechanism {
    @SerialName("deadbolt") DEADBOLT,
    @SerialName("sliding_gate") SLIDING_GATE,
    @SerialName("turnstile") TURNSTILE
}

@Serializable
data class SmartLock(
    @SerialName("device_id") val deviceId: String,
    val mechanism: LockMechanism = LockMechanism.DEADBOLT,
    @SerialName("auto_relock_after_seconds") val autoRelockAfterSeconds: Int? = null,
    @SerialName("last_locked_at") val lastLockedAt: String? = null,
    @SerialName("last_unlocked_at") val lastUnlockedAt: String? = null
)

@Serializable
enum class SensorType {
    @SerialName("motion") MOTION,
    @SerialName("door_window") DOOR_WINDOW,
    @SerialName("smoke") SMOKE,
    @SerialName("gas") GAS,
    @SerialName("water_leak") WATER_LEAK
}

@Serializable
data class Sensor(
    @SerialName("device_id") val deviceId: String,
    @SerialName("sensor_type") val sensorType: SensorType,
    @SerialName("current_reading") val currentReading: Double? = null,
    val unit: String? = null,
    @SerialName("alert_threshold") val alertThreshold: Double? = null,
    @SerialName("last_triggered_at") val lastTriggeredAt: String? = null
)

@Serializable
data class PowerMetrics(
    @SerialName("device_id") val deviceId: String,
    @SerialName("current_watts") val currentWatts: Double = 0.0,
    @SerialName("energy_kwh_total") val energyKwhTotal: Double = 0.0,
    @SerialName("last_reading_at") val lastReadingAt: String? = null
)
