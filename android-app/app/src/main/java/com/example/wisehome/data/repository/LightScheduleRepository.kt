package com.example.wisehome.data.repository

import com.example.wisehome.data.model.LightSchedule
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class LightScheduleInsert(
    @SerialName("device_id") val deviceId: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("days_of_week") val daysOfWeek: List<Int>,
    val enabled: Boolean
)

/**
 * Daily on/off windows for `scheduled_light` devices.
 *
 * The app only edits these rows — the switching itself is done server-side by the
 * `run_light_schedules()` pg_cron worker, so lights keep working with the app closed.
 * A window is applied only while the device is in AUTO control mode.
 *
 * Times are stored as Postgres `time` values ("HH:MM:SS") and evaluated by the worker
 * in Asia/Colombo, so what the user picks is what they see happen locally.
 */
class LightScheduleRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime

    private val invalidations = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun observeSchedules(deviceId: String): Flow<List<LightSchedule>> = channelFlow {
        val key = "light-schedules-$deviceId"
        send(getSchedules(deviceId))

        launch {
            invalidations.filter { it == key }.collect { send(getSchedules(deviceId)) }
        }

        val channel = realtime.channel(key)
        try {
            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "light_schedules"
            }
            channel.subscribe(blockUntilSubscribed = true)
            send(getSchedules(deviceId))
            changes.collect { send(getSchedules(deviceId)) }
        } finally {
            // Same reasoning as DeviceExtrasRepository: unsubscribe would throw
            // CancellationException the moment this scope is cancelled.
            withContext(NonCancellable) { realtime.removeChannel(channel) }
        }
    }

    suspend fun getSchedules(deviceId: String): List<LightSchedule> =
        postgrest.from("light_schedules").select {
            filter { eq("device_id", deviceId) }
        }.decodeList<LightSchedule>()

    suspend fun addSchedule(
        deviceId: String,
        startTime: String,
        endTime: String,
        daysOfWeek: List<Int>,
        enabled: Boolean = true
    ) {
        postgrest.from("light_schedules").insert(
            LightScheduleInsert(deviceId, normalize(startTime), normalize(endTime), daysOfWeek.sorted(), enabled)
        )
        invalidations.tryEmit("light-schedules-$deviceId")
    }

    suspend fun updateSchedule(
        schedule: LightSchedule,
        startTime: String = schedule.startTime,
        endTime: String = schedule.endTime,
        daysOfWeek: List<Int> = schedule.daysOfWeek,
        enabled: Boolean = schedule.enabled
    ) {
        postgrest.from("light_schedules").update({
            set("start_time", normalize(startTime))
            set("end_time", normalize(endTime))
            set("days_of_week", daysOfWeek.sorted())
            set("enabled", enabled)
        }) {
            filter { eq("id", schedule.id) }
        }
        invalidations.tryEmit("light-schedules-${schedule.deviceId}")
    }

    suspend fun deleteSchedule(schedule: LightSchedule) {
        postgrest.from("light_schedules").delete { filter { eq("id", schedule.id) } }
        invalidations.tryEmit("light-schedules-${schedule.deviceId}")
    }

    /** Accepts "HH:MM" from a time picker as well as the "HH:MM:SS" Postgres returns. */
    private fun normalize(time: String): String =
        if (time.count { it == ':' } == 1) "$time:00" else time
}
