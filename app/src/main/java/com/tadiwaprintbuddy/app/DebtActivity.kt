package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityDebtBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class DebtActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebtBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: DebtAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebtBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        adapter = DebtAdapter(emptyList()) { order ->
            showUpdatePaymentDialog(order)
        }

        binding.recyclerDebts.layoutManager = LinearLayoutManager(this)
        binding.recyclerDebts.adapter = adapter

        loadDebts()
    }

    private fun loadDebts() {
        lifecycleScope.launch {
            val unpaidOrders = repository.getUnpaidOrders()
            adapter.updateDebts(unpaidOrders)
            updateTotalOwed(unpaidOrders)
        }
    }

    private fun updateTotalOwed(orders: List<Order>) {
        val totalOwed = orders.sumOf { it.totalAmount - it.paidAmount }
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        binding.textTotalOwed.text = format.format(totalOwed)
    }

    private fun showUpdatePaymentDialog(order: Order) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_payment, null)
        val editAdditionalPayment = dialogView.findViewById<EditText>(R.id.editAdditionalPayment)

        AlertDialog.Builder(this)
            .setTitle("Update Payment for ${order.customerName}")
            .setView(dialogView)
            .setPositiveButton("Update") { _, _ ->
                val additionalPayment = editAdditionalPayment.text.toString().toDoubleOrNull() ?: 0.0
                val newPaidAmount = order.paidAmount + additionalPayment
                lifecycleScope.launch {
                    repository.updatePayment(order.id, newPaidAmount)
                    loadDebts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
