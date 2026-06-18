package com.neuralbridge.companion.power

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DevicePowerController(private val context: Context) {
    companion object {
        private const val PREFS = "neuralbridge_prefs"
        private const val PREF_KEEP_AWAKE_ENABLED = "executor_keep_awake_enabled"
        private const val PREF_KEEP_AWAKE_MODE = "executor_keep_awake_mode"
        private const val POLLING_WAKE_TAG = "NeuralBridge::CloudPolling"
        private const val EXECUTOR_WAKE_TAG = "NeuralBridge::ExecutorMode"
        private const val EXECUTOR_SCREEN_TAG = "NeuralBridge::ExecutorScreen"
        private const val WAKE_SCREEN_TAG = "NeuralBridge::WakeScreen"
        private const val WAKE_SCREEN_TIMEOUT_MS = 15_000L
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var pollingWakeLock: PowerManager.WakeLock? = null
    private var executorWakeLock: PowerManager.WakeLock? = null
    private var executorScreenWakeLock: PowerManager.WakeLock? = null

    fun restoreConfiguredKeepAwake() {
        val enabled = prefs.getBoolean(PREF_KEEP_AWAKE_ENABLED, false)
        val mode = prefs.getString(PREF_KEEP_AWAKE_MODE, "partial").orEmpty()
        setExecutorKeepAwake(enabled, mode)
    }

    fun setCloudPollingKeepAlive(enabled: Boolean) {
        if (enabled) {
            pollingWakeLock = acquireWakeLock(
                existing = pollingWakeLock,
                levelAndFlags = PowerManager.PARTIAL_WAKE_LOCK,
                tag = POLLING_WAKE_TAG
            )
        } else {
            pollingWakeLock = releaseWakeLock(pollingWakeLock)
        }
    }

    fun setExecutorKeepAwake(enabled: Boolean, mode: String = "partial"): JsonObject {
        val normalizedMode = when (mode.lowercase()) {
            "screen_on", "screen", "bright" -> "screen_on"
            "partial", "cpu" -> "partial"
            else -> "partial"
        }

        prefs.edit()
            .putBoolean(PREF_KEEP_AWAKE_ENABLED, enabled)
            .putString(PREF_KEEP_AWAKE_MODE, normalizedMode)
            .apply()

        if (!enabled) {
            executorWakeLock = releaseWakeLock(executorWakeLock)
            executorScreenWakeLock = releaseWakeLock(executorScreenWakeLock)
            return keepAwakeResult(enabled, normalizedMode, null)
        }

        executorWakeLock = acquireWakeLock(
            existing = executorWakeLock,
            levelAndFlags = PowerManager.PARTIAL_WAKE_LOCK,
            tag = EXECUTOR_WAKE_TAG
        )

        var warning: String? = null
        if (normalizedMode == "screen_on") {
            @Suppress("DEPRECATION")
            executorScreenWakeLock = acquireWakeLock(
                existing = executorScreenWakeLock,
                levelAndFlags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                tag = EXECUTOR_SCREEN_TAG
            )
            warning = "screen_on uses a best-effort screen WakeLock; MagicOS battery settings can still override it."
        } else {
            executorScreenWakeLock = releaseWakeLock(executorScreenWakeLock)
        }

        return keepAwakeResult(enabled, normalizedMode, warning)
    }

    fun wakeScreen(): JsonObject {
        val before = getScreenState()
        if (before["interactive"]?.toString() == "true") {
            return buildJsonObject {
                put("status", "ok")
                put("already_awake", true)
                put("screen_state", before)
            }
        }

        return try {
            @Suppress("DEPRECATION")
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_SCREEN_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_SCREEN_TIMEOUT_MS)
            }
            buildJsonObject {
                put("status", "ok")
                put("already_awake", false)
                put("screen_state", getScreenState())
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("error_code", "WAKE_SCREEN_FAILED")
                put("message", e.message ?: "Unable to wake screen")
                put("screen_state", getScreenState())
            }
        }
    }

    fun getScreenState(): JsonObject {
        val interactive = powerManager.isInteractive
        val deviceIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode
        } else {
            false
        }
        val ignoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        return buildJsonObject {
            put("screen_on", interactive)
            put("interactive", interactive)
            put("keyguard_locked", keyguardManager.isKeyguardLocked)
            put("device_idle", deviceIdle)
            put("power_save_mode", powerManager.isPowerSaveMode)
            put("ignoring_battery_optimizations", ignoringBatteryOptimizations)
            put("executor_keep_awake_enabled", prefs.getBoolean(PREF_KEEP_AWAKE_ENABLED, false))
            put("executor_keep_awake_mode", prefs.getString(PREF_KEEP_AWAKE_MODE, "partial") ?: "partial")
            put("polling_wakelock_held", pollingWakeLock?.isHeld == true)
            put("executor_wakelock_held", executorWakeLock?.isHeld == true)
            put("screen_wakelock_held", executorScreenWakeLock?.isHeld == true)
        }
    }

    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun releaseAll() {
        pollingWakeLock = releaseWakeLock(pollingWakeLock)
        executorWakeLock = releaseWakeLock(executorWakeLock)
        executorScreenWakeLock = releaseWakeLock(executorScreenWakeLock)
    }

    private fun acquireWakeLock(
        existing: PowerManager.WakeLock?,
        levelAndFlags: Int,
        tag: String
    ): PowerManager.WakeLock {
        if (existing?.isHeld == true) return existing
        return powerManager.newWakeLock(levelAndFlags, tag).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock?): PowerManager.WakeLock? {
        if (lock?.isHeld == true) lock.release()
        return null
    }

    private fun keepAwakeResult(enabled: Boolean, mode: String, warning: String?): JsonObject =
        buildJsonObject {
            put("status", "ok")
            put("enabled", enabled)
            put("mode", mode)
            warning?.let { put("warning", it) }
            put("screen_state", getScreenState())
        }
}
