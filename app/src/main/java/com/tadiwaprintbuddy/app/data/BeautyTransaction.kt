package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "beauty_transactions",
    indices = [Index(value = ["timestamp"], name = "idx_beauty_timestamp")]
)
data class BeautyTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val amount: Double,
    val type: String, // "ADD" or "RETURN"
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val previousBalance: Double = 0.0,
    val transactionAmount: Double = 0.0,
    val newBalance: Double = 0.0
)
