package com.tadiwaprintbuddy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tadiwaprintbuddy.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.*

data class BusinessInsight(
    val title: String,
    val value: String,
    val description: String
)

data class DashboardUiState(
    val period: String = "Today",
    val metrics: PeriodMetrics? = null,
    val revenueTrend: List<TrendPoint> = emptyList(),
    val paymentBreakdown: List<PaymentBreakdown> = emptyList(),
    val serviceBreakdown: List<CategoryRevenue> = emptyList(),
    val insights: List<BusinessInsight> = emptyList(),
    val isLoading: Boolean = true,
    val showZeroExpenseWarning: Boolean = false
)

class DashboardViewModel(private val repository: PrintRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadData("Today")
    }

    fun setPeriod(period: String) {
        _uiState.update { it.copy(period = period, isLoading = true) }
        loadData(period)
    }

    private fun loadData(period: String) {
        viewModelScope.launch {
            val (start, end) = getRange(period)
            val (prevStart, prevEnd) = getPreviousRange(period, start)

            // Basic Metrics
            val revenue = repository.getRevenueBetween(start, end) ?: 0.0
            val expenses = repository.getExpensesBetween(start, end) ?: 0.0
            val ordersCount = repository.getOrdersCountBetween(start, end)
            
            val prevRevenue = repository.getRevenueBetween(prevStart, prevEnd) ?: 0.0
            val prevExpenses = repository.getExpensesBetween(prevStart, prevEnd) ?: 0.0
            val prevOrdersCount = repository.getOrdersCountBetween(prevStart, prevEnd)

            val netProfit = revenue - expenses
            val prevNetProfit = prevRevenue - prevExpenses
            val profitMargin = if (revenue > 0) (netProfit / revenue) * 100 else 0.0

            val metrics = PeriodMetrics(
                revenue = revenue,
                expenses = expenses,
                netProfit = netProfit,
                ordersCount = ordersCount,
                profitMargin = profitMargin,
                previousRevenue = prevRevenue,
                previousExpenses = prevExpenses,
                previousNetProfit = prevNetProfit,
                previousOrdersCount = prevOrdersCount
            )

            // Charts
            val trend = repository.getRevenueTrend(start, end)
            val payments = repository.getPaymentBreakdownBetween(start, end)
            val services = repository.getServiceBreakdownBetween(start, end)

            // Insights
            val insights = calculateInsights(revenue, ordersCount, services, payments)

            _uiState.update {
                it.copy(
                    metrics = metrics,
                    revenueTrend = trend,
                    paymentBreakdown = payments,
                    serviceBreakdown = services,
                    insights = insights,
                    isLoading = false,
                    showZeroExpenseWarning = expenses == 0.0 && revenue > 0
                )
            }
        }
    }

    private fun calculateInsights(
        revenue: Double,
        ordersCount: Int,
        services: List<CategoryRevenue>,
        payments: List<PaymentBreakdown>
    ): List<BusinessInsight> {
        val list = mutableListOf<BusinessInsight>()
        
        // Best Service
        services.maxByOrNull { it.total }?.let {
            val percent = if (revenue > 0) (it.total / revenue) * 100 else 0.0
            list.add(BusinessInsight("🏆 Best Service", it.category, "${String.format(Locale.ENGLISH, "%.0f", percent)}% of revenue"))
        }

        // Top Payment
        payments.maxByOrNull { it.total }?.let {
            val percent = if (revenue > 0) (it.total / revenue) * 100 else 0.0
            list.add(BusinessInsight("💳 Top Payment", it.type, "${String.format(Locale.ENGLISH, "%.0f", percent)}% of sales"))
        }

        // Avg Order
        if (ordersCount > 0) {
            val avg = revenue / ordersCount
            list.add(BusinessInsight("📊 Avg Order", "₹${String.format(Locale.ENGLISH, "%.2f", avg)}", "per transaction"))
        }

        return list
    }

    private fun getRange(period: String): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        when (period) {
            "Today" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            }
            "This Week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
            }
            "This Month" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
            }
            "All Time" -> return Pair(0L, end)
        }
        return Pair(cal.timeInMillis, end)
    }

    private fun getPreviousRange(period: String, currentStart: Long): Pair<Long, Long> {
        if (period == "All Time") return Pair(0L, 0L)
        val cal = Calendar.getInstance()
        cal.timeInMillis = currentStart
        val end = currentStart
        when (period) {
            "Today" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            "This Week" -> cal.add(Calendar.WEEK_OF_YEAR, -1)
            "This Month" -> cal.add(Calendar.MONTH, -1)
        }
        return Pair(cal.timeInMillis, end)
    }
}

class DashboardViewModelFactory(private val repository: PrintRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(repository) as T
    }
}
