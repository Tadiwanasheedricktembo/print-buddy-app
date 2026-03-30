package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.work.*
import java.util.*
import java.util.concurrent.TimeUnit

class ReminderScheduler(private val context: Context) {

    fun scheduleReminder() {
        val prefs = ReminderPreferencesManager(context)
        if (!prefs.isEnabled) {
            cancelReminder()
            return
        }

        val workManager = WorkManager.getInstance(context)

        if (prefs.frequency == ReminderPreferencesManager.FREQUENCY_INTERVAL) {
            val intervalRequest = PeriodicWorkRequestBuilder<ReminderWorker>(
                prefs.intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .addTag("reminder_work")
                .build()

            workManager.enqueueUniquePeriodicWork(
                "reminder_work",
                ExistingPeriodicWorkPolicy.UPDATE,
                intervalRequest
            )
        } else {
            // Daily or Weekdays: Schedule at a specific time
            val currentTime = Calendar.getInstance()
            val dueDate = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, prefs.hour)
                set(Calendar.MINUTE, prefs.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (dueDate.before(currentTime)) {
                dueDate.add(Calendar.DAY_OF_MONTH, 1)
            }

            val initialDelay = dueDate.timeInMillis - currentTime.timeInMillis

            val dailyRequest = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .addTag("reminder_work")
                .build()

            workManager.enqueueUniquePeriodicWork(
                "reminder_work",
                ExistingPeriodicWorkPolicy.UPDATE,
                dailyRequest
            )
        }
    }

    fun cancelReminder() {
        WorkManager.getInstance(context).cancelUniqueWork("reminder_work")
    }
}
