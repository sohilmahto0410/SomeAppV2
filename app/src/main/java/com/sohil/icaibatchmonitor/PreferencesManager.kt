package com.sohil.icaibatchmonitor

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages persistent storage for monitor configurations using SharedPreferences + Gson.
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("icai_batch_monitor", Context.MODE_PRIVATE)

    private val gson = Gson()

    companion object {
        private const val KEY_CONFIGS = "monitor_configs"
        private const val KEY_MONITORING_ACTIVE = "monitoring_active"
    }

    // ─── Monitor Configs ────────────────────────────────────────────────────────

    /** Load all saved monitor configurations */
    fun getConfigs(): MutableList<MonitorConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<MonitorConfig>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Save the full list of configs */
    fun saveConfigs(configs: List<MonitorConfig>) {
        prefs.edit().putString(KEY_CONFIGS, gson.toJson(configs)).apply()
    }

    /** Add a new config. Returns false if an identical one already exists. */
    fun addConfig(config: MonitorConfig): Boolean {
        val configs = getConfigs()
        // Check for duplicate (same region+pou+course)
        val exists = configs.any {
            it.regionValue == config.regionValue &&
            it.pouValue == config.pouValue &&
            it.courseValue == config.courseValue
        }
        if (exists) return false
        configs.add(config)
        saveConfigs(configs)
        return true
    }

    /** Remove a config by its ID */
    fun removeConfig(id: String) {
        val configs = getConfigs()
        configs.removeAll { it.id == id }
        saveConfigs(configs)
    }

    /** Update a specific config (matched by ID) */
    fun updateConfig(updated: MonitorConfig) {
        val configs = getConfigs()
        val idx = configs.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            configs[idx] = updated
            saveConfigs(configs)
        }
    }

    /** Update the lastKnownBatchKeys and lastCheckedAt for a config */
    fun updateBatchSnapshot(id: String, batchKeys: List<String>) {
        val configs = getConfigs()
        val idx = configs.indexOfFirst { it.id == id }
        if (idx >= 0) {
            configs[idx] = configs[idx].copy(
                lastKnownBatchKeys = batchKeys,
                lastCheckedAt = System.currentTimeMillis()
            )
            saveConfigs(configs)
        }
    }

    // ─── Global Monitoring Toggle ────────────────────────────────────────────────

    fun isMonitoringActive(): Boolean = prefs.getBoolean(KEY_MONITORING_ACTIVE, false)

    fun setMonitoringActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_MONITORING_ACTIVE, active).apply()
    }
}
