package com.hudimpulse.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives turn-by-turn navigation broadcasts from Headunit Revived.
 * Action: com.andrerinas.headunitrevived.NAVIGATION_UPDATE
 */
class NavigationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val data = NavData(
            distanceMeters = intent.getIntExtra("distance_meters", -1),
            timeSeconds = intent.getIntExtra("time_seconds", -1),
            road = intent.getStringExtra("road") ?: "",
            nextEventType = intent.getIntExtra("next_event_type", 0),
            actionText = intent.getStringExtra("action_text") ?: "",
            turnSide = intent.getIntExtra("turn_side", 3),
            turnNumber = intent.getIntExtra("turn_number", -1),
            turnAngle = intent.getIntExtra("turn_angle", -1)
        )

        // Forward to any active listeners
        listeners.forEach { it.onNavigationUpdate(data) }
    }

    companion object {
        const val ACTION = "com.andrerinas.headunitrevived.NAVIGATION_UPDATE"

        private val listeners = mutableListOf<NavigationListener>()

        fun addListener(listener: NavigationListener) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }

        fun removeListener(listener: NavigationListener) {
            listeners.remove(listener)
        }
    }

    interface NavigationListener {
        fun onNavigationUpdate(data: NavData)
    }
}

data class NavData(
    val distanceMeters: Int,
    val timeSeconds: Int,
    val road: String,
    val nextEventType: Int,
    val actionText: String,
    val turnSide: Int, // 1=LEFT, 2=RIGHT, 3=UNSPECIFIED
    val turnNumber: Int,
    val turnAngle: Int
)
