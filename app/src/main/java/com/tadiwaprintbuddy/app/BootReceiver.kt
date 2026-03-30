package com.tadiwaprintbuddy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = ReminderPreferencesManager(context)
            if (prefs.isEnabled) {
                ReminderScheduler(context).scheduleReminder()
            }
        }
    }
}
