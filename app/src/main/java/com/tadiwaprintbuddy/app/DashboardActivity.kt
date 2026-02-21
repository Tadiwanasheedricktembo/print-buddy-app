package com.tadiwaprintbuddy.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tadiwaprintbuddy.app.data.AppDatabase
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
    }

    private fun fetchDashboardData() {
        lifecycleScope.launch {
            val totalRevenue = repository.getTotalRevenue() ?: 0.0
            val totalOrders = repository.getTotalOrders()
            val todaysRevenue = repository.getTodaysRevenue() ?: 0.0

            updateUI(totalRevenue, totalOrders, todaysRevenue)
        }
    }

    private fun updateUI(totalRevenue: Double, totalOrders: Int, todaysRevenue: Double) {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

        binding.textTotalRevenue.text = format.format(totalRevenue)
        binding.textTotalOrders.text = totalOrders.toString()
        binding.textTodaysRevenue.text = format.format(todaysRevenue)
    }
}
