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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dashboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_inventory -> {
                startActivity(Intent(this, InventoryActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun observeInsights() {
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)

        lifecycleScope.launch {
            repository.getTotalRevenueFlow().collectLatest { revenue ->
                binding.textTotalRevenue.text = format.format(revenue ?: 0.0)
                val netProfit = repository.getNetProfit()
                binding.textNetProfit.text = format.format(netProfit)
            }
        }

        lifecycleScope.launch {
            repository.getTotalOrdersFlow().collectLatest { count ->
                binding.textTotalOrders.text = count.toString()
            }
        }

        lifecycleScope.launch {
            repository.getCashInHandFlow().collectLatest { cash ->
                binding.textCashInHand.text = format.format(cash ?: 0.0)
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

        lifecycleScope.launch {
            repository.getLowStockItemsFlow().collectLatest { lowStock ->
                if (lowStock.isNotEmpty()) {
                    binding.cardLowStock.visibility = View.VISIBLE
                    val names = lowStock.joinToString(", ") { it.name }
                    binding.textLowStockAlert.text = getString(R.string.low_stock_alert, names)
                } else {
                    binding.cardLowStock.visibility = View.GONE
                }
            }
        }
    }

    private fun setupPieChart(pieChart: PieChart, data: List<CategoryRevenue>) {
        val entries = data.map { PieEntry(it.total.toFloat(), it.category) }

        val dataSet = PieDataSet(entries, "")
        
        // Apply screenshot specific palette
        val chartColors = listOf(
            Color.parseColor("#22C55E"), // Success Green
            Color.parseColor("#3B82F6"), // Primary Blue
            Color.parseColor("#F59E0B"), // Warning Amber
            Color.parseColor("#8B5CF6")  // Purple
        )
        
        dataSet.colors = chartColors
        dataSet.sliceSpace = 2f
        dataSet.valueTextSize = 13f
        dataSet.valueTextColor = Color.WHITE
        dataSet.valueTypeface = android.graphics.Typeface.DEFAULT_BOLD

        val pieData = PieData(dataSet)
        pieData.setValueFormatter(PercentFormatter(pieChart))

        pieChart.apply {
            this.data = pieData
            description.isEnabled = false
            setUsePercentValues(true)
            setDrawEntryLabels(true)
            
            // Exact Donut Styling from screenshot
            holeRadius = 68f
            transparentCircleRadius = 0f
            setHoleColor(Color.parseColor("#020814")) // Actual app background
            
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(11f)
            setEntryLabelTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            
            animateY(1000, com.github.mikephil.charting.animation.Easing.EaseInOutCubic)
            
            legend.apply {
                isEnabled = true
                textColor = Color.parseColor("#94A3B8") // text_secondary
                textSize = 12f
                form = com.github.mikephil.charting.components.Legend.LegendForm.SQUARE
                formSize = 10f
                xEntrySpace = 20f
                yEntrySpace = 10f
                verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
                orientation = com.github.mikephil.charting.components.Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
            }
            
            invalidate()
        }
    }
}
