package com.offlineassistant.offline_voice_assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * A BroadcastReceiver specifically designed to bypass Xiaomi/MIUI's aggressive 
 * background activity start restrictions. By sending a broadcast with 
 * FLAG_RECEIVER_FOREGROUND, the OS is forced to process it immediately,
 * rather than queuing a direct Activity intent until the app is opened manually.
 */
class ActionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ActionReceiver"
        const val ACTION_EXECUTE_INTENT = "com.offlineassistant.EXECUTE_INTENT"
        const val EXTRA_PAYLOAD_INTENT = "payload_intent"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_EXECUTE_INTENT) {
            val externalIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PAYLOAD_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PAYLOAD_INTENT)
            }

            if (externalIntent != null) {
                Log.d(TAG, "Received broadcast to launch: ${externalIntent.action}")
                try {
                    // Try to launch directly from the Receiver Context
                    externalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(externalIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch intent from Receiver", e)
                }
            } else {
                Log.w(TAG, "Received broadcast but payload intent was null")
            }
        }
    }
}
