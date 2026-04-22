package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.tadiwaprintbuddy.app.data.AppDatabase
import java.io.File

class BackupWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val data = database.printDao().getAllSettlementHistoryOnce()

            if (data.isNotEmpty()) {
                val backupDir = File(applicationContext.getExternalFilesDir(null), "backups")
                if (!backupDir.exists()) backupDir.mkdirs()

                val fileName = "backup_${System.currentTimeMillis()}.json"
                val file = File(backupDir, fileName)
                
                val json = Gson().toJson(data)
                file.writeText(json)

                // Keep only last 7 backups
                val files = backupDir.listFiles()?.sortedByDescending { it.lastModified() }
                if (files != null && files.size > 7) {
                    files.drop(7).forEach { it.delete() }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
