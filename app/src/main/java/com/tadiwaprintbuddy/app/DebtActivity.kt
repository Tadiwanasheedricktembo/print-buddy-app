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
import java.math.BigDecimal
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
        val totalOwed = orders.fold(BigDecimal.ZERO) { acc, order -> acc.add(order.totalAmount.subtract(order.paidAmount)) }
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)
        binding.textTotalOwed.text = format.format(totalOwed.toDouble())
    }

    private fun showUpdatePaymentDialog(order: Order) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_payment, null)
        val editAdditionalPayment = dialogView.findViewById<EditText>(R.id.editAdditionalPayment)

        AlertDialog.Builder(this)
            .setTitle("Update Payment for ${order.customerName}")
            .setView(dialogView)
            .setPositiveButton("Update") { _, _ ->
                val additionalPaymentStr = editAdditionalPayment.text.toString()
                val additionalPayment = try { BigDecimal(additionalPaymentStr) } catch (e: Exception) { BigDecimal.ZERO }
                val newPaidAmount = order.paidAmount.add(additionalPayment)
                lifecycleScope.launch {
                    repository.updatePayment(order.id, newPaidAmount, "CASH")
                    loadDebts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
