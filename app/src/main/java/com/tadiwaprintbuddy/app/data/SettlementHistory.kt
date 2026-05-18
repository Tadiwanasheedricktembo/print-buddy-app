package com.tadiwaprintbuddy.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settlement_history")
data class SettlementHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val customerName: String,
    @ColumnInfo(name = "previousBalance") val balanceBefore: Double,
    @ColumnInfo(name = "settledAmount") val amountPaid: Double,
    @ColumnInfo(name = "remainingBalance") val balanceAfter: Double,
    val timestamp: Long,
    val type: String = "PAYMENT",
    val note: String = "",
    val customerId: Long = 0,
    val transactionAmount: Double = 0.0,
    val newBalance: Double = 0.0
)
