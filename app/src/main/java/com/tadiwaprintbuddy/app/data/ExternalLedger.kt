package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

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
    val accountHolder: String = "Mr Tadiwanashe Edrick Tembo",
    val upiId: String = "9319994350@ptyes",

    // Sync Metadata
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)
