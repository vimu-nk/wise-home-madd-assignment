package com.example.wisehome.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wisehome.data.AppPreferences
import com.example.wisehome.data.RepositoryProvider
import com.example.wisehome.data.TemperatureUnit
import com.example.wisehome.data.ThemeMode
import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.remote.SupabaseClientProvider
import com.example.wisehome.ui.screens.home.roomLayoutFor
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

    private val _floors = MutableStateFlow<List<Floor>>(emptyList())

    val summary: StateFlow<HomeSummary> =
        combine(_floors, RepositoryProvider.devices.observeDevices()) { floors, devices ->
            HomeSummary(
                floors = floors,
                roomCount = floors.sumOf { roomLayoutFor(it).size },
                deviceCount = devices.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSummary())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch { _floors.value = RepositoryProvider.floors.getFloors() }
    }

    fun setThemeMode(mode: ThemeMode) = AppPreferences.setThemeMode(mode)

    fun setTemperatureUnit(unit: TemperatureUnit) = AppPreferences.setTemperatureUnit(unit)

    fun refreshNow() {
        viewModelScope.launch {
            _refreshing.value = true
            RepositoryProvider.refreshAll()
            _floors.value = RepositoryProvider.floors.getFloors()
            _refreshing.value = false
        }
    }
}
