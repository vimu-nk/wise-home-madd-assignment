package com.example.wisehome.ui.screens.home

import com.example.wisehome.ui.format.DeviceContext

/**
 * Loading state for a device's type-specific data.
 *
 * `Loading` must be distinguishable from `Ready(empty)` — previously the UI only
 * matched concrete subtypes, so a device with genuinely no extension row showed
 * "Loading…" forever.
 */
sealed interface DeviceExtrasState {
    data object Loading : DeviceExtrasState
    data class Ready(val context: DeviceContext) : DeviceExtrasState
}
