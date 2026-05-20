package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val amount: Double,
    val category: String,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val paymentMethod: String = "CASH" // "CASH", "UPI"
)
