package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "beauty_transactions")
data class BeautyTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val amount: Double,
    val type: String, // "ADD" or "RETURN"
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
