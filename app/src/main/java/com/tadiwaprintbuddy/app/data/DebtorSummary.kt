package com.tadiwaprintbuddy.app.data

data class DebtorSummary(
    val customerName: String,
    val totalBalance: Double,
    val type: String // "OWES" or "CHANGE"
)
