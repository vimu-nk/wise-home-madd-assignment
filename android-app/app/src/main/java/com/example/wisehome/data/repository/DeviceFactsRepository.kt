package com.example.wisehome.data.repository

import com.example.wisehome.data.model.Camera
import com.example.wisehome.data.model.AcUnit
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.Sensor
import com.example.wisehome.data.model.SmartLock
import com.example.wisehome.data.model.Thermostat
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A batched, app-wide index of type-specific facts keyed by device id, so any
 * screen can label a device correctly ("Deadbolt lock · Unlocked", "2 of 3 on",
 * "Cooling to 22°") without opening its detail sheet.
 *
 * Locks and sensor types are effectively static; thermostat mode/target and AC
 * readings do change, so those two tables are watched live.
 */
class DeviceFactsRepository(
    private val switchRepository: SwitchRepository
) {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val facts = MutableStateFlow<Map<String, DeviceFacts>>(emptyMap())
    private var started = false

    fun observeFacts(): StateFlow<Map<String, DeviceFacts>> {
        if (!started) {
            started = true
            scope.launch { refresh() }
            scope.launch { watch("facts-thermostats", "thermostats") }
            scope.launch { watch("facts-ac-units", "ac_units") }
            scope.launch { watch("facts-sensors", "sensors") }
            // Camera snapshots rotate from the simulator; list thumbnails follow them live.
            scope.launch { watch("facts-cameras", "cameras") }
            scope.launch {
                // Switch counts come from the shared switches subscription.
                switchRepository.observeSwitches().collect { rebuildSwitchCounts() }
            }
        }
        return facts.asStateFlow()
    }

    private suspend fun watch(channelName: String, tableName: String) {
        val channel = realtime.channel(channelName)
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = tableName
        }
        channel.subscribe(blockUntilSubscribed = true)
        changes.collect { refresh() }
    }

    private fun rebuildSwitchCounts() {
        val grouped = switchRepository.observeSwitches().value.groupBy { it.deviceId }
        facts.value = facts.value.toMutableMap().apply {
            grouped.forEach { (deviceId, switches) ->
                val existing = this[deviceId] ?: DeviceFacts(deviceId)
                this[deviceId] = existing.copy(
                    switchCount = switches.size,
                    switchesOn = switches.count { it.status == DeviceStatus.ON }
                )
            }
        }
    }

    suspend fun refresh() {
        val locks = runCatching {
            postgrest.from("smart_locks").select().decodeList<SmartLock>()
        }.getOrDefault(emptyList())
        val sensors = runCatching {
            postgrest.from("sensors").select().decodeList<Sensor>()
        }.getOrDefault(emptyList())
        val thermostats = runCatching {
            postgrest.from("thermostats").select().decodeList<Thermostat>()
        }.getOrDefault(emptyList())
        val acUnits = runCatching {
            postgrest.from("ac_units").select().decodeList<AcUnit>()
        }.getOrDefault(emptyList())
        val cameras = runCatching {
            postgrest.from("cameras").select().decodeList<Camera>()
        }.getOrDefault(emptyList())
        val switchesByDevice = switchRepository.observeSwitches().value.groupBy { it.deviceId }

        val merged = mutableMapOf<String, DeviceFacts>()
        fun edit(deviceId: String, block: (DeviceFacts) -> DeviceFacts) {
            merged[deviceId] = block(merged[deviceId] ?: DeviceFacts(deviceId))
        }

        locks.forEach { lock -> edit(lock.deviceId) { it.copy(lockMechanism = lock.mechanism) } }
        sensors.forEach { sensor ->
            edit(sensor.deviceId) {
                it.copy(
                    sensorType = sensor.sensorType,
                    sensorReading = sensor.currentReading,
                    sensorUnit = sensor.unit
                )
            }
        }
        thermostats.forEach { thermostat ->
            edit(thermostat.deviceId) {
                it.copy(
                    thermostatMode = thermostat.mode,
                    targetTempC = thermostat.targetTempC,
                    controlsDeviceId = thermostat.controlsDeviceId
                )
            }
            // The AC unit needs its controlling thermostat's mode to say "Cooling"
            // rather than just "On".
            thermostat.controlsDeviceId?.let { acId ->
                edit(acId) {
                    it.copy(thermostatMode = thermostat.mode, targetTempC = thermostat.targetTempC)
                }
            }
        }
        acUnits.forEach { ac ->
            edit(ac.deviceId) { it.copy(currentTempC = ac.currentTempC, fanSpeed = ac.fanSpeed) }
        }
        cameras.forEach { camera ->
            edit(camera.deviceId) { it.copy(cameraSnapshotUrl = camera.lastSnapshotUrl) }
        }
        switchesByDevice.forEach { (deviceId, switches) ->
            edit(deviceId) {
                it.copy(
                    switchCount = switches.size,
                    switchesOn = switches.count { s -> s.status == DeviceStatus.ON }
                )
            }
        }

        facts.value = merged
    }
}
