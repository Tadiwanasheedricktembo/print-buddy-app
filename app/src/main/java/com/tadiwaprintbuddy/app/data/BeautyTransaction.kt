package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.UUID

@Entity(
    tableName = "beauty_transactions",
    indices = [Index(value = ["timestamp"], name = "idx_beauty_timestamp")]
)
data class BeautyTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val amount: BigDecimal,
    val type: String, // "ADD" or "RETURN"
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val previousBalance: BigDecimal = BigDecimal.ZERO,
    val transactionAmount: BigDecimal = BigDecimal.ZERO,
    val newBalance: BigDecimal = BigDecimal.ZERO,

    // Sync Metadata
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)
