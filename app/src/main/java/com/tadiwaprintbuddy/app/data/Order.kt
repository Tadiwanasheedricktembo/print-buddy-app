package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.UUID

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
    val totalAmount: BigDecimal,
    val date: Long,
    val customerName: String,
    val paidAmount: BigDecimal = BigDecimal.ZERO,
    val paymentMethod: String = "CASH", // "CASH" or "UPI"
    val customerId: Long = 0,
    val previousBalance: BigDecimal = BigDecimal.ZERO,
    val transactionAmount: BigDecimal = BigDecimal.ZERO,
    val newBalance: BigDecimal = BigDecimal.ZERO,
    val paymentStatus: String = "PAID", // PAID, UNPAID, PARTIALLY_PAID
    val orderStatus: String = "ACTIVE", // ACTIVE, CANCELLED
    val receivedAmount: BigDecimal? = null,

    // Global Identity
    val customerSyncId: String = "",

    // Sync Metadata
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)
