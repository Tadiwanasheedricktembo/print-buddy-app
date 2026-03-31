package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Order
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

        adapter = OrdersAdapter(emptyList()) { order ->
            showDeleteConfirmation(order)
        }

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

    private fun showDeleteConfirmation(order: Order) {
        AlertDialog.Builder(this)
            .setTitle("Delete Order")
            .setMessage("Are you sure you want to delete Order #${order.id} for ${order.customerName} of amount ₹${"%.2f".format(order.totalAmount)}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteOrder(order.id)
                    loadOrders()
                    Toast.makeText(this@OrdersActivity, "Order deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
