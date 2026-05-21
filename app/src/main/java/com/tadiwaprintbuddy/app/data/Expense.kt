package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ExpenseCategory {
    PAPER,
    INK,
    ELECTRICITY,
    MAINTENANCE,
    MISCELLANEOUS
}

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val category: ExpenseCategory,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null,
    val paymentMethod: String = "CASH" // "CASH", "UPI"
)
