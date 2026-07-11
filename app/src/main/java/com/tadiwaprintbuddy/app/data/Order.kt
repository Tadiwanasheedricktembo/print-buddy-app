package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "orders",
    indices = [
        Index(value = ["date"], name = "idx_orders_date"),
        Index(value = ["paymentMethod"], name = "idx_orders_payment_method")
    ]
)
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
    val newBalance: Double = 0.0,
    val paymentStatus: String = "PAID", // PAID, UNPAID, PARTIALLY_PAID
    val orderStatus: String = "ACTIVE"  // ACTIVE, CANCELLED
)
