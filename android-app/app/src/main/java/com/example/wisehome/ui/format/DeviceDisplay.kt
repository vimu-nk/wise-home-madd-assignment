package com.example.wisehome.ui.format

import com.example.wisehome.data.model.AcUnit
import com.example.wisehome.data.model.Camera
import com.example.wisehome.data.model.ControlMode
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceSwitch
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.data.model.FanSpeed
import com.example.wisehome.data.model.LockMechanism
import com.example.wisehome.data.model.PowerMetrics
import com.example.wisehome.data.model.SafetyConfig
import com.example.wisehome.data.model.Sensor
import com.example.wisehome.data.model.SensorType
import com.example.wisehome.data.model.SmartLock
import com.example.wisehome.data.model.Thermostat
import com.example.wisehome.data.model.ThermostatMode
import com.example.wisehome.data.model.TriggeredBy
import com.example.wisehome.data.model.UsageEventType

/**
 * The single source of every user-facing word describing a device.
 *
 * Nothing in the UI should call `.name` on a model enum: "ON"/"OFF" is meaningless
 * on a gate, an iron or a smoke sensor. This maps each device type (and its
 * discriminator — lock mechanism, sensor type, appliance type) to language a
 * person would actually use.
 */

enum class StateTone { ACTIVE, IDLE, ATTENTION, FAULT, OFFLINE }

/** Everything the vocabulary may consult. All optional — labels degrade gracefully. */
data class DeviceContext(
    val facts: DeviceFacts? = null,
    val switches: List<DeviceSwitch> = emptyList(),
    val lock: SmartLock? = null,
    val safety: SafetyConfig? = null,
    val thermostat: Thermostat? = null,
    val acUnit: AcUnit? = null,
    /** The `ac_unit` device a thermostat drives (or the AC itself when viewing one). */
    val acDevice: Device? = null,
    val sensor: Sensor? = null,
    val camera: Camera? = null,
    val power: PowerMetrics? = null,
    val remainingSeconds: Long? = null
) {
    companion object {
        val Empty = DeviceContext()
    }
}

data class DeviceDisplay(
    val typeLabel: String,
    val stateLabel: String,
    val tone: StateTone,
    val detail: String? = null,
    val onVerb: String? = null,
    val offVerb: String? = null
) {
    /** Read-only devices (sensors, cameras) expose no verbs. */
    val isControllable: Boolean get() = onVerb != null && offVerb != null
}

private val Device.isOn: Boolean get() = status == DeviceStatus.ON

private fun mechanismOf(device: Device, ctx: DeviceContext): LockMechanism? =
    ctx.lock?.mechanism ?: ctx.facts?.lockMechanism

private fun sensorTypeOf(ctx: DeviceContext): SensorType? =
    ctx.sensor?.sensorType ?: ctx.facts?.sensorType

private fun thermostatModeOf(ctx: DeviceContext): ThermostatMode? =
    ctx.thermostat?.mode ?: ctx.facts?.thermostatMode

private fun targetTempOf(ctx: DeviceContext): Double? =
    ctx.thermostat?.targetTempC ?: ctx.facts?.targetTempC

fun formatTemp(celsius: Double?): String {
    if (celsius == null) return "—"
    val rounded = Math.round(celsius * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}°" else "$rounded°"
}

fun thermostatModeLabel(mode: ThermostatMode): String = when (mode) {
    ThermostatMode.COOL -> "Cool"
    ThermostatMode.HEAT -> "Heat"
    ThermostatMode.AUTO -> "Auto"
    ThermostatMode.OFF -> "Off"
}

fun fanSpeedLabel(speed: FanSpeed): String = when (speed) {
    FanSpeed.LOW -> "Low"
    FanSpeed.MED -> "Medium"
    FanSpeed.HIGH -> "High"
    FanSpeed.AUTO -> "Auto"
}

fun controlModeLabel(mode: ControlMode): String = when (mode) {
    ControlMode.AUTO -> "On a schedule"
    ControlMode.MANUAL -> "Manual control"
}

