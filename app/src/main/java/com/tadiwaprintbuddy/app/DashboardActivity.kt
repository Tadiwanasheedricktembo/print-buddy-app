package com.tadiwaprintbuddy.app

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.tadiwaprintbuddy.app.data.*
import com.tadiwaprintbuddy.app.databinding.ActivityDashboardBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory(PrintRepository(AppDatabase.getDatabase(this).printDao()))
    }
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupFilters()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupFilters() {
        binding.chipGroupTimeFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val period = when (checkedId) {
                R.id.chipToday -> "Today"
                R.id.chipYesterday -> "Yesterday"
                R.id.chipThisWeek -> "This Week"
                R.id.chipThisMonth -> "This Month"
                R.id.chipLast30Days -> "Last 30 Days"
                R.id.chipAllTime -> "All Time"
                else -> "Today"
            }
            viewModel.setPeriod(period)
        }

        binding.chipGroupPaymentFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val method = when (checkedId) {
                R.id.chipAllMethods -> "All"
                R.id.chipCashOnly -> "Cash"
                R.id.chipUpiOnly -> "UPI"
                R.id.chipCreditOnly -> "Credit"
                else -> "All"
            }
            viewModel.setPaymentMethod(method)
        }
    }

    private fun setupClickListeners() {
        binding.btnAddExpense.setOnClickListener {
            showAddExpenseSheet()
        }
        binding.bannerZeroExpense.setOnClickListener {
            showAddExpenseSheet()
        }

        // Add today's summary card clicks if needed
    }

    private fun showAddExpenseSheet() {
        val bottomSheet = AddExpenseBottomSheet()
        bottomSheet.show(supportFragmentManager, "AddExpense")
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateMetrics(state.metrics)
                updateCharts(state)
                updateInsights(state.insights)
                binding.textUpiWalletBalanceCallout.text = currencyFormat.format(state.upiWalletBalance)
                binding.bannerZeroExpense.visibility = if (state.showZeroExpenseWarning) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateMetrics(metrics: PeriodMetrics?) {
        if (metrics == null) return
        binding.textTotalRevenue.text = currencyFormat.format(metrics.revenue)
        binding.textNetProfit.text = currencyFormat.format(metrics.netProfit)
        binding.textTotalExpenses.text = currencyFormat.format(metrics.expenses)
        binding.textOrdersCount.text = metrics.ordersCount.toString()
        
        binding.textProfitMarginPercent.text = String.format(Locale.ENGLISH, "%.0f%%", metrics.profitMargin)
        binding.progressProfitMargin.progress = metrics.profitMargin.toInt()

        // Trend badges
        updateTrendBadge(binding.badgeRevenue, metrics.revenue, metrics.previousRevenue)
        updateTrendBadge(binding.badgeExpenses, metrics.expenses, metrics.previousExpenses, isInverted = true)
        updateTrendBadge(binding.badgeNetProfit, metrics.netProfit, metrics.previousNetProfit)
        updateTrendBadge(binding.badgeOrders, metrics.ordersCount.toDouble(), metrics.previousOrdersCount.toDouble(), showPercent = false)
    }

    private fun updateTrendBadge(textView: TextView, current: Double, previous: Double, isInverted: Boolean = false, showPercent: Boolean = true) {
        if (previous == 0.0) {
            textView.visibility = View.GONE
            return
        }
        textView.visibility = View.VISIBLE
        val diff = current - previous
        val isUp = diff >= 0
        
        if (showPercent) {
            val percent = (Math.abs(diff) / previous) * 100
            val sign = if (isUp) "▲" else "▼"
            textView.text = String.format(Locale.ENGLISH, "%s %.0f%%", sign, percent)
        } else {
            val sign = if (isUp) "+" else "-"
            textView.text = String.format(Locale.ENGLISH, "%s %.0f", sign, Math.abs(diff))
        }

        textView.setBackgroundResource(if (isUp != isInverted) R.drawable.bg_badge_trend_up else R.drawable.bg_badge_trend_down)
    }

    private fun updateCharts(state: DashboardUiState) {
        setupRevenueTrendChart(state.revenueTrend)
        setupPaymentBreakdownChart(state.paymentBreakdown)
        setupServiceBreakdownChart(state.serviceBreakdown, state.metrics?.ordersCount ?: 0)
    }

    private fun setupRevenueTrendChart(data: List<TrendPoint>) {
        val chart = binding.chartRevenueTrend
        if (data.isEmpty()) {
            chart.clear()
            return
        }

        val entries = data.map { Entry(it.timestamp.toFloat(), it.amount.toFloat()) }
        val dataSet = LineDataSet(entries, "Current Period")
        dataSet.apply {
            color = Color.parseColor("#00C853")
            lineWidth = 2f
            setDrawCircles(true)
            setCircleColor(Color.WHITE)
            circleRadius = 4f
            setCircleColor(Color.parseColor("#00C853"))
            setDrawFilled(true)
            fillAlpha = 20
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawValues(false)
        }

        chart.apply {
            this.data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#9E9E9E")
                setDrawGridLines(false)
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(value.toLong()))
                    }
                }
            }
            axisLeft.apply {
                textColor = Color.parseColor("#9E9E9E")
                gridColor = Color.parseColor("#1E1E1E")
                enableGridDashedLine(10f, 10f, 0f)
                setDrawAxisLine(false)
            }
            axisRight.isEnabled = false
            legend.isEnabled = false
            invalidate()
        }
    }

    private fun setupPaymentBreakdownChart(data: List<PaymentBreakdown>) {
        val chart = binding.chartPaymentBreakdown
        if (data.isEmpty()) {
            chart.clear()
            return
        }

        val entries = data.mapIndexed { index, item -> BarEntry(index.toFloat(), item.total.toFloat()) }
        val dataSet = BarDataSet(entries, "")
        dataSet.apply {
            colors = listOf(
                Color.parseColor("#00C853"), // CASH
                Color.parseColor("#448AFF"), // UPI
                Color.parseColor("#FFB300")  // CREDIT
            )
            setDrawValues(true)
            valueTextColor = Color.WHITE
            valueTextSize = 10f
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return currencyFormat.format(value)
                }
            }
        }

        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            setDrawGridBackground(false)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.WHITE
                setDrawGridLines(false)
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return data.getOrNull(value.toInt())?.type ?: ""
                    }
                }
            }
            axisLeft.isEnabled = false
            axisRight.isEnabled = false
            legend.isEnabled = false
            invalidate()
        }
    }

    private fun setupServiceBreakdownChart(data: List<CategoryRevenue>, totalJobs: Int) {
        val chart = binding.chartServiceBreakdown
        if (data.isEmpty()) {
            chart.clear()
            return
        }

        val entries = data.map { PieEntry(it.total.toFloat(), it.category) }
        val dataSet = PieDataSet(entries, "")
        dataSet.apply {
            colors = listOf(
                Color.parseColor("#00C853"),
                Color.parseColor("#FF6D00"),
                Color.parseColor("#448AFF"),
                Color.parseColor("#AA00FF"),
                Color.parseColor("#FFB300")
            )
            sliceSpace = 3f
            setDrawValues(false)
        }

        chart.apply {
            this.data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            setHoleColor(Color.TRANSPARENT)
            setCenterText("Total Jobs\n$totalJobs")
            setCenterTextColor(Color.WHITE)
            setCenterTextSize(16f)
            holeRadius = 70f
            legend.isEnabled = false
            invalidate()
        }

        // Custom Legend
        binding.chipGroupServiceLegend.removeAllViews()
        data.forEachIndexed { index, item ->
            val chip = com.google.android.material.chip.Chip(this)
            chip.text = "${item.category}: ${currencyFormat.format(item.total)}"
            chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A1A1A"))
            chip.setTextColor(Color.WHITE)
            chip.chipStrokeWidth = 1f
            chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(dataSet.colors[index % dataSet.colors.size])
            binding.chipGroupServiceLegend.addView(chip)
        }
    }

    private fun updateInsights(insights: List<BusinessInsight>) {
        binding.layoutInsightsStrip.removeAllViews()
        insights.forEach { insight ->
            val view = layoutInflater.inflate(R.layout.item_insight_card, binding.layoutInsightsStrip, false)
            view.findViewById<TextView>(R.id.textInsightIconTitle).text = insight.title
            view.findViewById<TextView>(R.id.textInsightValue).text = insight.value
            view.findViewById<TextView>(R.id.textInsightDescription).text = insight.description
            binding.layoutInsightsStrip.addView(view)
        }
    }
}
