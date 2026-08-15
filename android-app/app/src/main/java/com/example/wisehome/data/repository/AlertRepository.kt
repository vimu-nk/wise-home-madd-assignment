package com.example.wisehome.data.repository

import com.example.wisehome.data.model.Alert
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
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

class AlertRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val alerts = MutableStateFlow<List<Alert>>(emptyList())
    private var started = false

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * Alerts that arrived while the app was running, for the notification layer.
     *
     * Emitted only from the Realtime INSERT branch, never from [refresh] — a reconnect
     * re-fetches the whole table, and notifying from there would re-announce every
     * historical alert. [notifiedIds] guards the case where the same insert is
     * delivered twice across a socket reconnect.
     */
    private val _newAlerts = MutableSharedFlow<Alert>(extraBufferCapacity = 8)
    val newAlerts: SharedFlow<Alert> = _newAlerts.asSharedFlow()

    private val notifiedIds = mutableSetOf<String>()

    fun observeAlerts(): StateFlow<List<Alert>> {
        if (!started) {
            started = true
            scope.launch { subscribeToChanges() }
        }
        return alerts.asStateFlow()
    }

    suspend fun refresh() {
        runCatching {
            postgrest.from("alerts")
                .select { order("created_at", Order.DESCENDING) }
                .decodeList<Alert>()
        }
            .onSuccess { alerts.value = it }
            .onFailure { _errors.tryEmit("Couldn't load alerts") }
    }

    private fun setAcknowledgedLocal(alertId: String, acknowledged: Boolean) {
        alerts.update { list ->
            list.map { if (it.id == alertId) it.copy(acknowledged = acknowledged) else it }
        }
    }

    private suspend fun subscribeToChanges() {
        val channel = realtime.channel("alerts-db-changes")
        val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "alerts"
        }
        channel.subscribe(blockUntilSubscribed = true)
        refresh()
        changeFlow.collect { action ->
            alerts.value = when (action) {
                is PostgresAction.Insert -> {
                    val inserted = action.decodeRecord<Alert>()
                    if (notifiedIds.add(inserted.id)) _newAlerts.tryEmit(inserted)
                    listOf(inserted) + alerts.value
                }
                is PostgresAction.Update -> {
                    val updated = action.decodeRecord<Alert>()
                    alerts.value.map { if (it.id == updated.id) updated else it }
                }
                is PostgresAction.Delete -> {
                    val deletedId = action.oldRecord["id"]?.toString()?.trim('"')
                    alerts.value.filterNot { it.id == deletedId }
                }
                else -> alerts.value
            }
        }
    }

    suspend fun setAcknowledged(alertId: String, acknowledged: Boolean) {
        val previous = alerts.value.find { it.id == alertId } ?: return
        setAcknowledgedLocal(alertId, acknowledged)
        try {
            postgrest.from("alerts").update({ set("acknowledged", acknowledged) }) {
                filter { eq("id", alertId) }
            }
        } catch (e: Exception) {
            setAcknowledgedLocal(alertId, previous.acknowledged)
            _errors.tryEmit("Couldn't update that alert")
        }
    }

    suspend fun acknowledgeAll() {
        val previous = alerts.value
        alerts.update { list -> list.map { it.copy(acknowledged = true) } }
        try {
            postgrest.from("alerts").update({ set("acknowledged", true) }) {
                filter { eq("acknowledged", false) }
            }
        } catch (e: Exception) {
            alerts.value = previous
            _errors.tryEmit("Couldn't mark all as read")
        }
    }
}
