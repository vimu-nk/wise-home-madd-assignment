@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.wisehome.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wisehome.data.RepositoryProvider
import com.example.wisehome.data.model.ControlMode
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.DeviceFacts
import com.example.wisehome.data.model.DeviceStatus
import com.example.wisehome.data.model.DeviceType
import com.example.wisehome.data.model.FanSpeed
import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.model.LightSchedule
import com.example.wisehome.data.model.SafetyPreset
import com.example.wisehome.data.model.SensorType
import com.example.wisehome.data.model.Room
import com.example.wisehome.data.model.ThermostatMode
import com.example.wisehome.data.model.UsageLog
import com.example.wisehome.data.repository.DeviceExtrasRepository
import com.example.wisehome.data.repository.DeviceFactsRepository
import com.example.wisehome.data.repository.DeviceRepository
import com.example.wisehome.data.repository.FloorRepository
import com.example.wisehome.data.repository.LightScheduleRepository
import com.example.wisehome.data.repository.RoomRepository
import com.example.wisehome.data.repository.SwitchRepository
import com.example.wisehome.data.repository.UsageRepository
import com.example.wisehome.ui.format.DeviceContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val deviceRepository: DeviceRepository = RepositoryProvider.devices,
    private val floorRepository: FloorRepository = RepositoryProvider.floors,
    private val extrasRepository: DeviceExtrasRepository = RepositoryProvider.extras,
    private val usageRepository: UsageRepository = RepositoryProvider.usage,
    private val switchRepository: SwitchRepository = RepositoryProvider.switches,
    private val factsRepository: DeviceFactsRepository = RepositoryProvider.facts,
    private val roomRepository: RoomRepository = RepositoryProvider.rooms,
    private val lightScheduleRepository: LightScheduleRepository = RepositoryProvider.lightSchedules
) : ViewModel() {

    /** Floors are editable from Settings now, so this follows the repository's live
     *  list rather than a one-shot fetch. */
    val floors: StateFlow<List<Floor>> = floorRepository.observeFloors()

    private val _selectedFloorId = MutableStateFlow<String?>(null)
    val selectedFloorId: StateFlow<String?> = _selectedFloorId.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<String?>(null)

    private val _usageHistoryVisible = MutableStateFlow(false)
    val usageHistoryVisible: StateFlow<Boolean> = _usageHistoryVisible.asStateFlow()

    val errors: Flow<String> =
        merge(deviceRepository.errors, switchRepository.errors, RepositoryProvider.alerts.errors)

    /** Facts for every device, so rooms and grid badges can label without a sheet. */
    val deviceFacts: StateFlow<Map<String, DeviceFacts>> = factsRepository.observeFacts()

    init {
        viewModelScope.launch {
            // Keep a valid selection as floors are added and deleted: default to the
            // first floor, and fall back to it if the selected floor disappears.
            floors.collect { loaded ->
                val current = _selectedFloorId.value
                if (current == null || loaded.none { it.id == current }) {
                    _selectedFloorId.value = loaded.firstOrNull()?.id
                }
            }
        }
    }

    /** Every room in the house; screens filter by floor via [roomsForFloor]. */
    val rooms: StateFlow<List<Room>> = roomRepository.observeRooms()

    fun selectFloor(floorId: String) {
        _selectedFloorId.value = floorId
    }

    val devicesOnSelectedFloor: StateFlow<List<Device>> =
        combine(deviceRepository.observeDevices(), _selectedFloorId) { devices, floorId ->
            if (floorId == null) emptyList() else devices.filter { it.floorId == floorId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedDevice: StateFlow<Device?> =
        combine(deviceRepository.observeDevices(), _selectedDeviceId) { devices, id ->
            devices.find { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Fully derived — no snapshot reads. The old version looked up the device from
     * `observeDevices().value` at tap time and gave up permanently if the list
     * hadn't loaded yet.
     */
    val deviceExtras: StateFlow<DeviceExtrasState> =
        selectedDevice
            .distinctUntilChanged { a, b -> a?.id == b?.id && a?.type == b?.type }
            .flatMapLatest { device ->
                if (device == null) flowOf(DeviceExtrasState.Ready(DeviceContext.Empty))
                else observeContext(device)
                    .map<DeviceContext, DeviceExtrasState> { DeviceExtrasState.Ready(it) }
                    .onStart { emit(DeviceExtrasState.Loading) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceExtrasState.Loading)

    private fun observeContext(device: Device): Flow<DeviceContext> = when (device.type) {
        DeviceType.MULTISWITCH ->
            extrasRepository.observeSwitches(device.id).map { DeviceContext(switches = it) }

        DeviceType.SMART_LOCK ->
            extrasRepository.observeLock(device.id).map { DeviceContext(lock = it) }

        DeviceType.SCHEDULED_SAFETY ->
            extrasRepository.observeSafetyConfig(device.id).map { DeviceContext(safety = it) }

        DeviceType.THERMOSTAT ->
            extrasRepository.observeThermostat(device.id).flatMapLatest { thermostat ->
                val acId = thermostat?.controlsDeviceId
                if (acId == null) {
                    flowOf(DeviceContext(thermostat = thermostat))
                } else {
                    // combine with the shared devices flow rather than reading .value,
                    // so the linked AC device resolves regardless of load order.
                    combine(
                        extrasRepository.observeAcUnit(acId),
                        deviceRepository.observeDevices()
                    ) { acUnit, devices ->
                        DeviceContext(
                            thermostat = thermostat,
                            acUnit = acUnit,
                            acDevice = devices.find { it.id == acId }
                        )
                    }
                }
            }

        DeviceType.AC_UNIT ->
            combine(
                extrasRepository.observeThermostatControlling(device.id),
                extrasRepository.observeAcUnit(device.id)
            ) { thermostat, acUnit ->
                DeviceContext(thermostat = thermostat, acUnit = acUnit, acDevice = device)
            }

        DeviceType.SENSOR ->
            extrasRepository.observeSensor(device.id).map { DeviceContext(sensor = it) }

        DeviceType.CAMERA ->
            extrasRepository.observeCamera(device.id).map { DeviceContext(camera = it) }

        DeviceType.SMART_PLUG_METERED ->
            extrasRepository.observePowerMetrics(device.id).map { DeviceContext(power = it) }

        else -> flowOf(DeviceContext.Empty)
    }

    /** Live history: DB triggers write the row, Realtime delivers it, list grows in place. */
    val usageHistory: StateFlow<List<UsageLog>> =
        combine(_selectedDeviceId, _usageHistoryVisible) { id, visible -> id.takeIf { visible } }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else usageRepository.observeUsageForDevice(id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectDevice(deviceId: String?) {
        _selectedDeviceId.value = deviceId
        _usageHistoryVisible.value = false
    }

    fun toggleUsageHistory() {
        _usageHistoryVisible.value = !_usageHistoryVisible.value
    }

    // ---- Device management ----

    private val _editorError = MutableStateFlow<String?>(null)
    val editorError: StateFlow<String?> = _editorError.asStateFlow()

    fun clearEditorError() { _editorError.value = null }

    /**
     * Creates the device, then its type-specific extension row. If the second step
     * fails the device is removed again rather than left half-built — a multiswitch
     * with no switches or a sensor with no sensor row is inert and confusing.
     */
    fun addDevice(
        floorId: String,
        name: String,
        type: DeviceType,
        gridX: Int,
        gridY: Int,
        sensorType: SensorType = SensorType.MOTION,
        switchCount: Int = 2,
        safetyKind: String = "iron",
        applianceType: String? = null
    ) {
        viewModelScope.launch {
            val created = runCatching {
                deviceRepository.addDevice(floorId, name.trim(), type, gridX, gridY, applianceType)
            }.getOrElse {
                _editorError.value = it.message ?: "That device could not be created"
                return@launch
            }

            runCatching {
                extrasRepository.createExtensionRow(created.id, type, sensorType, switchCount, safetyKind)
            }.onFailure {
                runCatching { deviceRepository.deleteDevice(created.id) }
                _editorError.value = "Couldn't set up the ${deviceTypeName(type)}: ${it.message}"
            }
        }
    }

    fun updateDevice(
        device: Device,
        name: String,
        gridX: Int,
        gridY: Int,
        floorId: String,
        applianceType: String? = device.applianceType
    ) {
        viewModelScope.launch {
            runCatching {
                deviceRepository.updateDevice(device.id, name.trim(), gridX, gridY, floorId, applianceType)
            }.onFailure { _editorError.value = it.message ?: "That change could not be saved" }
        }
    }

    fun deleteDevice(device: Device) {
        viewModelScope.launch {
            runCatching { deviceRepository.deleteDevice(device.id) }
                .onSuccess { if (_selectedDeviceId.value == device.id) _selectedDeviceId.value = null }
                .onFailure { _editorError.value = it.message ?: "That device could not be deleted" }
        }
    }

    private fun deviceTypeName(type: DeviceType) = type.name.lowercase().replace('_', ' ')

    // ---- Scheduled lights ----

    /**
     * Windows for the open device. The app only edits these; the switching is done by
     * the `run_light_schedules()` worker, so lights keep working with the app closed.
     */
    val lightSchedules: StateFlow<List<LightSchedule>> =
        selectedDevice
            .distinctUntilChanged { a, b -> a?.id == b?.id }
            .flatMapLatest { device ->
                if (device?.type != DeviceType.SCHEDULED_LIGHT) flowOf(emptyList())
                else lightScheduleRepository.observeSchedules(device.id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addLightSchedule(deviceId: String, startTime: String, endTime: String, days: List<Int>) {
        viewModelScope.launch {
            lightScheduleRepository.addSchedule(deviceId, startTime, endTime, days)
        }
    }

    fun updateLightSchedule(
        schedule: LightSchedule,
        startTime: String = schedule.startTime,
        endTime: String = schedule.endTime,
        days: List<Int> = schedule.daysOfWeek,
        enabled: Boolean = schedule.enabled
    ) {
        viewModelScope.launch {
            lightScheduleRepository.updateSchedule(schedule, startTime, endTime, days, enabled)
        }
    }

    fun deleteLightSchedule(schedule: LightSchedule) {
        viewModelScope.launch { lightScheduleRepository.deleteSchedule(schedule) }
    }

    // ---- Safety-capped appliances ----

    /** Duration options for the open device's appliance kind (iron, heater, ...). */
    val safetyPreset: StateFlow<SafetyPreset?> =
        deviceExtras
            .map { (it as? DeviceExtrasState.Ready)?.context?.safety?.kind }
            .distinctUntilChanged()
            .map { kind -> kind?.let { runCatching { extrasRepository.getSafetyPreset(it) }.getOrNull() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMaxOnDuration(deviceId: String, seconds: Int) {
        viewModelScope.launch { extrasRepository.setMaxOnDuration(deviceId, seconds) }
    }

    fun setDevicePower(device: Device, on: Boolean) {
        viewModelScope.launch {
            when (device.type) {
                DeviceType.SCHEDULED_SAFETY -> extrasRepository.setIronPower(device.id, on)
                DeviceType.SMART_LOCK -> extrasRepository.setLockState(device.id, on)
                else -> deviceRepository.setStatus(
                    device.id,
                    if (on) DeviceStatus.ON else DeviceStatus.OFF
                )
            }
        }
    }

    fun toggleDevice(device: Device) = setDevicePower(device, device.status != DeviceStatus.ON)

    fun setControlMode(deviceId: String, mode: ControlMode) {
        viewModelScope.launch { deviceRepository.setControlMode(deviceId, mode) }
    }

    fun toggleSwitch(deviceId: String, switchId: String, currentStatus: DeviceStatus) {
        viewModelScope.launch {
            val next = if (currentStatus == DeviceStatus.ON) DeviceStatus.OFF else DeviceStatus.ON
            extrasRepository.setSwitchStatus(deviceId, switchId, next)
        }
    }

    fun setAllSwitches(deviceId: String, on: Boolean) {
        viewModelScope.launch {
            extrasRepository.setAllSwitches(
                deviceId,
                if (on) DeviceStatus.ON else DeviceStatus.OFF
            )
        }
    }

    fun setLockState(deviceId: String, locked: Boolean) {
        viewModelScope.launch { extrasRepository.setLockState(deviceId, locked) }
    }

    fun setThermostatTarget(deviceId: String, targetTempC: Double) {
        viewModelScope.launch {
            extrasRepository.setThermostatTarget(deviceId, targetTempC.coerceIn(10.0, 32.0))
        }
    }

    fun setThermostatMode(deviceId: String, acDeviceId: String?, mode: ThermostatMode) {
        viewModelScope.launch { extrasRepository.setThermostatMode(deviceId, acDeviceId, mode) }
    }

    fun setAcFanSpeed(acDeviceId: String, fanSpeed: FanSpeed) {
        viewModelScope.launch { extrasRepository.setAcFanSpeed(acDeviceId, fanSpeed) }
    }
}
