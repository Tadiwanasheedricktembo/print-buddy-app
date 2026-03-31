package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settlement_history")
data class SettlementHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val customerName: String,
    val previousBalance: Double,
    val settledAmount: Double,
    val remainingBalance: Double,
    val timestamp: Long,
    val note: String? = null
)
