package com.example.wisehome.data.repository

import com.example.wisehome.data.model.Room
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

@Serializable
private data class RoomInsert(
    @SerialName("floor_id") val floorId: String,
    val label: String,
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int
)

/**
 * Room rectangles per floor.
 *
 * One subscription for every room in the house, filtered by floor client-side — the
 * table is tiny and this matches how [DeviceRepository] handles devices. Callers get
 * rooms in creation order; overlapping rectangles are allowed, and a device inside
 * two rooms is resolved by first match, which is the behaviour the hardcoded layouts
 * had.
 */
class RoomRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val rooms = MutableStateFlow<List<Room>>(emptyList())
    private var started = false

    fun observeRooms(): StateFlow<List<Room>> {
        if (!started) {
            started = true
            scope.launch {
                refresh()
                subscribeToChanges()
            }
        }
        return rooms.asStateFlow()
    }

    suspend fun refresh() {
        runCatching { postgrest.from("rooms").select().decodeList<Room>() }
            .onSuccess { rooms.value = it }
    }

    suspend fun addRoom(floorId: String, label: String, x0: Int, y0: Int, x1: Int, y1: Int) {
        postgrest.from("rooms").insert(
            RoomInsert(floorId, label, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
        )
        refresh()
    }

    suspend fun updateRoom(id: String, label: String, x0: Int, y0: Int, x1: Int, y1: Int) {
        postgrest.from("rooms").update({
            set("label", label)
            set("x0", minOf(x0, x1))
            set("y0", minOf(y0, y1))
            set("x1", maxOf(x0, x1))
            set("y1", maxOf(y0, y1))
        }) {
            filter { eq("id", id) }
        }
        refresh()
    }

    /** Devices are not deleted with the room — they keep their grid cell and simply
     *  fall outside every room until one covers them again. */
    suspend fun deleteRoom(id: String) {
        postgrest.from("rooms").delete { filter { eq("id", id) } }
        refresh()
    }

    private suspend fun subscribeToChanges() {
        val channel = realtime.channel("rooms-db-changes")
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "rooms"
        }
        channel.subscribe(blockUntilSubscribed = true)
        refresh()
        changes.collect { refresh() }
    }
}
