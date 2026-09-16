package com.tadiwaprintbuddy.app.data

import java.math.BigDecimal

data class TrendPoint(
    val timestamp: Long,
    val amount: BigDecimal
)

data class PaymentBreakdown(
    val type: String,
    val total: BigDecimal
)

data class PeriodMetrics(
    val revenue: BigDecimal,
    val expenses: BigDecimal,
    val netProfit: BigDecimal,
    val ordersCount: Int,
    val profitMargin: Double,
    val previousRevenue: BigDecimal,
    val previousExpenses: BigDecimal,
    val previousNetProfit: BigDecimal,
    val previousOrdersCount: Int
)
