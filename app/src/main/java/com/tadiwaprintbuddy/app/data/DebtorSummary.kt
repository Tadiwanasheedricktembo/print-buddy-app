package com.tadiwaprintbuddy.app.data

data class DebtorSummary(
    val customerId: Long = 0,
    val customerName: String,
    val totalBalance: Double,
    val type: String // "OWES" or "CHANGE"
)
