package com.neuralbridge.companion.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.neuralbridge.companion.R
import com.neuralbridge.companion.service.ExecutorKeepAliveService
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService

object WakeSignalHandler {
    const val ACTION_WAKE_SIGNAL = "com.neuralbridge.companion.action.WAKE_SIGNAL"
    const val EXTRA_REASON = "wake_reason"

    private const val TAG = "PhoneExecutorWake"
    private const val CHANNEL_ID = "phone_executor_wake_silent"
    private const val NOTIFICATION_ID = 4244
    private const val WAKE_LOCK_TAG = "NeuralBridge::WakeSignal"
    private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

    fun handleWakeSignal(context: Context, reason: String = "wake-signal") {
        val appContext = context.applicationContext
        Log.i(TAG, "push_received reason=$reason")
        Log.i(TAG, "wake_receiver_start reason=$reason")

        acquireShortWakeLock(appContext)
        ExecutorKeepAliveService.start(appContext)

        val service = NeuralBridgeAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "executor_service_start requested; accessibility service instance unavailable")
        } else {
            Log.i(TAG, "executor_service_start instance=ready")
            Log.i(TAG, "one_shot_poll_start reason=$reason")
            service.ensureCloudGatewayClientRunning("wake-signal:$reason")
        }

        showWakeNotification(appContext, reason)
        logScreenWakeResult(appContext, reason)
    }

    fun buildWakeActivityIntent(context: Context, reason: String): Intent =
        Intent(context, WakeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_REASON, reason)
        }

    private fun acquireShortWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "wakelock_acquired timeout_ms=$WAKE_LOCK_TIMEOUT_MS")
        } catch (e: Exception) {
            Log.w(TAG, "wakelock_acquire_failed ${e.message}", e)
        }
    }

    private fun showWakeNotification(context: Context, reason: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        createWakeChannel(notificationManager)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "wake_notification_blocked missing_post_notifications")
            context.startActivity(buildWakeActivityIntent(context, reason))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !notificationManager.canUseFullScreenIntent()
        ) {
            Log.w(TAG, "wake_notification_fullscreen_not_allowed")
        }

        val wakeIntent = buildWakeActivityIntent(context, reason)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Phone Executor wake request")
            .setContentText("Preparing to receive cloud command")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setDefaults(0)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "wake_notification_posted reason=$reason")
    }

    private fun createWakeChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phone Executor Wake",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Silently wake the phone when a cloud command is queued"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun logScreenWakeResult(context: Context, reason: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            val powerManager = context.getSystemService(PowerManager::class.java)
            if (powerManager.isInteractive) {
                Log.i(TAG, "screen_turn_on_success reason=$reason")
            } else {
                Log.w(TAG, "screen_turn_on_not_confirmed reason=$reason")
                startWakeActivityFallback(context, reason)
            }
        }, 2_000L)
    }

    private fun startWakeActivityFallback(context: Context, reason: String) {
        try {
            Log.i(TAG, "wake_activity_fallback_start reason=$reason")
            context.startActivity(buildWakeActivityIntent(context, "$reason:fallback"))
        } catch (e: Exception) {
            Log.w(TAG, "wake_activity_fallback_failed reason=$reason message=${e.message}", e)
        }
    }
}
