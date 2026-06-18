package com.neuralbridge.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ExecutorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("neuralbridge_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("nb_enabled", false) && prefs.getBoolean("cloud_enabled", false)
        if (!enabled) return

        try {
            ExecutorKeepAliveService.start(context)
            Log.i("ExecutorBootReceiver", "Executor keepalive restored after ${intent?.action}")
        } catch (e: Exception) {
            Log.w("ExecutorBootReceiver", "Failed to restore executor keepalive", e)
        }
    }
}
