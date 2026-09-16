package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "deferred_sync")
data class DeferredSync(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val entityType: String,
    val entitySyncId: String,
    val operation: String,
    val data: String, // JSON string
    val timestamp: Long,
    val serverUpdatedAt: String?,
    val serverId: Int?,
    val idempotencyKey: String,
    val createdAt: Long = System.currentTimeMillis()
)
