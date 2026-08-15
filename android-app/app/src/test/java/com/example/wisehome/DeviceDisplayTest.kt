package com.example.wisehome

import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceSwitch
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.data.model.LockMechanism
import com.example.wisehome.data.model.Sensor
import com.example.wisehome.data.model.SensorType
import com.example.wisehome.data.model.SmartLock
import com.example.wisehome.data.model.Thermostat
import com.example.wisehome.data.model.ThermostatMode
import com.example.wisehome.ui.format.DeviceContext
import com.example.wisehome.ui.format.StateTone
import com.example.wisehome.ui.format.deviceDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the per-device-type vocabulary. "ON"/"OFF" must never reach the user —
 * a gate is Open or Closed, a smoke sensor is Clear or has detected smoke.
 */
class DeviceDisplayTest {

    private fun device(
        type: DeviceType,
        status: DeviceStatus = DeviceStatus.OFF,
        applianceType: String? = null
    ) = Device(
        id = "d1",
        floorId = "f1",
        name = "Test device",
        type = type,
        gridX = 0,
        gridY = 0,
        status = status,
        applianceType = applianceType
    )

    @Test
    fun `deadbolt lock reads locked and unlocked`() {
        val ctx = DeviceContext(lock = SmartLock("d1", LockMechanism.DEADBOLT))
        assertEquals("Locked", deviceDisplay(device(DeviceType.SMART_LOCK, DeviceStatus.ON), ctx).stateLabel)
        assertEquals("Unlocked", deviceDisplay(device(DeviceType.SMART_LOCK, DeviceStatus.OFF), ctx).stateLabel)
    }

    @Test
    fun `sliding gate reads closed and open with matching verbs`() {
        val ctx = DeviceContext(lock = SmartLock("d1", LockMechanism.SLIDING_GATE))
        val open = deviceDisplay(device(DeviceType.SMART_LOCK, DeviceStatus.OFF), ctx)
        assertEquals("Open", open.stateLabel)
        assertEquals("Close", open.onVerb)
        assertEquals("Open", open.offVerb)
    }

    @Test
    fun `turnstile releases rather than unlocks`() {
        val ctx = DeviceContext(lock = SmartLock("d1", LockMechanism.TURNSTILE))
        val display = deviceDisplay(device(DeviceType.SMART_LOCK, DeviceStatus.OFF), ctx)
        assertEquals("Free to turn", display.stateLabel)
        assertEquals("Release", display.offVerb)
    }

    @Test
    fun `multiswitch summarises how many are on`() {
        val switches = listOf(
            DeviceSwitch("s1", "d1", 1, "A", DeviceStatus.ON),
            DeviceSwitch("s2", "d1", 2, "B", DeviceStatus.OFF),
            DeviceSwitch("s3", "d1", 3, "C", DeviceStatus.ON)
        )
        val display = deviceDisplay(
            device(DeviceType.MULTISWITCH, DeviceStatus.ON),
            DeviceContext(switches = switches)
        )
        assertEquals("2 of 3 on", display.stateLabel)
        assertEquals("3-gang switch panel", display.typeLabel)
    }

    @Test
    fun `multiswitch collapses to all on and all off`() {
        val allOn = List(2) { DeviceSwitch("s$it", "d1", it, null, DeviceStatus.ON) }
        assertEquals(
            "All on",
            deviceDisplay(device(DeviceType.MULTISWITCH, DeviceStatus.ON), DeviceContext(switches = allOn)).stateLabel
        )
        val allOff = List(2) { DeviceSwitch("s$it", "d1", it, null, DeviceStatus.OFF) }
        assertEquals(
            "All off",
            deviceDisplay(device(DeviceType.MULTISWITCH, DeviceStatus.OFF), DeviceContext(switches = allOff)).stateLabel
        )
    }

    @Test
    fun `smoke sensor is a fault when triggered, not merely attention`() {
        val ctx = DeviceContext(sensor = Sensor("d1", SensorType.SMOKE))
        val triggered = deviceDisplay(device(DeviceType.SENSOR, DeviceStatus.ON), ctx)
        assertEquals("Smoke detected", triggered.stateLabel)
        assertEquals(StateTone.FAULT, triggered.tone)
        assertEquals("Clear", deviceDisplay(device(DeviceType.SENSOR, DeviceStatus.OFF), ctx).stateLabel)
    }

    @Test
    fun `sensors expose no control verbs`() {
        val display = deviceDisplay(
            device(DeviceType.SENSOR, DeviceStatus.OFF),
            DeviceContext(sensor = Sensor("d1", SensorType.MOTION))
        )
        assertNull(display.onVerb)
        assertNull(display.offVerb)
    }

    @Test
    fun `thermostat states its target temperature`() {
        val ctx = DeviceContext(thermostat = Thermostat("d1", 22.0, ThermostatMode.COOL))
        assertEquals(
            "Cooling to 22°",
            deviceDisplay(device(DeviceType.THERMOSTAT, DeviceStatus.ON), ctx).stateLabel
        )
    }

    @Test
    fun `appliance vocabulary follows the appliance type`() {
        assertEquals(
            "Playing",
            deviceDisplay(device(DeviceType.APPLIANCE, DeviceStatus.ON, "tv"), DeviceContext.Empty).stateLabel
        )
        val washer = deviceDisplay(device(DeviceType.APPLIANCE, DeviceStatus.ON, "washing_machine"), DeviceContext.Empty)
        assertEquals("Washing", washer.stateLabel)
        assertEquals("Start", washer.onVerb)
    }

    @Test
    fun `error and disconnected override every type-specific label`() {
        val ctx = DeviceContext(lock = SmartLock("d1", LockMechanism.DEADBOLT))
        val error = deviceDisplay(device(DeviceType.SMART_LOCK, DeviceStatus.ERROR), ctx)
        assertEquals("Needs attention", error.stateLabel)
        assertEquals(StateTone.FAULT, error.tone)

        val offline = deviceDisplay(device(DeviceType.CAMERA, DeviceStatus.DISCONNECTED))
        assertEquals("Offline", offline.stateLabel)
        assertEquals(StateTone.OFFLINE, offline.tone)
    }

    @Test
    fun `iron is heating and flagged for attention while on`() {
        val display = deviceDisplay(device(DeviceType.SCHEDULED_SAFETY, DeviceStatus.ON))
        assertEquals("Heating", display.stateLabel)
        assertEquals(StateTone.ATTENTION, display.tone)
    }
}
