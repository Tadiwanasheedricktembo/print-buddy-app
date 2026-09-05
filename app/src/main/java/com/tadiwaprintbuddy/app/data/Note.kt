package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,

    // Sync Metadata
    val syncId: String = UUID.randomUUID().toString(),
    val deletedAt: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)
