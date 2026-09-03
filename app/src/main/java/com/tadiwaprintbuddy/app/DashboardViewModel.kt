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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

data class BusinessInsight(
    val title: String,
    val value: String,
    val description: String
)

data class DashboardUiState(
    val period: String = "Today",
    val paymentMethod: String = "All",
    val metrics: PeriodMetrics? = null,
    val revenueTrend: List<TrendPoint> = emptyList(),
    val paymentBreakdown: List<PaymentBreakdown> = emptyList(),
    val serviceBreakdown: List<CategoryRevenue> = emptyList(),
    val expenseBreakdown: List<CategoryRevenue> = emptyList(),
    val insights: List<BusinessInsight> = emptyList(),
    val upiWalletBalance: Double = 0.0,
    val totalReceivables: Double = 0.0,
    val debtorsCount: Int = 0,
    val isLoading: Boolean = true,
    val showZeroExpenseWarning: Boolean = false
)

class DashboardViewModel(private val repository: PrintRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun setPeriod(period: String) {
        _uiState.update { it.copy(period = period, isLoading = true) }
        loadData()
    }

    fun setPaymentMethod(method: String) {
        _uiState.update { it.copy(paymentMethod = method, isLoading = true) }
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val state = _uiState.value
            val (start, end) = getRange(state.period)
            val (prevStart, prevEnd) = getPreviousRange(state.period, start)

            val method = state.paymentMethod.uppercase()

            // 1. Revenue (Authoritative Collection - Money In)
            // Logic: Include only settlements for orders that are still ACTIVE/LEGACY
            val revenue = repository.getSettledRevenueByMethodBetween(start, end, method)
            val prevRevenue = repository.getSettledRevenueByMethodBetween(prevStart, prevEnd, method)

            // 2. Sales Volume (Value of all active work done)
            val salesVolume = repository.getSalesVolumeBetween(start, end)

            // 3. Expenses
            val expenses = if (method == "ALL" || method == "ALL_PAYMENTS") repository.getExpensesBetween(start, end) else repository.getExpensesByMethodBetween(start, end, method)
            val prevExpenses = if (method == "ALL" || method == "ALL_PAYMENTS") repository.getExpensesBetween(prevStart, prevEnd) else repository.getExpensesByMethodBetween(prevStart, prevEnd, method)

            // 4. Orders & Counts
            // Logic: Analytics All matches All Orders (including Credit)
            // Logic: Analytics All_Payments matches All Payments (excluding Credit)
            val ordersCount = when (method) {
                "ALL" -> repository.getOrdersCountBetween(start, end)
                "ALL_PAYMENTS" -> repository.getOrdersCountByMethodBetween(start, end, "PAID_ONLY")
                else -> repository.getOrdersCountByMethodBetween(start, end, method)
            }
            val prevOrdersCount = when (method) {
                "ALL" -> repository.getOrdersCountBetween(prevStart, prevEnd)
                "ALL_PAYMENTS" -> repository.getOrdersCountByMethodBetween(prevStart, prevEnd, "PAID_ONLY")
                else -> repository.getOrdersCountByMethodBetween(prevStart, prevEnd, method)
            }

            // 5. Cash in Hand (Period Specific Flow)
            // Robust calculation: (Cash from orders + Cash from debt) - Cash expenses
            val cashCollected = repository.getSettledRevenueByMethodBetween(start, end, "CASH")
            val cashExpenses = repository.getExpensesByMethodBetween(start, end, "CASH")
            val cashInHand = cashCollected - cashExpenses

            // Financial Metrics
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

            // Snapshot Metrics
            val upiNetFlow = repository.getBeautyNetFlowBetween(start, end)
            val upiTotalBalance = repository.getCurrentBeautyBalance()
            val totalReceivables = repository.getTotalReceivables()
            val debtorsCount = repository.getDebtorsCount()

            // Charts
            val trend = repository.getRevenueTrendByMethod(start, end, method) 
            val payments = repository.getPaymentBreakdownBetween(start, end)
            val services = repository.getServiceBreakdownBetween(start, end)
            val expenseBreakdown = repository.getExpenseBreakdownBetween(start, end)

            // Insights (Using Sales Volume for Avg Order to be accurate)
            val insights = calculateInsights(salesVolume, ordersCount, upiNetFlow, totalReceivables, debtorsCount, cashInHand, trend)

            _uiState.update {
                it.copy(
                    metrics = metrics,
                    revenueTrend = trend,
                    paymentBreakdown = payments,
                    serviceBreakdown = services,
                    expenseBreakdown = expenseBreakdown,
                    insights = insights,
                    upiWalletBalance = upiTotalBalance,
                    totalReceivables = totalReceivables,
                    debtorsCount = debtorsCount,
                    isLoading = false,
                    showZeroExpenseWarning = expenses == 0.0 && revenue > 0
                )
            }
        }
    }

    private fun calculateInsights(
        salesVolume: Double,
        ordersCount: Int,
        upiNetFlow: Double,
        totalReceivables: Double,
        debtorsCount: Int,
        cashInHand: Double,
        trend: List<TrendPoint>
    ): List<BusinessInsight> {
        val list = mutableListOf<BusinessInsight>()
        
        list.add(BusinessInsight("📊 Work Value", "₹${String.format(Locale.ENGLISH, "%.2f", salesVolume)}", "Total jobs this period"))
        list.add(BusinessInsight("💵 Cash Flow", "₹${String.format(Locale.ENGLISH, "%.2f", cashInHand)}", "Net cash collected"))
        
        val upiSign = if (upiNetFlow >= 0) "+" else "-"
        list.add(BusinessInsight("📱 UPI Flow", "$upiSign₹${String.format(Locale.ENGLISH, "%.2f",
            abs(upiNetFlow)
        )}", "Net digital movement"))
        
        list.add(BusinessInsight("⏳ Outstanding", "₹${String.format(Locale.ENGLISH, "%.2f", totalReceivables)}", "Debt from $debtorsCount customers"))

        if (ordersCount > 0) {
            val avg = salesVolume / ordersCount
            list.add(BusinessInsight("📈 Avg Job", "₹${String.format(Locale.ENGLISH, "%.2f", avg)}", "Value per order"))
        }

        // Peak Day
        if (trend.isNotEmpty()) {
            val peak = trend.maxByOrNull { it.amount }
            if (peak != null) {
                val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(peak.timestamp))
                list.add(BusinessInsight("📅 Peak Day", day, "₹${String.format(Locale.ENGLISH, "%.0f", peak.amount)} peak"))
            }
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
                cal.set(Calendar.MILLISECOND, 0)
            }
            "Yesterday" -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                return Pair(start, cal.timeInMillis)
            }
            "This Week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            "This Month" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            }
            "Last 30 Days" -> {
                cal.add(Calendar.DAY_OF_YEAR, -30)
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
            "Today", "Yesterday" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            "This Week" -> cal.add(Calendar.WEEK_OF_YEAR, -1)
            "This Month" -> cal.add(Calendar.MONTH, -1)
            "Last 30 Days" -> cal.add(Calendar.DAY_OF_YEAR, -30)
        }
        return Pair(cal.timeInMillis, end)
    }
}

class DashboardViewModelFactory(private val repository: PrintRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(repository) as T
    }
}
