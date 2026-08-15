package com.example.wisehome.data.repository

import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceSwitch
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The third app-wide Realtime channel from section 3 of the spec: every
 * multiswitch child switch, in one subscription. Being global (rather than
 * per-device) means a room card can show "2 of 3 on" without opening a sheet.
 */
class SwitchRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val switches = MutableStateFlow<List<DeviceSwitch>>(emptyList())
    private var started = false

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun observeSwitches(): StateFlow<List<DeviceSwitch>> {
        if (!started) {
            started = true
            scope.launch { subscribeToChanges() }
        }
        return switches.asStateFlow()
    }

    fun switchesFor(deviceId: String): Flow<List<DeviceSwitch>> =
        observeSwitches()
            .map { all -> all.filter { it.deviceId == deviceId }.sortedBy { it.switchIndex } }
            .distinctUntilChanged()

    suspend fun refresh() {
        runCatching { postgrest.from("device_switches").select().decodeList<DeviceSwitch>() }
            .onSuccess { switches.value = it }
            .onFailure { _errors.tryEmit("Couldn't load switches") }
    }

    private fun replaceLocal(sw: DeviceSwitch) {
        switches.update { list -> list.map { if (it.id == sw.id) sw else it } }
    }

    private suspend fun subscribeToChanges() {
        val channel = realtime.channel("switches-db-changes")
        val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "device_switches"
        }
        channel.subscribe(blockUntilSubscribed = true)
        refresh()
        changeFlow.collect { action ->
            switches.value = when (action) {
                is PostgresAction.Insert -> switches.value + action.decodeRecord<DeviceSwitch>()
                is PostgresAction.Update -> {
                    val updated = action.decodeRecord<DeviceSwitch>()
                    switches.value.map { if (it.id == updated.id) updated else it }
                }
                is PostgresAction.Delete -> {
                    val deletedId = action.oldRecord["id"]?.toString()?.trim('"')
                    switches.value.filterNot { it.id == deletedId }
                }
                else -> switches.value
            }
        }
    }

    suspend fun setStatus(switchId: String, status: DeviceStatus) {
        val previous = switches.value.find { it.id == switchId } ?: return
        replaceLocal(previous.copy(status = status))
        try {
            postgrest.from("device_switches").update({ set("status", status.name) }) {
                filter { eq("id", switchId) }
            }
            val row = postgrest.from("device_switches")
                .select { filter { eq("id", switchId) } }
                .decodeSingle<DeviceSwitch>()
            replaceLocal(row)
        } catch (e: Exception) {
            replaceLocal(previous)
            _errors.tryEmit("Couldn't switch ${previous.label ?: "switch ${previous.switchIndex}"}")
        }
    }
}
