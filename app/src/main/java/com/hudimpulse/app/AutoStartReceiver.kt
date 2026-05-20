package com.hudimpulse.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Automatically launches HudDisplayActivity when Android Auto connects
 * via Headunit Revived (ACTION_CONNECTED broadcast).
 *
 * Can be enabled/disabled via HudPreferences.autoStartOnConnect.
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONNECTED) return

        val prefs = HudPreferences(context)
        if (!prefs.autoStartOnConnect) return

        // Launch HUD Display activity
        val hudIntent = Intent(context, HudDisplayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("AUTO_START", true) // Flag para avisar a Activity
        }
        context.startActivity(hudIntent)
    }

    companion object {
        const val ACTION_CONNECTED = "com.andrerinas.headunitrevived.ACTION_CONNECTED"
    }
}
