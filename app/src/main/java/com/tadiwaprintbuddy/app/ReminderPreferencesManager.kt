package com.tadiwaprintbuddy.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class ReminderPreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("reminder_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_ENABLED = "reminder_enabled"
        const val KEY_HOUR = "reminder_hour"
        const val KEY_MINUTE = "reminder_minute"
        const val KEY_FREQUENCY = "reminder_frequency" // daily, weekdays, interval
        const val KEY_INTERVAL_HOURS = "reminder_interval_hours"
        const val KEY_MESSAGE = "reminder_message"

        const val FREQUENCY_DAILY = "daily"
        const val FREQUENCY_WEEKDAYS = "weekdays"
        const val FREQUENCY_INTERVAL = "interval"
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var hour: Int
        get() = prefs.getInt(KEY_HOUR, 20) // Default 8 PM
        set(value) = prefs.edit { putInt(KEY_HOUR, value) }

    var minute: Int
        get() = prefs.getInt(KEY_MINUTE, 0)
        set(value) = prefs.edit { putInt(KEY_MINUTE, value) }

    var frequency: String
        get() = prefs.getString(KEY_FREQUENCY, FREQUENCY_DAILY) ?: FREQUENCY_DAILY
        set(value) = prefs.edit { putString(KEY_FREQUENCY, value) }

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, 4)
        set(value) = prefs.edit { putInt(KEY_INTERVAL_HOURS, value) }

    var message: String
        get() = prefs.getString(KEY_MESSAGE, "Don't forget to record your transactions for today!") ?: ""
        set(value) = prefs.edit { putString(KEY_MESSAGE, value) }
}
