package com.example.wisehome.data.repository

import com.example.wisehome.data.model.ControlMode
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Insert payload — the database assigns id, status, control_mode and timestamps. */
@Serializable
private data class DeviceInsert(
    @SerialName("floor_id") val floorId: String,
    val name: String,
    val type: DeviceType,
    @SerialName("grid_x") val gridX: Int,
    @SerialName("grid_y") val gridY: Int,
    @SerialName("appliance_type") val applianceType: String? = null
)

/**
 * Single realtime subscription on `devices`, shared app-wide (per section 3 of the spec).
 * ViewModels collect [observeDevices] and filter client-side (e.g. by floor).
 *
 * Writes never depend on the Realtime echo to update the UI: each one applies an
 * optimistic local change, then reconciles against the authoritative row from the
 * server, then rolls back and reports an error if the write failed. Realtime is the
 * path for changes made *elsewhere* (the simulator, the pg_cron safety worker).
 */
class DeviceRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val devices = MutableStateFlow<List<Device>>(emptyList())
    private var started = false

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun observeDevices(): StateFlow<List<Device>> {
        if (!started) {
            started = true
            scope.launch { subscribeToChanges() }
        }
        return devices.asStateFlow()
    }

    suspend fun refresh() {
        runCatching { postgrest.from("devices").select().decodeList<Device>() }
            .onSuccess { devices.value = it }
            .onFailure { _errors.tryEmit("Couldn't load devices") }
    }

    private fun patchLocal(deviceId: String, transform: (Device) -> Device) {
        devices.update { list -> list.map { if (it.id == deviceId) transform(it) else it } }
    }

    private fun replaceLocal(device: Device) {
        devices.update { list -> list.map { if (it.id == device.id) device else it } }
    }

    private suspend fun subscribeToChanges() {
        val channel = realtime.channel("devices-db-changes")
        val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "devices"
        }
        // Subscribe before the initial load, otherwise a change landing between the
        // two is never seen by either path.
        channel.subscribe(blockUntilSubscribed = true)
        refresh()
        changeFlow.collect { action ->
            devices.value = when (action) {
                is PostgresAction.Insert -> devices.value + action.decodeRecord<Device>()
                is PostgresAction.Update -> {
                    val updated = action.decodeRecord<Device>()
                    devices.value.map { if (it.id == updated.id) updated else it }
                }
                is PostgresAction.Delete -> {
                    val deletedId = action.oldRecord["id"]?.toString()?.trim('"')
                    devices.value.filterNot { it.id == deletedId }
                }
                else -> devices.value
            }
        }
    }

    suspend fun setStatus(deviceId: String, status: DeviceStatus) {
        val previous = devices.value.find { it.id == deviceId } ?: return
        patchLocal(deviceId) { it.copy(status = status) }
        try {
            postgrest.from("devices").update({ set("status", status.name) }) {
                filter { eq("id", deviceId) }
            }
            val row = postgrest.from("devices")
                .select { filter { eq("id", deviceId) } }
                .decodeSingle<Device>()
            replaceLocal(row)
        } catch (e: Exception) {
            replaceLocal(previous)
            _errors.tryEmit("Couldn't update ${previous.name}")
        }
    }

    /**
     * Creates a device and returns its server-assigned row.
     *
     * Type-specific extension rows are *not* created here — see
     * [DeviceExtrasRepository.createExtensionRow], which the ViewModel calls next. Kept
     * separate so this stays the single owner of the `devices` table and its cache.
     */
    suspend fun addDevice(
        floorId: String,
        name: String,
        type: DeviceType,
        gridX: Int,
        gridY: Int,
        applianceType: String? = null
    ): Device {
        val created = postgrest.from("devices")
            .insert(DeviceInsert(floorId, name, type, gridX, gridY, applianceType)) {
                select()
            }
            .decodeSingle<Device>()
        devices.update { it + created }
        return created
    }

    suspend fun updateDevice(
        deviceId: String,
        name: String,
        gridX: Int,
        gridY: Int,
        floorId: String,
        applianceType: String? = null
    ) {
        postgrest.from("devices").update({
            set("name", name)
            set("grid_x", gridX)
            set("grid_y", gridY)
            set("floor_id", floorId)
            set("appliance_type", applianceType)
        }) {
            filter { eq("id", deviceId) }
        }
        val row = postgrest.from("devices")
            .select { filter { eq("id", deviceId) } }
            .decodeSingle<Device>()
        replaceLocal(row)
    }

    /** Extension rows, switches, usage logs and alerts cascade with the device. */
    suspend fun deleteDevice(deviceId: String) {
        postgrest.from("devices").delete { filter { eq("id", deviceId) } }
        devices.update { list -> list.filterNot { it.id == deviceId } }
    }

    suspend fun setControlMode(deviceId: String, mode: ControlMode) {
        val previous = devices.value.find { it.id == deviceId } ?: return
        patchLocal(deviceId) { it.copy(controlMode = mode) }
        try {
            postgrest.from("devices").update({ set("control_mode", mode.name) }) {
                filter { eq("id", deviceId) }
            }
            val row = postgrest.from("devices")
                .select { filter { eq("id", deviceId) } }
                .decodeSingle<Device>()
            replaceLocal(row)
        } catch (e: Exception) {
            replaceLocal(previous)
            _errors.tryEmit("Couldn't change mode for ${previous.name}")
        }
    }
}
