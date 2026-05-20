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
                context.contentResolver.openOutputStream(zipUri)?.use { outputStream ->
                    ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                        buildZipBackup(context, zos)
                    }
                } ?: run {
                    Log.e(TAG, "Unable to open backup output stream for Uri: $zipUri")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
                false
            }
        }
    }

    suspend fun createZipBackupToFile(context: Context, destFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { fos ->
                    ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                        buildZipBackup(context, zos)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
                false
            }
        }
    }

    private fun buildZipBackup(context: Context, zos: ZipOutputStream): Boolean {
        AppDatabase.closeDatabase()

        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            Log.e(TAG, "Database file not found: ${dbFile.absolutePath}")
            return false
        }

        addToZip(dbFile, DB_NAME, zos)

        val shmFile = File(dbFile.path + "-shm")
        if (shmFile.exists()) addToZip(shmFile, "$DB_NAME-shm", zos)

        val walFile = File(dbFile.path + "-wal")
        if (walFile.exists()) addToZip(walFile, "$DB_NAME-wal", zos)

        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        picturesDir?.listFiles()?.forEach { file ->
            if (file.isFile) {
                addToZip(file, "$IMAGES_DIR_ENTRY${file.name}", zos)
            }
        }

        return true
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
            var restoredAny = false
            try {
                AppDatabase.closeDatabase()

                val dbFile = context.getDatabasePath(DB_NAME)
                File(dbFile.path + "-shm").delete()
                File(dbFile.path + "-wal").delete()

                context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                    ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                        var entry: ZipEntry? = try { zis.nextEntry } catch (e: Exception) { null }

                        if (entry == null) {
                            Log.e(TAG, "Not a valid Zip file or empty Zip")
                            return@withContext false
                        }

                        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

                        while (entry != null) {
                            when {
                                entry.name.startsWith(DB_NAME) -> {
                                    val destFile = context.getDatabasePath(entry.name)
                                    destFile.parentFile?.mkdirs()
                                    FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }
                                    if (entry.name == DB_NAME) restoredAny = true
                                }
                                entry.name.startsWith(IMAGES_DIR_ENTRY) -> {
                                    val filename = entry.name.substring(IMAGES_DIR_ENTRY.length)
                                    if (filename.isNotEmpty()) {
                                        val destFile = File(picturesDir, filename)
                                        destFile.parentFile?.mkdirs()
                                        FileOutputStream(destFile).use { fos -> zis.copyTo(fos) }
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = try { zis.nextEntry } catch (e: Exception) { null }
                        }
                    }
                }
                restoredAny
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                false
            }
        }
    }
}
