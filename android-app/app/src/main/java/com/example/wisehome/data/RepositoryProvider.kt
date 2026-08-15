package com.example.wisehome.data

import com.example.wisehome.data.remote.SupabaseClientProvider
import com.example.wisehome.data.repository.AlertRepository
import com.example.wisehome.data.repository.DeviceExtrasRepository
import com.example.wisehome.data.repository.DeviceFactsRepository
import com.example.wisehome.data.repository.DeviceRepository
import com.example.wisehome.data.repository.FloorRepository
import com.example.wisehome.data.repository.SwitchRepository
import com.example.wisehome.data.repository.UsageRepository
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide repository singletons.
 *
 * These MUST be shared. When each ViewModel built its own via default constructor
 * args there were two DeviceRepository instances holding two independent caches
 * and opening two Realtime channels with the same topic name — so optimistic
 * updates in one screen were invisible to every other screen.
 */
object RepositoryProvider {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val devices by lazy { DeviceRepository() }
    val switches by lazy { SwitchRepository() }
    val alerts by lazy { AlertRepository() }
    val floors by lazy { FloorRepository() }
    val usage by lazy { UsageRepository() }
    val facts by lazy { DeviceFactsRepository(switches) }
    val extras by lazy { DeviceExtrasRepository(devices, switches) }

    private val _lastSyncedAt = MutableStateFlow<String?>(null)
    val lastSyncedAt: StateFlow<String?> = _lastSyncedAt.asStateFlow()

    /** Re-read everything. Called on app resume and whenever Realtime reconnects. */
    suspend fun refreshAll() {
        devices.refresh()
        switches.refresh()
        alerts.refresh()
        facts.refresh()
        _lastSyncedAt.value = nowIso()
    }

    /**
     * Open the websocket and keep it open.
     *
     * Don't rely on connect-on-subscribe: whether the socket is up then depends on
     * which screen the user happens to open first, so the connection indicator can
     * read "Offline" while everything else works. Connecting explicitly at startup
     * makes the socket state deterministic.
     */
    fun startConnectionWatch() {
        scope.launch {
            runCatching { SupabaseClientProvider.client.realtime.connect() }
        }
        scope.launch {
            SupabaseClientProvider.client.realtime.status.collect { status ->
                if (status == Realtime.Status.CONNECTED) {
                    refreshAll()
                }
            }
        }
    }
}
