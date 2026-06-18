package com.neuralbridge.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.neuralbridge.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-level keepalive for cloud executor mode.
 *
 * AccessibilityService hosts the actual automation engine, but some Android
 * builds are aggressive about pausing it when the screen turns off. This
 * independent foreground service keeps the process in a foreground state and
 * periodically asks the accessibility service to restore cloud polling.
 */
class ExecutorKeepAliveService : Service() {
    companion object {
        private const val TAG = "ExecutorKeepAliveService"
        private const val CHANNEL_ID = "neuralbridge_executor_keepalive"
        private const val NOTIFICATION_ID = 4243
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val PREFS_NAME = "neuralbridge_prefs"
        private const val WAKE_LOCK_TAG = "NeuralBridge::ExecutorKeepAliveService"
        private const val WIFI_LOCK_TAG = "NeuralBridge::ExecutorWifi"

        fun start(context: Context) {
            val intent = Intent(context, ExecutorKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ExecutorKeepAliveService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ExecutorKeepAliveService.onCreate")
        createNotificationChannel()
        startAsForeground()
        acquireLocks()
        startMonitorLoop()
        Log.i(TAG, "Executor keepalive service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ExecutorKeepAliveService.onStartCommand intent=${intent?.action ?: "none"} flags=$flags startId=$startId")
        if (!isExecutorEnabled()) {
            Log.w(TAG, "ExecutorKeepAliveService.onStartCommand disabled; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        acquireLocks()
        NeuralBridgeAccessibilityService.instance?.ensureCloudGatewayClientRunning("keepalive-start")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "ExecutorKeepAliveService.onDestroy")
        releaseLocks()
        scope.cancel()
        Log.i(TAG, "Executor keepalive service stopped")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "ExecutorKeepAliveService.onTaskRemoved intent=${rootIntent?.action ?: "none"}")
        if (isExecutorEnabled()) {
            Log.w(TAG, "ExecutorKeepAliveService.restart_scheduled reason=task_removed")
            start(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onLowMemory() {
        Log.w(TAG, "ExecutorKeepAliveService.onLowMemory")
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        Log.w(TAG, "ExecutorKeepAliveService.onTrimMemory level=$level")
        super.onTrimMemory(level)
    }

    private fun startMonitorLoop() {
        scope.launch {
            while (isActive) {
                if (!isExecutorEnabled()) {
                    stopSelf()
                    return@launch
                }
                acquireLocks()
                val service = NeuralBridgeAccessibilityService.instance
                if (service == null) {
                    Log.w(TAG, "AccessibilityService instance unavailable during keepalive check")
                } else {
                    Log.i(TAG, "PollingWatchdog.tick service=available")
                    service.ensureCloudGatewayClientRunning("keepalive-loop")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun isExecutorEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("nb_enabled", false) && prefs.getBoolean("cloud_enabled", false)
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "ExecutorKeepAliveService.wakelock_acquired tag=$WAKE_LOCK_TAG")
        }

        try {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            if (wifiManager != null && wifiLock?.isHeld != true) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "ExecutorKeepAliveService.wifi_lock_acquired tag=$WIFI_LOCK_TAG")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire Wi-Fi lock; continuing with CPU wake lock", e)
        }
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "ExecutorKeepAliveService.wakelock_released tag=$WAKE_LOCK_TAG")
        }
        wakeLock = null
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            Log.i(TAG, "ExecutorKeepAliveService.wifi_lock_released tag=$WIFI_LOCK_TAG")
        }
        wifiLock = null
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "ExecutorKeepAliveService.startForeground.success")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NeuralBridge Executor Keepalive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the cloud phone executor connected while the screen is off"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NeuralBridge executor online")
            .setContentText("Keeping cloud command polling active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
}
