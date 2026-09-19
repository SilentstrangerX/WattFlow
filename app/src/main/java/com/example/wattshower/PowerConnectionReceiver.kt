package com.battery.wattflow // Make sure this matches your package name!

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class PowerConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        if (action == Intent.ACTION_POWER_CONNECTED || action == Intent.ACTION_POWER_DISCONNECTED) {
            val serviceIntent = Intent(context, BatteryService::class.java)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Android 12+ sometimes blocks starting foreground services from the background.
                // If this happens, the user just needs to open the app once.
                e.printStackTrace()
            }
        }
    }
}