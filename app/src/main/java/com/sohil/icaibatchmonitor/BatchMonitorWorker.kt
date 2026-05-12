package com.sohil.icaibatchmonitor

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker.
 * Runs in the background every [intervalMinutes] minutes.
 * For each active MonitorConfig:
 *   1. Fetches current batches from ICAI website
 *   2. Compares with lastKnownBatchKeys
 *   3. Fires a notification if new batches appeared
 *   4. Updates the stored snapshot
 */
class BatchMonitorWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        const val TAG = "BatchMonitorWorker"
        const val WORK_NAME = "icai_batch_monitor"

        /**
         * Schedule (or re-schedule) the periodic work.
         * Uses KEEP policy so existing work isn't cancelled unless interval changes.
         */
        fun schedule(context: Context, intervalMinutes: Long = 30) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BatchMonitorWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "Scheduled batch monitor worker every $intervalMinutes minutes")
        }

        /** Cancel all scheduled monitoring work */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled batch monitor worker")
        }

        /** Force an immediate one-time check (for testing/manual refresh) */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<BatchMonitorWorker>()
                .setConstraints(constraints)
                .addTag("$TAG-manual")
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Triggered manual check")
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "Starting batch check...")

        val prefs = PreferencesManager(context)
        val scraper = ICAIScraper()
        val configs = prefs.getConfigs().filter { it.isActive }

        if (configs.isEmpty()) {
            Log.d(TAG, "No active configs to check")
            return Result.success()
        }

        var anyError = false

        configs.forEach { config ->
            try {
                Log.d(TAG, "Checking: ${config.displayName()}")

                val batches = scraper.getBatches(
                    region = config.regionValue,
                    pou = config.pouValue,
                    course = config.courseValue
                )

                val currentKeys = batches.map { it.uniqueKey() }
                val previousKeys = config.lastKnownBatchKeys

                if (previousKeys.isEmpty()) {
                    // First-ever check: just save state, no notification
                    Log.d(TAG, "First check for ${config.displayName()}, saving ${batches.size} batches")
                    prefs.updateBatchSnapshot(config.id, currentKeys)
                } else {
                    // Find keys that are new (not seen before)
                    val newKeys = currentKeys.filter { it !in previousKeys }

                    if (newKeys.isNotEmpty()) {
                        Log.d(TAG, "Found ${newKeys.size} new batches for ${config.displayName()}!")

                        // Get full BatchInfo for the new keys
                        val newBatches = batches.filter { it.uniqueKey() in newKeys }
                        val descriptions = newBatches.map { batch ->
                            buildString {
                                append(batch.batchNo)
                                if (batch.startDate.isNotBlank()) append(" | ${batch.startDate}")
                                if (batch.endDate.isNotBlank()) append(" – ${batch.endDate}")
                                if (batch.venue.isNotBlank()) append(" @ ${batch.venue}")
                                if (batch.availableSeats.isNotBlank()) append(" [${batch.availableSeats} seats]")
                            }.trim().ifBlank { newKeys.first() }
                        }

                        NotificationHelper.notifyNewBatches(context, config, descriptions)
                        prefs.updateBatchSnapshot(config.id, currentKeys)
                    } else {
                        // No new batches, just update the timestamp
                        prefs.updateBatchSnapshot(config.id, currentKeys)
                        Log.d(TAG, "No new batches for ${config.displayName()}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking ${config.displayName()}: ${e.message}")
                anyError = true
            }
        }

        return if (anyError) Result.retry() else Result.success()
    }
}
