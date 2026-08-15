package com.example.wisehome.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.wisehome.R
import com.example.wisehome.data.model.Alert

/**
 * Posts a system notification when the backend raises an alert.
 *
 * The alerts themselves come from the server — `run_safety_cutoff()` inserts a row
 * when an appliance exceeds its maximum on-duration — so the phone is only the
 * messenger. Notifications appear while the app process is alive; a killed app
 * would need FCM, which this project deliberately does not take on.
 */
object AlertNotifier {

    private const val CHANNEL_ID = "wisehome_safety_alerts"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Safety alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Automatic shut-offs and other safety events in your home"
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun notify(context: Context, alert: Alert) {
        // On API 33+ the user can decline notifications; posting anyway throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("WiseHome safety alert")
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        // The alert id keeps two different alerts from replacing each other, while a
        // repeat of the same alert updates in place instead of stacking.
        NotificationManagerCompat.from(context).notify(alert.id.hashCode(), notification)
    }
}
