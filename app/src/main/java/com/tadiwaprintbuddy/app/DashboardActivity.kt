package com.tadiwaprintbuddy.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.CategoryRevenue
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityDashboardBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var repository: PrintRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupToolbar()
        setupClickListeners()
        observeInsights()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupClickListeners() {
        binding.cardRevenue.setOnClickListener {
            val intent = Intent(this, OrdersActivity::class.java)
            intent.putExtra("filter", "MONTH")
            startActivity(intent)
        }

        binding.cardOrders.setOnClickListener {
            val intent = Intent(this, OrdersActivity::class.java)
            intent.putExtra("fromInsights", true)
            startActivity(intent)
        }
    }

    private fun observeInsights() {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

        lifecycleScope.launch {
            repository.getTotalRevenueFlow().collectLatest { revenue ->
                binding.textTotalRevenue.text = format.format(revenue ?: 0.0)
            }
        }

        lifecycleScope.launch {
            repository.getTotalOrdersFlow().collectLatest { count ->
                binding.textTotalOrders.text = count.toString()
            }
        }

        lifecycleScope.launch {
            repository.getRevenueByCategoryFlow().collectLatest { data ->
                if (data.isNotEmpty()) {
                    binding.categoryChart.visibility = View.VISIBLE
                    setupPieChart(binding.categoryChart, data)
                } else {
                    binding.categoryChart.visibility = View.GONE
                }
            }
        }
    }

    private fun setupPieChart(pieChart: PieChart, data: List<CategoryRevenue>) {
        val entries = data.map { PieEntry(it.total.toFloat(), it.category) }

        val dataSet = PieDataSet(entries, "")
        
        // Apply Design System Chart Palette
        val chartColors = listOf(
            Color.parseColor("#22C55E"), // Success Green
            Color.parseColor("#3B82F6"), // Primary Blue
            Color.parseColor("#F59E0B"), // Warning Amber
            Color.parseColor("#EF4444"), // Error Red
            Color.parseColor("#8B5CF6"), // Purple
            Color.parseColor("#EC4899")  // Pink
        )
        
        dataSet.colors = chartColors
        dataSet.sliceSpace = 4f
        dataSet.valueTextSize = 13f
        dataSet.valueTextColor = Color.WHITE
        dataSet.valueTypeface = android.graphics.Typeface.DEFAULT_BOLD

        val pieData = PieData(dataSet)
        pieData.setValueFormatter(PercentFormatter(pieChart))

        pieChart.apply {
            this.data = pieData
            description.isEnabled = false
            setUsePercentValues(true)
            
            // Modern Styling
            holeRadius = 60f
            transparentCircleRadius = 65f
            setHoleColor(Color.parseColor("#111827")) // brand_surface
            
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(11f)
            setEntryLabelTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            
            animateY(1200, com.github.mikephil.charting.animation.Easing.EaseInOutCubic)
            
            legend.apply {
                isEnabled = true
                textColor = Color.parseColor("#9CA3AF") // text_secondary
                textSize = 12f
                formSize = 12f
                xEntrySpace = dpToPx(16f)
                verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
                orientation = com.github.mikephil.charting.components.Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
            }
            
            invalidate()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
