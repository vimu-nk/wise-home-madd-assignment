package com.example.wisehome.data.repository

import com.example.wisehome.data.model.AcUnit
import com.example.wisehome.data.model.Camera
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceSwitch
import com.example.wisehome.data.model.FanSpeed
import com.example.wisehome.data.model.PowerMetrics
import com.example.wisehome.data.model.SafetyConfig
import com.example.wisehome.data.model.SafetyPreset
import com.example.wisehome.data.model.Sensor
import com.example.wisehome.data.model.SmartLock
import com.example.wisehome.data.model.Thermostat
import com.example.wisehome.data.model.ThermostatMode
import com.example.wisehome.data.nowIso
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads/writes for the type-specific extension tables (section 4 of the spec).
 *
 * Each observe* Flow subscribes a scoped Realtime channel for its table while a
 * device's detail sheet is open, and re-fetches on any change. Every setter also
 * pushes an [invalidate] so the sheet updates from our own write even if the
 * Realtime round trip is slow or the socket is down.
 *
 * Writes that touch `devices` are routed through [DeviceRepository] rather than
 * hitting postgrest directly — otherwise the shared device cache (and every grid
 * badge and room card reading it) never learns about the change.
 */
class DeviceExtrasRepository(
    private val deviceRepository: DeviceRepository,
    private val switchRepository: SwitchRepository
) {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime

    private val invalidations = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private fun invalidate(key: String) {
        invalidations.tryEmit(key)
    }

    /**
     * Emits [fetch] immediately, again once the subscription is live (closing the
     * subscribe/fetch race), then on every change to [tableName] and on every
     * manual invalidation of [key].
     */
    private fun <T> watchTable(key: String, tableName: String, fetch: suspend () -> T): Flow<T> =
        channelFlow {
            send(fetch())

            launch {
                invalidations.filter { it == key }.collect { send(fetch()) }
            }

            val channel = realtime.channel(key)
            try {
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = tableName
                }
                channel.subscribe(blockUntilSubscribed = true)
                send(fetch())
                changes.collect { send(fetch()) }
            } finally {
                // removeChannel under NonCancellable: the suspending unsubscribe()
                // throws CancellationException the instant this scope is cancelled,
                // which leaks the channel.
                withContext(NonCancellable) { realtime.removeChannel(channel) }
            }
        }.distinctUntilChanged()

    // ---- Multiswitch: delegated to the shared switches subscription ----

    fun observeSwitches(deviceId: String): Flow<List<DeviceSwitch>> =
        switchRepository.switchesFor(deviceId)

    suspend fun getSwitches(deviceId: String): List<DeviceSwitch> =
        postgrest.from("device_switches").select {
            filter { eq("device_id", deviceId) }
        }.decodeList<DeviceSwitch>().sortedBy { it.switchIndex }

    /**
     * A multiswitch's own devices.status has no direct control — it must reflect
     * whether ANY child switch is on, otherwise its grid badge and room card
     * never change no matter how many switches are flipped.
     */
    suspend fun setSwitchStatus(deviceId: String, switchId: String, status: DeviceStatus) {
        switchRepository.setStatus(switchId, status)
        val switches = switchRepository.observeSwitches().value.filter { it.deviceId == deviceId }
        val anyOn = switches.any { it.status == DeviceStatus.ON }
        deviceRepository.setStatus(deviceId, if (anyOn) DeviceStatus.ON else DeviceStatus.OFF)
    }

    suspend fun setAllSwitches(deviceId: String, status: DeviceStatus) {
        switchRepository.observeSwitches().value
            .filter { it.deviceId == deviceId }
            .forEach { switchRepository.setStatus(it.id, status) }
        deviceRepository.setStatus(deviceId, status)
    }

    // ---- Smart locks ----

    fun observeLock(deviceId: String): Flow<SmartLock?> =
        watchTable("lock-$deviceId", "smart_locks") { getSmartLock(deviceId) }

    suspend fun getSmartLock(deviceId: String): SmartLock? =
        postgrest.from("smart_locks").select {
            filter { eq("device_id", deviceId) }
        }.decodeSingleOrNull()

    suspend fun setLockState(deviceId: String, locked: Boolean) {
        deviceRepository.setStatus(deviceId, if (locked) DeviceStatus.ON else DeviceStatus.OFF)
        runCatching {
            postgrest.from("smart_locks").update({
                if (locked) set("last_locked_at", nowIso()) else set("last_unlocked_at", nowIso())
            }) {
                filter { eq("device_id", deviceId) }
            }
        }
        invalidate("lock-$deviceId")
    }

    // ---- Safety-capped devices (iron, heaters, hair dryer) ----

    fun observeSafetyConfig(deviceId: String): Flow<SafetyConfig?> =
        watchTable("safety-$deviceId", "safety_configs") { getSafetyConfig(deviceId) }

    suspend fun getSafetyConfig(deviceId: String): SafetyConfig? =
        postgrest.from("safety_configs").select {
            filter { eq("device_id", deviceId) }
        }.decodeSingleOrNull()

    /**
     * Duration options for an appliance kind. Reference data that only changes when a
     * migration runs, so it is fetched on demand rather than watched.
     */
    suspend fun getSafetyPreset(kind: String): SafetyPreset? =
        postgrest.from("safety_presets").select {
            filter { eq("kind", kind) }
        }.decodeSingleOrNull()

    /**
     * Arming the countdown is *not* done here. The `trg_devices_safety_arm` trigger
     * stamps `turned_on_at` on any OFF -> ON transition, whoever writes it — this app,
     * the hardware simulator, or the SQL editor. Doing it client-side (as this used to)
     * meant an iron switched on from the simulator was never timed out at all.
     */
    suspend fun setIronPower(deviceId: String, on: Boolean) {
        deviceRepository.setStatus(deviceId, if (on) DeviceStatus.ON else DeviceStatus.OFF)
        invalidate("safety-$deviceId")
    }

    suspend fun setMaxOnDuration(deviceId: String, seconds: Int) {
        postgrest.from("safety_configs").update({
            set("max_on_duration_seconds", seconds)
        }) {
            filter { eq("device_id", deviceId) }
        }
        invalidate("safety-$deviceId")
    }

    // ---- Climate ----

    fun observeThermostat(deviceId: String): Flow<Thermostat?> =
        watchTable("thermostat-$deviceId", "thermostats") { getThermostat(deviceId) }

    fun observeThermostatControlling(acDeviceId: String): Flow<Thermostat?> =
        watchTable("thermostat-ctrl-$acDeviceId", "thermostats") { getThermostatControlling(acDeviceId) }

    fun observeAcUnit(deviceId: String): Flow<AcUnit?> =
        watchTable("acunit-$deviceId", "ac_units") { getAcUnit(deviceId) }

    suspend fun getThermostat(deviceId: String): Thermostat? =
        postgrest.from("thermostats").select {
            filter { eq("device_id", deviceId) }
        }.decodeSingleOrNull()

    suspend fun getThermostatControlling(acDeviceId: String): Thermostat? =
        postgrest.from("thermostats").select {
            filter { eq("controls_device_id", acDeviceId) }
        }.decodeSingleOrNull()

    suspend fun getAcUnit(deviceId: String): AcUnit? =
        postgrest.from("ac_units").select {
            filter { eq("device_id", deviceId) }
        }.decodeSingleOrNull()

    suspend fun setThermostatTarget(deviceId: String, targetTempC: Double) {
        runCatching {
            postgrest.from("thermostats").update({ set("target_temp_c", targetTempC) }) {
                filter { eq("device_id", deviceId) }
            }
        }
        invalidate("thermostat-$deviceId")
    }

    suspend fun setThermostatMode(deviceId: String, acDeviceId: String?, mode: ThermostatMode) {
        runCatching {
            postgrest.from("thermostats").update({ set("mode", mode.name) }) {
                filter { eq("device_id", deviceId) }
            }
        }
        if (acDeviceId != null) {
            deviceRepository.setStatus(
                acDeviceId,
                if (mode == ThermostatMode.OFF) DeviceStatus.OFF else DeviceStatus.ON
            )
            invalidate("thermostat-ctrl-$acDeviceId")
        }
        invalidate("thermostat-$deviceId")
    }

    suspend fun setAcFanSpeed(acDeviceId: String, fanSpeed: FanSpeed) {
        runCatching {
            postgrest.from("ac_units").update({ set("fan_speed", fanSpeed.name) }) {
                filter { eq("device_id", acDeviceId) }
            }
        }
        invalidate("acunit-$acDeviceId")
    }

    // ---- Read-only extras ----

    fun observeSensor(deviceId: String): Flow<Sensor?> =
        watchTable("sensor-$deviceId", "sensors") { getSensor(deviceId) }

    suspend fun getSensor(deviceId: String): Sensor? =
        postgrest.from("sensors").select {
            filter { eq("device_id", deviceId) }
        }.decodeSingleOrNull()

    fun observeCamera(deviceId: String): Flow<Camera?> =
        watchTable("camera-$deviceId", "cameras") { getCamera(deviceId) }

    suspend fun getCamera(deviceId: String): Camera? =
        postgrest.from("cameras").select {
            filter { eq("device_id", deviceId) }
        }.decodeSingleOrNull()

    fun observePowerMetrics(deviceId: String): Flow<PowerMetrics?> =
        watchTable("power-$deviceId", "power_metrics") { getPowerMetrics(deviceId) }

    suspend fun getPowerMetrics(deviceId: String): PowerMetrics? =
        postgrest.from("power_metrics").select {
            filter { eq("device_id", deviceId) }
        }.decodeSingleOrNull()
}
