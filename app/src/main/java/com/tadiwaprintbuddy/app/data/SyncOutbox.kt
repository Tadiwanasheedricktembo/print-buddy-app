package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "sync_outbox")
data class SyncOutbox(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val entityType: String,
    val entitySyncId: String,
    val operation: String, // CREATE, UPDATE, DELETE
    val createdAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val status: SyncStatus = SyncStatus.PENDING_UPLOAD
)
