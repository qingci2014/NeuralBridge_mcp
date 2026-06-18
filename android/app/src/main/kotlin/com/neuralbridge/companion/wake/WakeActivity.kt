package com.neuralbridge.companion.wake

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import com.neuralbridge.companion.service.ExecutorKeepAliveService
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService

class WakeActivity : Activity() {
    private val finishHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reason = intent.getStringExtra(WakeSignalHandler.EXTRA_REASON) ?: "wake-activity"
        Log.i(TAG, "wake_activity_start reason=$reason")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "turn_screen_on_called reason=$reason")

        acquireScreenWakeLock()
        ExecutorKeepAliveService.start(this)
        NeuralBridgeAccessibilityService.instance?.ensureCloudGatewayClientRunning("wake-activity:$reason")
            ?: Log.w(TAG, "one_shot_poll_start skipped; accessibility service instance unavailable")

        finishHandler.postDelayed({
            Log.i(TAG, "wake_activity_finish reason=$reason")
            finish()
        }, FINISH_DELAY_MS)
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        finishHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun acquireScreenWakeLock() {
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "NeuralBridge::WakeActivity"
            ).apply {
                setReferenceCounted(false)
                acquire(FINISH_DELAY_MS + 2_000L)
            }
            Log.i(TAG, "wakelock_acquired type=screen timeout_ms=${FINISH_DELAY_MS + 2_000L}")
        } catch (e: Exception) {
            Log.w(TAG, "wakelock_acquire_failed ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "PhoneExecutorWake"
        private const val FINISH_DELAY_MS = 12_000L
    }
}
