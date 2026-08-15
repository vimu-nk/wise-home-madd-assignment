package com.example.wisehome.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wisehome.data.RepositoryProvider
import com.example.wisehome.data.model.Alert
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.repository.AlertRepository
import com.example.wisehome.data.repository.DeviceRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AlertsViewModel(
    private val alertRepository: AlertRepository = RepositoryProvider.alerts,
    private val deviceRepository: DeviceRepository = RepositoryProvider.devices
) : ViewModel() {

    val alerts: StateFlow<List<Alert>> = alertRepository.observeAlerts()
    val devices: StateFlow<List<Device>> = deviceRepository.observeDevices()
    val errors: SharedFlow<String> = alertRepository.errors

    fun setAcknowledged(alertId: String, acknowledged: Boolean) {
        viewModelScope.launch { alertRepository.setAcknowledged(alertId, acknowledged) }
    }

    fun acknowledgeAll() {
        viewModelScope.launch { alertRepository.acknowledgeAll() }
    }
}
