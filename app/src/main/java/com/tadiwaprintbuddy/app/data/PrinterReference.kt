package com.tadiwaprintbuddy.app.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
@Entity(tableName = "printer_references")
data class PrinterReference(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val notes: String?,
    val imagePath: String,
    val timestamp: Long,
    
    // Sync Metadata
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
) : Parcelable
