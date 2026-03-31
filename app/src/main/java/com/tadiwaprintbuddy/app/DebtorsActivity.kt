package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.DebtorSummary
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityDebtorsBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class DebtorsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebtorsBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: DebtorsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebtorsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        adapter = DebtorsAdapter(emptyList()) { debtor ->
            showReceivePaymentDialog(debtor)
        }

        binding.recyclerDebtors.layoutManager = LinearLayoutManager(this)
        binding.recyclerDebtors.adapter = adapter

        loadDebtors()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settlement_history -> {
                startActivity(Intent(this, SettlementHistoryActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadDebtors() {
        lifecycleScope.launch {
            val debtors = repository.getDebtors()
            adapter.updateDebtors(debtors)
            updateTotalOwed(debtors)
        }
    }

    private fun updateTotalOwed(debtors: List<DebtorSummary>) {
        val totalOwed = debtors.sumOf { it.totalOwed }
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        binding.textTotalOwed.text = format.format(totalOwed)
    }

    private fun showReceivePaymentDialog(debtor: DebtorSummary) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_receive_payment, null)
        val editPaymentAmount = dialogView.findViewById<EditText>(R.id.editPaymentAmount)

        AlertDialog.Builder(this)
            .setTitle("Receive Payment from ${debtor.customerName}")
            .setView(dialogView)
            .setPositiveButton("Receive") { _, _ ->
                val paymentAmount = editPaymentAmount.text.toString().toDoubleOrNull() ?: 0.0
                lifecycleScope.launch {
                    repository.applyPaymentToCustomer(debtor.customerName, paymentAmount)
                    loadDebtors()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
