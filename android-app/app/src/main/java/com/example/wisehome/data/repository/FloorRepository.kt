package com.example.wisehome.data.repository

import com.example.wisehome.data.model.Floor
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest

private val FLOOR_DISPLAY_ORDER = listOf("Ground Floor", "First Floor", "Exterior / Garden")

/** Floors rarely change — fetched once on demand, no Realtime subscription needed. */
class FloorRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest

    suspend fun getFloors(): List<Floor> {
        val floors = postgrest.from("floors").select().decodeList<Floor>()
        return floors.sortedBy { FLOOR_DISPLAY_ORDER.indexOf(it.name).let { i -> if (i == -1) Int.MAX_VALUE else i } }
    }
}
