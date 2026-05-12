package com.sohil.icaibatchmonitor

import java.util.UUID

/**
 * Represents a single monitoring configuration chosen by the user.
 * Each config tracks one Region + POU + Course combination.
 */
data class MonitorConfig(
    val id: String = UUID.randomUUID().toString(),

    // User-visible labels
    val regionLabel: String,
    val pouLabel: String,
    val courseLabel: String,

    // Actual form values used in POST requests
    val regionValue: String,
    val pouValue: String,
    val courseValue: String,

    // Check interval in minutes
    val intervalMinutes: Int = 30,

    // Snapshot of last known batch keys (to detect new ones)
    // Each key is a unique string for a batch (e.g. "BatchName|StartDate")
    val lastKnownBatchKeys: List<String> = emptyList(),

    // Timestamp of last successful check
    val lastCheckedAt: Long = 0L,

    // Whether this config is actively being monitored
    val isActive: Boolean = true
) {
    /** Short display name shown in the UI */
    fun displayName() = "$courseLabel — $pouLabel ($regionLabel)"
}
