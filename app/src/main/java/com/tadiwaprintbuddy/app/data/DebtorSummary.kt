package com.tadiwaprintbuddy.app.data

import java.math.BigDecimal

data class DebtorSummary(
    val customerId: Long = 0,
    val customerName: String,
    val totalBalance: BigDecimal,
    val type: String // "OWES" or "CHANGE"
)
