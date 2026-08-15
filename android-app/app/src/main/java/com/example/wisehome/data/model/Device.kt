package com.example.wisehome.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType {
    @SerialName("outlet") OUTLET,
    @SerialName("multiswitch") MULTISWITCH,
    @SerialName("scheduled_safety") SCHEDULED_SAFETY,
    @SerialName("scheduled_light") SCHEDULED_LIGHT,
    @SerialName("camera") CAMERA,
    @SerialName("thermostat") THERMOSTAT,
    @SerialName("ac_unit") AC_UNIT,
    @SerialName("smart_lock") SMART_LOCK,
    @SerialName("sensor") SENSOR,
    @SerialName("smart_plug_metered") SMART_PLUG_METERED,
    @SerialName("appliance") APPLIANCE
}

@Serializable
enum class DeviceStatus {
    ON, OFF, ERROR, DISCONNECTED
}

@Serializable
enum class ControlMode {
    MANUAL, AUTO
}

@Serializable
data class Device(
    val id: String,
    @SerialName("floor_id") val floorId: String?,
    val name: String,
    val type: DeviceType,
    @SerialName("grid_x") val gridX: Int,
    @SerialName("grid_y") val gridY: Int,
    val status: DeviceStatus = DeviceStatus.OFF,
    @SerialName("control_mode") val controlMode: ControlMode = ControlMode.MANUAL,
    @SerialName("appliance_type") val applianceType: String? = null
)
