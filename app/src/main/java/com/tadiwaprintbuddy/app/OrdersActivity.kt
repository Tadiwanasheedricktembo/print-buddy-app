package com.tadiwaprintbuddy.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityOrdersBinding
import kotlinx.coroutines.launch

class OrdersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrdersBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: OrdersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        adapter = OrdersAdapter(emptyList())

        binding.recyclerOrders.layoutManager = LinearLayoutManager(this)
        binding.recyclerOrders.adapter = adapter

        loadOrders()
    }

    private fun loadOrders() {
        lifecycleScope.launch {
            val orders = repository.getAllOrders()
            if (orders.isEmpty()) {
                binding.textNoOrders.visibility = android.view.View.VISIBLE
                binding.recyclerOrders.visibility = android.view.View.GONE
            } else {
                binding.textNoOrders.visibility = android.view.View.GONE
                binding.recyclerOrders.visibility = android.view.View.VISIBLE
                adapter.updateOrders(orders)
            }
        }
    }
}
