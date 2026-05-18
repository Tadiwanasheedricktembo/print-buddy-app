package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "external_ledger")
data class ExternalLedger(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val transactionType: String, // "CREDIT_TO_EXTERNAL" or "DEBIT_FROM_EXTERNAL"
    val amount: Double,
    val timestamp: Long,
    val customerName: String? = null,
    val customerId: Long? = null,
    val orderId: Int? = null,
    val note: String? = null,
    val accountHolder: String = "Beauty Rani",
    val upiId: String = "9217011636@ptaxis"
)
