package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tadiwaprintbuddy.app.data.AppDatabase
import java.util.Calendar

class ReminderWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val prefs = ReminderPreferencesManager(applicationContext)
        if (!prefs.isEnabled) return androidx.work.ListenableWorker.Result.success()

        val database = AppDatabase.getDatabase(applicationContext)
        val lastOrder = database.printDao().getAllOrders().firstOrNull()

        val now = System.currentTimeMillis()
        val shouldNotify = when (prefs.frequency) {
            ReminderPreferencesManager.FREQUENCY_DAILY -> {
                // If last order was not today
                !isSameDay(lastOrder?.date, now)
            }
            ReminderPreferencesManager.FREQUENCY_WEEKDAYS -> {
                val calendar = Calendar.getInstance()
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val isWeekday = dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
                
                if (isWeekday) {
                    !isSameDay(lastOrder?.date, now)
                } else {
                    false
                }
            }
            ReminderPreferencesManager.FREQUENCY_INTERVAL -> {
                val intervalMillis = prefs.intervalHours * 60 * 60 * 1000L
                val lastOrderTime = lastOrder?.date ?: 0L
                (now - lastOrderTime) >= intervalMillis
            }
            else -> false
        }

        if (shouldNotify) {
            NotificationHelper(applicationContext).showReminderNotification(prefs.message)
        }

        return androidx.work.ListenableWorker.Result.success()
    }

    private fun isSameDay(date1: Long?, date2: Long): Boolean {
        if (date1 == null) return false
        val cal1 = Calendar.getInstance().apply { timeInMillis = date1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
