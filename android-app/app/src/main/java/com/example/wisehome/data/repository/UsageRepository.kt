package com.example.wisehome.data.repository

import com.example.wisehome.data.model.UsageLog
import com.example.wisehome.data.remote.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/**
 * Usage history. Read on demand, but kept live while a device's history is open
 * so pressing a control makes the new event appear in place (the DB triggers in
 * migration 20260816000002 write the row, Realtime delivers it).
 */
class UsageRepository {

    private val postgrest = SupabaseClientProvider.client.postgrest
    private val realtime = SupabaseClientProvider.client.realtime

    suspend fun getUsageForDevice(deviceId: String, limit: Long = 50): List<UsageLog> =
        runCatching {
            postgrest.from("usage_logs")
                .select {
                    filter { eq("device_id", deviceId) }
                    order("created_at", Order.DESCENDING)
                    limit(limit)
                }
                .decodeList<UsageLog>()
        }.getOrDefault(emptyList())

    fun observeUsageForDevice(deviceId: String, limit: Long = 50): Flow<List<UsageLog>> =
        channelFlow {
            send(getUsageForDevice(deviceId, limit))

            val channel = realtime.channel("usage-$deviceId")
            try {
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "usage_logs"
                }
                channel.subscribe(blockUntilSubscribed = true)
                send(getUsageForDevice(deviceId, limit))
                changes.collect { send(getUsageForDevice(deviceId, limit)) }
            } finally {
                // Must be removeChannel under NonCancellable: the suspending
                // unsubscribe() would throw CancellationException immediately and
                // leak the channel.
                withContext(NonCancellable) { realtime.removeChannel(channel) }
            }
        }
}
