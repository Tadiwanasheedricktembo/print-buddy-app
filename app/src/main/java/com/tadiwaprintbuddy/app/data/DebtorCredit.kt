package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "debtor_credits")
data class DebtorCredit(
    @PrimaryKey
    val customerName: String,
    val amount: Double
)
