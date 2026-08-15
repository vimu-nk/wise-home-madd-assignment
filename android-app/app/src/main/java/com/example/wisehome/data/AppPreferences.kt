package com.example.wisehome.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

/**
 * Small persisted preference store. SharedPreferences is plenty here — these are
 * two enums, and adding DataStore would pull in a dependency for no benefit.
 */
object AppPreferences {

    private const val FILE = "wisehome_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_UNIT = "temperature_unit"

    private var prefs: android.content.SharedPreferences? = null

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _temperatureUnit = MutableStateFlow(TemperatureUnit.CELSIUS)
    val temperatureUnit: StateFlow<TemperatureUnit> = _temperatureUnit.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = store
        _themeMode.value = runCatching {
            ThemeMode.valueOf(store.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        _temperatureUnit.value = runCatching {
            TemperatureUnit.valueOf(store.getString(KEY_UNIT, null) ?: TemperatureUnit.CELSIUS.name)
        }.getOrDefault(TemperatureUnit.CELSIUS)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString(KEY_THEME, mode.name)?.apply()
    }

    fun setTemperatureUnit(unit: TemperatureUnit) {
        _temperatureUnit.value = unit
        prefs?.edit()?.putString(KEY_UNIT, unit.name)?.apply()
    }
}
