package com.tadiwaprintbuddy.app

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.CategoryRevenue
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityDashboardBinding
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

        fetchDashboardData()
        fetchPieChartData()
    }

    private fun fetchDashboardData() {
        lifecycleScope.launch {
            val totalRevenue = repository.getTotalRevenue() ?: 0.0
            val totalOrders = repository.getTotalOrders()
            val todaysRevenue = repository.getTodaysRevenue() ?: 0.0

            updateUI(totalRevenue, totalOrders, todaysRevenue)
        }
    }

    private fun fetchPieChartData() {
        lifecycleScope.launch {
            try {
                val pieChart = PieChart(this@DashboardActivity)
                binding.pieChartContainer.addView(pieChart, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

                val revenueByCategory = repository.getRevenueByCategory()
                if (revenueByCategory.isEmpty()) {
                    binding.pieChartContainer.visibility = View.GONE
                } else {
                    binding.pieChartContainer.visibility = View.VISIBLE
                    setupPieChart(pieChart, revenueByCategory)
                }
            } catch (t: Throwable) {
                Log.e("DashboardActivity", "CRITICAL: Failed to create or setup PieChart", t)
                binding.pieChartContainer.visibility = View.GONE
            }
        }
    }

    private fun setupPieChart(pieChart: PieChart, data: List<CategoryRevenue>) {
        val entries = data.map { PieEntry(it.total.toFloat(), it.category) }

        val dataSet = PieDataSet(entries, "Revenue by Category")
        dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()

        val pieData = PieData(dataSet)
        pieData.setValueTextSize(14f)
        pieData.setValueFormatter(PercentFormatter(pieChart))

        pieChart.apply {
            this.data = pieData
            description.isEnabled = false
            setUsePercentValues(true)
            animateY(1000)
            invalidate()
        }
    }

    private fun updateUI(totalRevenue: Double, totalOrders: Int, todaysRevenue: Double) {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

        binding.textTotalRevenue.text = format.format(totalRevenue)
        binding.textTotalOrders.text = totalOrders.toString()
        binding.textTodaysRevenue.text = format.format(todaysRevenue)
    }
}
