package com.tadiwaprintbuddy.app.data

data class TrendPoint(
    val timestamp: Long,
    val amount: Double
)

data class PaymentBreakdown(
    val type: String,
    val total: Double
)

data class PeriodMetrics(
    val revenue: Double,
    val expenses: Double,
    val netProfit: Double,
    val ordersCount: Int,
    val profitMargin: Double,
    val previousRevenue: Double,
    val previousExpenses: Double,
    val previousNetProfit: Double,
    val previousOrdersCount: Int
)
