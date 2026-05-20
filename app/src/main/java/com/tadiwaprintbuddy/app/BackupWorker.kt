package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.tadiwaprintbuddy.app.data.AppDatabase
import java.io.File

class BackupWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private const val BACKUP_DIR_NAME = "backups"
        private const val MAX_BACKUPS = 7
        private const val BACKUP_FILE_PREFIX = "PrintBuddy_Backup_"
        private const val BACKUP_FILE_EXT = ".zip"
    }

    override suspend fun doWork(): Result {
        return try {
            val externalFilesDir = applicationContext.getExternalFilesDir(null)
                ?: return Result.failure()

            val backupDir = File(externalFilesDir, BACKUP_DIR_NAME)
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                return Result.failure()
            }

            val timestampedName = "$BACKUP_FILE_PREFIX${System.currentTimeMillis()}$BACKUP_FILE_EXT"
            val tempFile = File(backupDir, "$timestampedName.tmp")
            val finalFile = File(backupDir, timestampedName)

            if (!BackupUtils.createZipBackupToFile(applicationContext, tempFile)) {
                tempFile.delete()
                return Result.retry()
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                return Result.retry()
            }

            cleanupOldBackups(backupDir)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun cleanupOldBackups(backupDir: File) {
        val files = backupDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(BACKUP_FILE_EXT) }
            ?.sortedByDescending { it.lastModified() }

        if (files != null && files.size > MAX_BACKUPS) {
            files.drop(MAX_BACKUPS).forEach { it.delete() }
        }
    }
}
