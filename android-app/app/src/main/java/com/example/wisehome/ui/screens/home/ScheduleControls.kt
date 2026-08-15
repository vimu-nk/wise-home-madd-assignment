@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wisehome.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wisehome.data.model.ControlMode
import com.example.wisehome.data.model.Device
import com.example.wisehome.data.model.LightSchedule
import com.example.wisehome.data.model.SafetyConfig
import com.example.wisehome.ui.components.ControlRow
import com.example.wisehome.ui.components.SectionCard
import com.example.wisehome.ui.theme.Space

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Daily on/off windows for a scheduled light.
 *
 * The app writes windows only — `run_light_schedules()` on the server does the
 * switching every minute, so a light still follows its schedule with the phone off.
 * Windows apply only while the device is in AUTO; switching to MANUAL is the
 * documented way to take over, which is why that state is called out here.
 */
@Composable
fun LightScheduleCard(device: Device, viewModel: HomeViewModel) {
    val schedules by viewModel.lightSchedules.collectAsState()
    var editing by remember { mutableStateOf<LightSchedule?>(null) }
    var adding by remember { mutableStateOf(false) }

    SectionCard(title = "Schedule") {
        if (device.controlMode == ControlMode.MANUAL) {
            Text(
                "This light is in Manual mode, so its schedule is paused. Switch to Auto " +
                    "below to hand control back to the schedule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (schedules.isEmpty()) {
            Text(
                "No windows yet. Add one and the light will switch itself on and off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        schedules.forEachIndexed { index, schedule ->
            ControlRow(
                title = "${shortTime(schedule.startTime)} – ${shortTime(schedule.endTime)}",
                subtitle = daysSummary(schedule.daysOfWeek)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = schedule.enabled,
                        onCheckedChange = { viewModel.updateLightSchedule(schedule, enabled = it) }
                    )
                    TextButton(onClick = { editing = schedule }) { Text("Edit") }
                }
            }
            if (index != schedules.lastIndex) HorizontalDivider()
        }

        TextButton(onClick = { adding = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  Add window")
        }
    }

    if (adding) {
        ScheduleEditorDialog(
            schedule = null,
            onDismiss = { adding = false },
            onDelete = null,
            onSave = { start, end, days ->
                viewModel.addLightSchedule(device.id, start, end, days)
                adding = false
            }
        )
    }

    editing?.let { schedule ->
        ScheduleEditorDialog(
            schedule = schedule,
            onDismiss = { editing = null },
            onDelete = {
                viewModel.deleteLightSchedule(schedule)
                editing = null
            },
            onSave = { start, end, days ->
                viewModel.updateLightSchedule(schedule, startTime = start, endTime = end, days = days)
                editing = null
            }
        )
    }
}

@Composable
private fun ScheduleEditorDialog(
    schedule: LightSchedule?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (start: String, end: String, days: List<Int>) -> Unit
) {
    val startState = rememberTimePickerState(
        initialHour = hourOf(schedule?.startTime ?: "18:00:00"),
        initialMinute = minuteOf(schedule?.startTime ?: "18:00:00"),
        is24Hour = true
    )
    val endState = rememberTimePickerState(
        initialHour = hourOf(schedule?.endTime ?: "23:00:00"),
        initialMinute = minuteOf(schedule?.endTime ?: "23:00:00"),
        is24Hour = true
    )
    var days by remember { mutableStateOf(schedule?.daysOfWeek ?: (1..7).toList()) }
    // Two pickers side by side would be unreadable on a phone, so only one shows.
    var editingEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (schedule == null) "Add window" else "Edit window") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    FilterChip(
                        selected = !editingEnd,
                        onClick = { editingEnd = false },
                        label = { Text("On at ${pad(startState.hour)}:${pad(startState.minute)}") }
                    )
                    FilterChip(
                        selected = editingEnd,
                        onClick = { editingEnd = true },
                        label = { Text("Off at ${pad(endState.hour)}:${pad(endState.minute)}") }
                    )
                }

                TimePicker(state = if (editingEnd) endState else startState)

                Text("Repeats", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    DAY_LABELS.forEachIndexed { index, label ->
                        val day = index + 1 // 1 = Monday, matching Postgres isodow
                        FilterChip(
                            selected = day in days,
                            onClick = {
                                days = if (day in days) days - day else (days + day).sorted()
                            },
                            label = { Text(label.take(1)) }
                        )
                    }
                }

                if (days.isEmpty()) {
                    Text(
                        "Pick at least one day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val crossesMidnight =
                    startState.hour * 60 + startState.minute > endState.hour * 60 + endState.minute
                if (crossesMidnight) {
                    Text(
                        "This window runs past midnight — it ends the following morning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = days.isNotEmpty(),
                onClick = {
                    onSave(
                        "${pad(startState.hour)}:${pad(startState.minute)}:00",
                        "${pad(endState.hour)}:${pad(endState.minute)}:00",
                        days
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/**
 * Maximum on-duration for a hazard appliance.
 *
 * Options come from `safety_presets` keyed by appliance kind, so an iron is offered
 * minutes and a space heater hours. The cap is enforced server-side by
 * `run_safety_cutoff()`; this only chooses the number.
 */
@Composable
fun SafetyDurationCard(device: Device, config: SafetyConfig, viewModel: HomeViewModel) {
    val preset by viewModel.safetyPreset.collectAsState()
    var custom by remember { mutableStateOf(false) }

    SectionCard(title = "Safety cut-off") {
        Text(
            "Switches off automatically after ${formatMinutes(config.maxOnDurationSeconds)}.",
            style = MaterialTheme.typography.bodyMedium
        )

        val options = preset?.optionsSeconds.orEmpty()
        if (options.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                options.forEach { seconds ->
                    FilterChip(
                        selected = seconds == config.maxOnDurationSeconds && !custom,
                        onClick = {
                            custom = false
                            viewModel.setMaxOnDuration(device.id, seconds)
                        },
                        label = { Text(formatMinutes(seconds)) }
                    )
                }
                AssistChip(onClick = { custom = true }, label = { Text("Custom") })
            }
        }

        if (custom) {
            CustomDurationRow(
                initialMinutes = config.maxOnDurationSeconds / 60,
                onApply = { minutes ->
                    viewModel.setMaxOnDuration(device.id, minutes * 60)
                    custom = false
                }
            )
        }

        preset?.let {
            Text(
                "Defaults for a ${it.label.lowercase()}: ${formatMinutes(it.defaultSeconds)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CustomDurationRow(initialMinutes: Int, onApply: (Int) -> Unit) {
    var text by remember { mutableStateOf(initialMinutes.toString()) }
    val minutes = text.toIntOrNull()
    // 8 hours is well past any sane appliance cap, and 0 would cut off instantly.
    val valid = minutes != null && minutes in 1..480

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier.fillMaxWidth().padding(top = Space.s)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(3) },
            label = { Text("Minutes") },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(140.dp)
        )
        TextButton(enabled = valid, onClick = { onApply(minutes!!) }) { Text("Apply") }
    }
}

// ---- formatting helpers ----

private fun pad(value: Int): String = value.toString().padStart(2, '0')

/** "18:00:00" -> "18:00" */
private fun shortTime(time: String): String = time.take(5)

private fun hourOf(time: String): Int = time.take(2).toIntOrNull() ?: 0

private fun minuteOf(time: String): Int = time.drop(3).take(2).toIntOrNull() ?: 0

private fun daysSummary(days: List<Int>): String = when {
    days.size == 7 -> "Every day"
    days.sorted() == listOf(1, 2, 3, 4, 5) -> "Weekdays"
    days.sorted() == listOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(", ") { DAY_LABELS.getOrElse(it - 1) { "?" } }
}

private fun formatMinutes(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}
