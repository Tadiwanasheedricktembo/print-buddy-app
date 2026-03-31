package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.DebtorCredit
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityDebtorCreditBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

class DebtorCreditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebtorCreditBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: DebtorCreditAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebtorCreditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        adapter = DebtorCreditAdapter(emptyList()) { debtorCredit ->
            showSettlePaymentDialog(debtorCredit)
        }

        binding.recyclerDebtorCredits.layoutManager = LinearLayoutManager(this)
        binding.recyclerDebtorCredits.adapter = adapter

        binding.buttonAddDebtorCredit.setOnClickListener {
            showAddNewEntryDialog()
        }

        loadDebtorCredits()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_debtors, menu)
        return true
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

    private fun loadDebtorCredits() {
        lifecycleScope.launch {
            val debtorCredits = repository.getDebtorCreditList()
            adapter.updateDebtorCredits(debtorCredits)
            updateTotals(debtorCredits)
        }
    }

    private fun updateTotals(debtorCredits: List<DebtorCredit>) {
        val totalOwedToMe = debtorCredits.filter { it.amount > 0 }.sumOf { it.amount }
        val totalIOwe = debtorCredits.filter { it.amount < 0 }.sumOf { -it.amount }
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

        binding.textTotalOwedToMe.text = format.format(totalOwedToMe)
        binding.textTotalIOwe.text = format.format(totalIOwe)
    }

    private fun showSettlePaymentDialog(debtorCredit: DebtorCredit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settle_payment, null)
        val editPaymentAmount = dialogView.findViewById<EditText>(R.id.editPaymentAmount)

        AlertDialog.Builder(this)
            .setTitle("Settle for ${debtorCredit.customerName}")
            .setView(dialogView)
            .setPositiveButton("Apply Payment") { _, _ ->

                val paymentAmount = editPaymentAmount.text.toString().toDoubleOrNull()

                if (paymentAmount == null || paymentAmount <= 0.0) {
                    Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val currentAmount = debtorCredit.amount

                // Correct delta direction
                val amountDelta = if (currentAmount > 0) {
                    -paymentAmount   // customer paying you
                } else {
                    paymentAmount    // you giving change
                }

                val remainingAfter = currentAmount + amountDelta

                // Prevent over-settlement (flipping sign)
                if (
                    (currentAmount > 0 && remainingAfter < 0) ||
                    (currentAmount < 0 && remainingAfter > 0)
                ) {
                    Toast.makeText(
                        this,
                        "Amount exceeds remaining balance",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    repository.addOrUpdateDebtorCredit(
                        debtorCredit.customerName,
                        amountDelta
                    )
                    loadDebtorCredits()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddNewEntryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_debtor_credit, null)
        val editCustomerName = dialogView.findViewById<EditText>(R.id.editCustomerName)
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinnerType)
        val editAmount = dialogView.findViewById<EditText>(R.id.editAmount)

        val types = arrayOf("Owes Me", "I Owe Change")
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            types
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = spinnerAdapter

        AlertDialog.Builder(this)
            .setTitle("Add New Entry")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->

                val customerName = editCustomerName.text.toString()
                val amount = editAmount.text.toString().toDoubleOrNull()

                if (customerName.isBlank() || amount == null || amount <= 0.0) {
                    Toast.makeText(this, "Enter valid details", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedType = spinnerType.selectedItem.toString()
                val finalAmount = if (selectedType == "I Owe Change") {
                    -amount
                } else {
                    amount
                }

                lifecycleScope.launch {
                    repository.addOrUpdateDebtorCredit(customerName, finalAmount)
                    loadDebtorCredits()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