fun sensorTypeLabel(type: SensorType): String = when (type) {
    SensorType.MOTION -> "Motion sensor"
    SensorType.DOOR_WINDOW -> "Door & window sensor"
    SensorType.SMOKE -> "Smoke detector"
    SensorType.GAS -> "Gas detector"
    SensorType.WATER_LEAK -> "Water leak sensor"
}

fun applianceTypeLabel(raw: String?): String = when (raw?.lowercase()) {
    "tv" -> "Television"
    "fridge" -> "Refrigerator"
    "washing_machine" -> "Washing machine"
    "microwave" -> "Microwave"
    "fan" -> "Exhaust fan"
    "water_heater" -> "Water heater"
    null, "" -> "Appliance"
    else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun triggeredByLabel(by: TriggeredBy): String = when (by) {
    TriggeredBy.USER -> "You"
    TriggeredBy.SCHEDULE -> "Schedule"
    TriggeredBy.SAFETY_WORKER -> "Safety cut-off"
}

fun usageEventLabel(event: UsageEventType, ctx: DeviceContext = DeviceContext.Empty): String =
    when (event) {
        UsageEventType.ON -> "Turned on"
        UsageEventType.OFF -> "Turned off"
        UsageEventType.ERROR -> "Reported a fault"
        UsageEventType.AUTO_CUTOFF -> "Auto shut-off (safety timer)"
        UsageEventType.LOCKED -> when (ctx.lock?.mechanism ?: ctx.facts?.lockMechanism) {
            LockMechanism.SLIDING_GATE -> "Closed"
            else -> "Locked"
        }
        UsageEventType.UNLOCKED -> when (ctx.lock?.mechanism ?: ctx.facts?.lockMechanism) {
            LockMechanism.SLIDING_GATE -> "Opened"
            LockMechanism.TURNSTILE -> "Released"
            else -> "Unlocked"
        }
        UsageEventType.SENSOR_TRIGGERED -> "Triggered"
        UsageEventType.MODE_CHANGE -> "Mode changed"
    }

fun deviceTypeLabel(device: Device, ctx: DeviceContext = DeviceContext.Empty): String =
    when (device.type) {
        DeviceType.SMART_LOCK -> when (mechanismOf(device, ctx)) {
            LockMechanism.SLIDING_GATE -> "Sliding gate"
            LockMechanism.TURNSTILE -> "Turnstile gate"
            else -> "Deadbolt lock"
        }
        DeviceType.MULTISWITCH -> {
            val count = ctx.switches.size.takeIf { it > 0 } ?: ctx.facts?.switchCount ?: 0
            if (count > 0) "$count-gang switch panel" else "Switch panel"
        }
        DeviceType.SCHEDULED_SAFETY -> "Safety-timed appliance"
        DeviceType.SCHEDULED_LIGHT -> "Light"
        DeviceType.SMART_PLUG_METERED -> "Metered plug"
        DeviceType.AC_UNIT -> "Air conditioner"
        DeviceType.THERMOSTAT -> "Thermostat"
        DeviceType.CAMERA -> "Security camera"
        DeviceType.OUTLET -> "Power outlet"
        DeviceType.SENSOR -> sensorTypeOf(ctx)?.let { sensorTypeLabel(it) } ?: "Sensor"
        DeviceType.APPLIANCE -> applianceTypeLabel(device.applianceType)
    }

fun deviceDisplay(device: Device, ctx: DeviceContext = DeviceContext.Empty): DeviceDisplay {
    val typeLabel = deviceTypeLabel(device, ctx)

    // Fault and offline states override every type-specific label.
    if (device.status == DeviceStatus.ERROR) {
        return DeviceDisplay(typeLabel, "Needs attention", StateTone.FAULT)
    }
    if (device.status == DeviceStatus.DISCONNECTED) {
        return DeviceDisplay(typeLabel, "Offline", StateTone.OFFLINE)
    }

    val on = device.isOn

    return when (device.type) {
        DeviceType.SMART_LOCK -> {
            val mechanism = mechanismOf(device, ctx)
            val (onLabel, offLabel) = when (mechanism) {
                LockMechanism.SLIDING_GATE -> "Closed" to "Open"
                LockMechanism.TURNSTILE -> "Locked" to "Free to turn"
                else -> "Locked" to "Unlocked"
            }
            val (onVerb, offVerb) = when (mechanism) {
                LockMechanism.SLIDING_GATE -> "Close" to "Open"
                LockMechanism.TURNSTILE -> "Lock" to "Release"
                else -> "Lock" to "Unlock"
            }
            val stamp = if (on) ctx.lock?.lastLockedAt else ctx.lock?.lastUnlockedAt
            DeviceDisplay(
                typeLabel = typeLabel,
                stateLabel = if (on) onLabel else offLabel,
                tone = if (on) StateTone.ACTIVE else StateTone.ATTENTION,
                detail = stamp?.let { "${if (on) onLabel else offLabel} ${formatRelative(it)}" },
                onVerb = onVerb,
                offVerb = offVerb
            )
        }

        DeviceType.MULTISWITCH -> {
            val switches = ctx.switches
            val total = switches.size.takeIf { it > 0 } ?: ctx.facts?.switchCount ?: 0
            val onCount = if (switches.isNotEmpty()) {
                switches.count { it.status == DeviceStatus.ON }
            } else {
                ctx.facts?.switchesOn ?: 0
            }
            val label = when {
                total == 0 -> if (on) "On" else "Off"
                onCount == 0 -> "All off"
                onCount == total -> "All on"
                else -> "$onCount of $total on"
            }
            DeviceDisplay(
                typeLabel = typeLabel,
                stateLabel = label,
                tone = if (onCount > 0) StateTone.ACTIVE else StateTone.IDLE
            )
        }

        DeviceType.SCHEDULED_SAFETY -> DeviceDisplay(
            typeLabel = typeLabel,
            stateLabel = if (on) "Heating" else "Off",
            tone = if (on) StateTone.ATTENTION else StateTone.IDLE,
            detail = ctx.remainingSeconds?.takeIf { on }?.let { "Auto-off in ${formatDuration(it)}" },
            onVerb = "Turn on",
            offVerb = "Turn off"
        )

        DeviceType.SCHEDULED_LIGHT -> DeviceDisplay(
            typeLabel = typeLabel,
            stateLabel = if (on) "On" else "Off",
            tone = if (on) StateTone.ACTIVE else StateTone.IDLE,
            detail = if (device.controlMode == ControlMode.AUTO) controlModeLabel(ControlMode.AUTO) else null,
            onVerb = "Turn on",
            offVerb = "Turn off"
        )

        DeviceType.AC_UNIT -> {
            val mode = thermostatModeOf(ctx)
            val activeLabel = when (mode) {
                ThermostatMode.HEAT -> "Heating"
                ThermostatMode.COOL -> "Cooling"
                else -> "Running"
            }
            val fan = ctx.acUnit?.fanSpeed ?: ctx.facts?.fanSpeed
            val current = ctx.acUnit?.currentTempC ?: ctx.facts?.currentTempC
            val bits = buildList {
                fan?.let { add("Fan ${fanSpeedLabel(it).lowercase()}") }
                current?.let { add("Room ${formatTemp(it)}") }
            }
            DeviceDisplay(
                typeLabel = typeLabel,
                stateLabel = if (on) activeLabel else "Idle",
                tone = if (on) StateTone.ACTIVE else StateTone.IDLE,
                detail = bits.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                onVerb = "Turn on",
                offVerb = "Turn off"
            )
        }

        DeviceType.THERMOSTAT -> {
            val mode = thermostatModeOf(ctx)
            val target = targetTempOf(ctx)
            val label = when (mode) {
                ThermostatMode.COOL -> "Cooling to ${formatTemp(target)}"
                ThermostatMode.HEAT -> "Heating to ${formatTemp(target)}"
                ThermostatMode.AUTO -> "Auto · ${formatTemp(target)}"
                else -> "Off"
            }
            DeviceDisplay(
                typeLabel = typeLabel,
                stateLabel = label,
                tone = if (mode != null && mode != ThermostatMode.OFF) StateTone.ACTIVE else StateTone.IDLE
            )
        }

        DeviceType.CAMERA -> DeviceDisplay(
            typeLabel = typeLabel,
            stateLabel = if (on) "Live" else "Standby",
            tone = if (on) StateTone.ACTIVE else StateTone.IDLE,
            detail = ctx.camera?.lastSnapshotAt?.let { "Updated ${formatRelative(it)}" }
        )

        DeviceType.SENSOR -> {
            val type = sensorTypeOf(ctx)
            val triggered = on
            val label = when (type) {
                SensorType.MOTION -> if (triggered) "Motion detected" else "Clear"
                SensorType.DOOR_WINDOW -> if (triggered) "Open" else "Closed"
                SensorType.SMOKE -> if (triggered) "Smoke detected" else "Clear"
                SensorType.GAS -> if (triggered) "Gas detected" else "Clear"
                SensorType.WATER_LEAK -> if (triggered) "Leak detected" else "Dry"
                null -> if (triggered) "Triggered" else "Clear"
            }
            val tone = when {
                !triggered -> StateTone.IDLE
                type == SensorType.SMOKE || type == SensorType.GAS || type == SensorType.WATER_LEAK ->
                    StateTone.FAULT
                else -> StateTone.ATTENTION
            }
            val reading = ctx.sensor?.currentReading ?: ctx.facts?.sensorReading
            val unit = ctx.sensor?.unit ?: ctx.facts?.sensorUnit
            val lastTriggered = ctx.sensor?.lastTriggeredAt
            val bits = buildList {
                if (reading != null && !unit.isNullOrBlank()) add("$reading $unit")
                lastTriggered?.let { add("Last triggered ${formatRelative(it)}") }
            }
            DeviceDisplay(
                typeLabel = typeLabel,
                stateLabel = label,
                tone = tone,
                detail = bits.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            )
        }

        DeviceType.SMART_PLUG_METERED -> {
            val watts = ctx.power?.currentWatts
            val kwh = ctx.power?.energyKwhTotal
            val bits = buildList {
                watts?.let { add("$it W now") }
                kwh?.let { add("$it kWh total") }
            }
            DeviceDisplay(
                typeLabel = typeLabel,
                stateLabel = if (on) "Powered" else "Off",
                tone = if (on) StateTone.ACTIVE else StateTone.IDLE,
                detail = bits.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                onVerb = "Turn on",
                offVerb = "Turn off"
            )
        }

        DeviceType.OUTLET -> DeviceDisplay(
            typeLabel = typeLabel,
            stateLabel = if (on) "Powered" else "Off",
            tone = if (on) StateTone.ACTIVE else StateTone.IDLE,
            onVerb = "Turn on",
            offVerb = "Turn off"
        )

        DeviceType.APPLIANCE -> {
            val (onLabel, offLabel) = when (device.applianceType?.lowercase()) {
                "tv" -> "Playing" to "Standby"
                "fridge" -> "Running" to "Off"
                "washing_machine" -> "Washing" to "Idle"
                "microwave" -> "Running" to "Idle"
                "fan" -> "Spinning" to "Off"
                "water_heater" -> "Heating" to "Off"
                else -> "On" to "Off"
            }
            val (onVerb, offVerb) = when (device.applianceType?.lowercase()) {
                "washing_machine" -> "Start" to "Stop"
                else -> "Turn on" to "Turn off"
            }
            DeviceDisplay(
                typeLabel = typeLabel,
                stateLabel = if (on) onLabel else offLabel,
                tone = if (on) StateTone.ACTIVE else StateTone.IDLE,
                onVerb = onVerb,
                offVerb = offVerb
            )
        }
    }
}

fun deviceStateLabel(device: Device, ctx: DeviceContext = DeviceContext.Empty): String =
    deviceDisplay(device, ctx).stateLabel

fun deviceStateTone(device: Device, ctx: DeviceContext = DeviceContext.Empty): StateTone =
    deviceDisplay(device, ctx).tone
