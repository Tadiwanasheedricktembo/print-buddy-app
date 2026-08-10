package com.tadiwaprintbuddy.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    @ColumnInfo(name = "previousBalance") val balanceBefore: Double,
    @ColumnInfo(name = "settledAmount") val amountPaid: Double,
    @ColumnInfo(name = "remainingBalance") val balanceAfter: Double, // UI SNAPSHOT ONLY - Never use for authoritative state
    val timestamp: Long,
    val type: String = "PAYMENT", // Legacy field, kept for compatibility
    val note: String = "",
    val customerId: Long = 0,
    val transactionAmount: Double = 0.0,
    val newBalance: Double = 0.0, // UI SNAPSHOT ONLY - Never use for authoritative state
    
    // Hardening Fields
    val originId: Int? = null,         // Link to Orders.id if ledgerEntryType is ORDER_POST
    val ledgerEntryType: String = "",  // "ORDER_POST", "PAYMENT", "ADJUSTMENT"
    val isShadowDuplicate: Boolean = false, // True if flagged during reconciliation
    val reconciliationStatus: String = "VERIFIED", // "VERIFIED", "SHADOW_PURGED", "FLAGGED"
    val receivedAmount: Double? = null
)
