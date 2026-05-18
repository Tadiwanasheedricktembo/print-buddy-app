package com.tadiwaprintbuddy.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.tadiwaprintbuddy.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupUtils {
    private const val TAG = "BackupUtils"
    private const val DB_NAME = "print_database"
    private const val IMAGES_DIR_ENTRY = "images/"

    suspend fun createZipBackup(context: Context, zipUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure all data is checkpointed to the main DB file
                AppDatabase.getDatabase(context).close()

                context.contentResolver.openOutputStream(zipUri)?.use { outputStream ->
                    ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                        // 1. Add Database files
                        val dbFile = context.getDatabasePath(DB_NAME)
                        if (dbFile.exists()) addToZip(dbFile, DB_NAME, zos)
                        
                        val shmFile = File(dbFile.path + "-shm")
                        if (shmFile.exists()) addToZip(shmFile, "$DB_NAME-shm", zos)
                        
                        val walFile = File(dbFile.path + "-wal")
                        if (walFile.exists()) addToZip(walFile, "$DB_NAME-wal", zos)

                        // 2. Add Images
                        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                        picturesDir?.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                addToZip(file, "$IMAGES_DIR_ENTRY${file.name}", zos)
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
                false
            }
        }
    }

    private fun addToZip(file: File, entryName: String, zos: ZipOutputStream) {
        FileInputStream(file).use { fis ->
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            fis.copyTo(zos)
            zos.closeEntry()
        }
    }

    suspend fun restoreZipBackup(context: Context, zipUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Close current database connections
                AppDatabase.getDatabase(context).close()

                context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                    ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                        var entry: ZipEntry? = zis.getNextEntry()
                        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

                        while (entry != null) {
                            when {
                                entry.name.startsWith(DB_NAME) -> {
                                    val dbFile = context.getDatabasePath(entry.name)
                                    FileOutputStream(dbFile).use { fos -> zis.copyTo(fos) }
                                }
                                entry.name.startsWith(IMAGES_DIR_ENTRY) -> {
                                    val filename = entry.name.substring(IMAGES_DIR_ENTRY.length)
                                    val destFile = File(picturesDir, filename)
                                    FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.getNextEntry()
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                false
            }
        }
    }
}
