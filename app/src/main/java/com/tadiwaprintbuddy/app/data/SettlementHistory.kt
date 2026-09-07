package com.tadiwaprintbuddy.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.UUID

@Entity(
    tableName = "settlement_history",
    indices = [
        Index("customerId"),
        Index("originId"),
        Index(value = ["originId", "ledgerEntryType"]),
        Index(value = ["timestamp"], name = "idx_settlement_timestamp")
    ]
)
data class SettlementHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val customerName: String,
    @ColumnInfo(name = "previousBalance") val balanceBefore: BigDecimal,
    @ColumnInfo(name = "settledAmount") val amountPaid: BigDecimal,
    @ColumnInfo(name = "remainingBalance") val balanceAfter: BigDecimal, // UI SNAPSHOT ONLY - Never use for authoritative state
    val timestamp: Long,
    val type: String = "PAYMENT", // Legacy field, kept for compatibility
    val note: String = "",
    val customerId: Long = 0,
    val transactionAmount: BigDecimal = BigDecimal.ZERO,
    val newBalance: BigDecimal = BigDecimal.ZERO, // UI SNAPSHOT ONLY - Never use for authoritative state
    
    // Hardening Fields
    val originId: Int? = null,         // Link to Orders.id if ledgerEntryType is ORDER_POST
    val ledgerEntryType: String = "",  // "ORDER_POST", "PAYMENT", "ADJUSTMENT"
    val isShadowDuplicate: Boolean = false, // True if flagged during reconciliation
    val reconciliationStatus: String = "VERIFIED", // "VERIFIED", "SHADOW_PURGED", "FLAGGED"
    val receivedAmount: BigDecimal? = null,

    // Global Identity
    val customerSyncId: String = "",
    val originSyncId: String? = null,

    // Sync Metadata
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)
