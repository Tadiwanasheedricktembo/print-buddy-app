package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(
    tableName = "debtor_credits",
    indices = [Index(value = ["lastUpdated"], name = "idx_debtor_updated")]
)
data class DebtorCredit(
    @PrimaryKey
    val customerId: Long,
    val customerName: String, // Keeping for safety during migration/display
    val amount: BigDecimal,
    val lastUpdated: Long = System.currentTimeMillis(),
    val phoneNumber: String? = null
)
