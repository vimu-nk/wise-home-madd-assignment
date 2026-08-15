package com.example.wisehome.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wisehome.data.AppPreferences
import com.example.wisehome.data.RepositoryProvider
import com.example.wisehome.data.TemperatureUnit
import com.example.wisehome.data.ThemeMode
import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.model.Room
import com.example.wisehome.data.remote.SupabaseClientProvider

import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeSummary(
    val floors: List<Floor> = emptyList(),
    val roomCount: Int = 0,
    val deviceCount: Int = 0
)

class SettingsViewModel : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = AppPreferences.themeMode
    val temperatureUnit: StateFlow<TemperatureUnit> = AppPreferences.temperatureUnit
    val lastSyncedAt: StateFlow<String?> = RepositoryProvider.lastSyncedAt

    val connectionStatus: StateFlow<Realtime.Status> =
        SupabaseClientProvider.client.realtime.status
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Realtime.Status.DISCONNECTED)

    private val floorRepository = RepositoryProvider.floors
    private val roomRepository = RepositoryProvider.rooms

    val floors: StateFlow<List<Floor>> = floorRepository.observeFloors()
    val rooms: StateFlow<List<Room>> = roomRepository.observeRooms()

    val summary: StateFlow<HomeSummary> =
        combine(floors, rooms, RepositoryProvider.devices.observeDevices()) { floors, rooms, devices ->
            HomeSummary(
                floors = floors,
                roomCount = rooms.size,
                deviceCount = devices.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSummary())

    private val _editorError = MutableStateFlow<String?>(null)
    val editorError: StateFlow<String?> = _editorError.asStateFlow()

    fun clearEditorError() { _editorError.value = null }

    // ---- Floor management ----

    fun addFloor(name: String, imageUrl: String, gridCols: Int, gridRows: Int) = edit {
        floorRepository.addFloor(name.trim(), imageUrl, gridCols, gridRows)
    }

    fun updateFloor(floor: Floor, name: String, imageUrl: String, gridCols: Int, gridRows: Int) = edit {
        floorRepository.updateFloor(floor.id, name.trim(), imageUrl, gridCols, gridRows)
    }

    /** Cascades to the floor's rooms and devices — callers must confirm first. */
    fun deleteFloor(floor: Floor) = edit { floorRepository.deleteFloor(floor.id) }

    // ---- Room management ----

    fun addRoom(floorId: String, label: String, x0: Int, y0: Int, x1: Int, y1: Int) = edit {
        roomRepository.addRoom(floorId, label.trim(), x0, y0, x1, y1)
    }

    fun updateRoom(room: Room, label: String, x0: Int, y0: Int, x1: Int, y1: Int) = edit {
        roomRepository.updateRoom(room.id, label.trim(), x0, y0, x1, y1)
    }

    fun deleteRoom(room: Room) = edit { roomRepository.deleteRoom(room.id) }

    /**
     * Surfaces write failures instead of swallowing them. A duplicate room label or a
     * name colliding on (floor, label) comes back as a Postgres error, and silently
     * doing nothing would look like the button was broken.
     */
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { _editorError.value = it.message ?: "That change could not be saved" }
        }
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = AppPreferences.setThemeMode(mode)

    fun setTemperatureUnit(unit: TemperatureUnit) = AppPreferences.setTemperatureUnit(unit)

    /** refreshAll() already re-reads floors and rooms, and both are observed. */
    fun refreshNow() {
        viewModelScope.launch {
            _refreshing.value = true
            RepositoryProvider.refreshAll()
            _refreshing.value = false
        }
    }
}
