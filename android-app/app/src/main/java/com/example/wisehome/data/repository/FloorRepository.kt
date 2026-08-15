package com.example.wisehome.data.repository

import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val FLOOR_DISPLAY_ORDER = listOf("Ground Floor", "First Floor", "Exterior / Garden")

/** Insert payload — the DB generates `id` and `created_at`. */
@Serializable
private data class FloorInsert(
    val name: String,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("grid_cols") val gridCols: Int,
    @SerialName("grid_rows") val gridRows: Int
)

/**
 * Floors, now editable from the app.
 *
 * Floors were previously fetched once and never watched, on the assumption they were
 * seed-only data. They are managed from Settings now, so changes have to reach every
 * screen (and every other device) — hence the shared StateFlow and the Realtime
 * subscription, matching [DeviceRepository].
 *
 * Ordering keeps the three seeded floors in their natural order and appends anything
 * created later, so a new floor shows up as the last tab rather than in the middle.
 */
class FloorRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val floors = MutableStateFlow<List<Floor>>(emptyList())
    private var started = false

    fun observeFloors(): StateFlow<List<Floor>> {
        if (!started) {
            started = true
            scope.launch {
                refresh()
                subscribeToChanges()
            }
        }
        return floors.asStateFlow()
    }

    suspend fun getFloors(): List<Floor> = sorted(
        postgrest.from("floors").select().decodeList<Floor>()
    )

    suspend fun refresh() {
        runCatching { getFloors() }.onSuccess { floors.value = it }
    }

    suspend fun addFloor(name: String, imageUrl: String, gridCols: Int, gridRows: Int) {
        postgrest.from("floors").insert(FloorInsert(name, imageUrl, gridCols, gridRows))
        refresh()
    }

    suspend fun updateFloor(id: String, name: String, imageUrl: String, gridCols: Int, gridRows: Int) {
        postgrest.from("floors").update({
            set("name", name)
            set("image_url", imageUrl)
            set("grid_cols", gridCols)
            set("grid_rows", gridRows)
        }) {
            filter { eq("id", id) }
        }
        refresh()
    }

    /** Cascades to that floor's rooms and devices — the caller must confirm first. */
    suspend fun deleteFloor(id: String) {
        postgrest.from("floors").delete { filter { eq("id", id) } }
        refresh()
    }

    private fun sorted(list: List<Floor>): List<Floor> =
        list.sortedWith(
            compareBy(
                { FLOOR_DISPLAY_ORDER.indexOf(it.name).let { i -> if (i == -1) Int.MAX_VALUE else i } },
                { it.name }
            )
        )

    private suspend fun subscribeToChanges() {
        val channel = realtime.channel("floors-db-changes")
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "floors"
        }
        channel.subscribe(blockUntilSubscribed = true)
        refresh() // closes the subscribe/fetch race
        changes.collect { refresh() }
    }
}
