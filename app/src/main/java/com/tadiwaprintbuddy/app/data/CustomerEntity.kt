package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "customers",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val displayName: String,
    val normalizedName: String, // LOWER(TRIM(displayName))
    val phoneNumber: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    
    // Sync Metadata
    val syncId: String = UUID.randomUUID().toString(),
    val deletedAt: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)
