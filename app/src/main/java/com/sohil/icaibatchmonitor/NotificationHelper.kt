package com.sohil.icaibatchmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Handles all notification creation and display.
 */
object NotificationHelper {

    private const val CHANNEL_ID = "icai_batch_alerts"
    private const val CHANNEL_NAME = "ICAI Batch Alerts"
    private const val CHANNEL_DESC = "Notifies when ICAI batches become available"

    /** Call this once at app start (idempotent) */
    fun createNotificationChannel(context: Context) {
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
            description = CHANNEL_DESC
            enableVibration(true)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Fire a notification when new batches are detected.
     * @param config The monitor config that triggered this
     * @param newBatches List of new batch descriptions
     */
    fun notifyNewBatches(
        context: Context,
        config: MonitorConfig,
        newBatches: List<String>
    ) {
        // Tapping the notification opens the app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val batchList = newBatches.take(5).joinToString("\n• ", prefix = "• ")
        val moreText = if (newBatches.size > 5) "\n...and ${newBatches.size - 5} more" else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🎉 ICAI Batch Available!")
            .setContentText("${newBatches.size} new batch(es) for ${config.courseLabel}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${config.displayName()}\n\nNew batches:\n$batchList$moreText")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(config.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+ — handle gracefully
        }
    }

    /**
     * Fire a "status change" notification when a batch that was full now has seats.
     */
    fun notifyBatchStatusChange(
        context: Context,
        config: MonitorConfig,
        batchDescription: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ Batch Opened!")
            .setContentText(batchDescription)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Batch now open:\n$batchDescription\n\nFor: ${config.displayName()}")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify((config.id + batchDescription).hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }
}
