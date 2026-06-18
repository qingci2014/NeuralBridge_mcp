package com.neuralbridge.companion.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.neuralbridge.companion.BuildConfig

class WakeSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w("PhoneExecutorWake", "external wake broadcast ignored in non-debug build")
            return
        }
        val reason = intent.getStringExtra(WakeSignalHandler.EXTRA_REASON) ?: "broadcast"
        WakeSignalHandler.handleWakeSignal(context, reason)
    }
}
