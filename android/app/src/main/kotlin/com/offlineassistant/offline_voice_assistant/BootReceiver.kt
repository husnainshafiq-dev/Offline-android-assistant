package com.offlineassistant.offline_voice_assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast and restarts the voice assistant service
 * if it was previously enabled by the user.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
        const val PREFS_NAME = "voice_assistant_prefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed received")

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false)

            if (serviceEnabled) {
                Log.d(TAG, "Service was enabled, restarting...")
                val serviceIntent = Intent(context, VoiceAssistantService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d(TAG, "Service was not enabled, skipping restart")
            }
        }
    }
}
