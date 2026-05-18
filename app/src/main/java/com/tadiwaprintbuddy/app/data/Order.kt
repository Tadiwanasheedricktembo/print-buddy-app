package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val totalAmount: Double,
    val date: Long,
    val customerName: String,
    val paidAmount: Double = 0.0,
    val paymentMethod: String = "CASH", // "CASH" or "UPI"
    val customerId: Long = 0,
    val previousBalance: Double = 0.0,
    val transactionAmount: Double = 0.0,
    val newBalance: Double = 0.0
)
