package com.tadiwaprintbuddy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.DebtorCredit
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityDebtorCreditBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebtorCreditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebtorCreditBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: DebtorCreditAdapter
    private var allDebtorCredits: List<DebtorCredit> = emptyList()
    private var currentFilter = FilterMode.ALL

    private enum class FilterMode {
        ALL, OWES_ME, I_OWE_CHANGE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebtorCreditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupRecyclerView()
        setupSearch()
        setupSummaryCardFilters()

        binding.buttonAddDebtorCredit.setOnClickListener {
            showAddNewEntryDialog()
        }

        loadDebtorCredits()
    }

    private fun setupRecyclerView() {
        adapter = DebtorCreditAdapter(emptyList(), { debtorCredit ->
            showSettlePaymentDialog(debtorCredit)
        }, { debtorCredit ->
            try {
                val intent = Intent(this, SettlementHistoryActivity::class.java).apply {
                    putExtra("EXTRA_CUSTOMER_ID", debtorCredit.customerId)
                    putExtra("EXTRA_CUSTOMER_NAME", debtorCredit.customerName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Error opening history", Toast.LENGTH_SHORT).show()
            }
        }, { debtorCredit ->
            showDeleteCustomerDialog(debtorCredit)
        }, { debtorCredit ->
            showEditBalanceDialog(debtorCredit)
        })
        binding.recyclerDebtorCredits.layoutManager = LinearLayoutManager(this)
        binding.recyclerDebtorCredits.adapter = adapter
    }

    private fun showDeleteCustomerDialog(debtorCredit: DebtorCredit) {
        AlertDialog.Builder(this)
            .setTitle("Delete Customer")
            .setMessage("Are you sure you want to delete ${debtorCredit.customerName}? This will also delete all their orders and settlement history.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteCustomerCompletely(debtorCredit.customerId)
                    loadDebtorCredits()
                    Toast.makeText(this@DebtorCreditActivity, "Customer deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
                binding.imageClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.imageClearSearch.setOnClickListener {
            binding.editSearch.text.clear()
        }
    }

    private fun setupSummaryCardFilters() {
        binding.cardOwesMe.setOnClickListener {
            toggleFilter(FilterMode.OWES_ME)
        }
        binding.cardIOwe.setOnClickListener {
            toggleFilter(FilterMode.I_OWE_CHANGE)
        }
    }

    private fun toggleFilter(mode: FilterMode) {
        currentFilter = if (currentFilter == mode) FilterMode.ALL else mode
        updateFilterUI()
        applyFilters()
    }

    private fun updateFilterUI() {
        val activeAlpha = 1.0f
        val inactiveAlpha = 0.4f

        when (currentFilter) {
            FilterMode.ALL -> {
                binding.cardOwesMe.alpha = activeAlpha
                binding.cardIOwe.alpha = activeAlpha
                binding.textFilterLabel.visibility = View.GONE
            }
            FilterMode.OWES_ME -> {
                binding.cardOwesMe.alpha = activeAlpha
                binding.cardIOwe.alpha = inactiveAlpha
                binding.textFilterLabel.visibility = View.VISIBLE
                binding.textFilterLabel.text = "Filtering: CUSTOMER OWES"
                binding.textFilterLabel.setTextColor(ContextCompat.getColor(this, R.color.destructive))
            }
            FilterMode.I_OWE_CHANGE -> {
                binding.cardOwesMe.alpha = inactiveAlpha
                binding.cardIOwe.alpha = activeAlpha
                binding.textFilterLabel.visibility = View.VISIBLE
                binding.textFilterLabel.text = "Filtering: CHANGE DUE"
                binding.textFilterLabel.setTextColor(ContextCompat.getColor(this, R.color.warning))
            }
        }
    }

    private fun applyFilters() {
        val searchQuery = binding.editSearch.text.toString()
        
        var filteredList = allDebtorCredits
        
        // Apply category filter
        filteredList = when (currentFilter) {
            FilterMode.ALL -> filteredList
            FilterMode.OWES_ME -> filteredList.filter { it.amount > 0 }
            FilterMode.I_OWE_CHANGE -> filteredList.filter { it.amount < 0 }
        }
        
        // Apply search filter
        if (searchQuery.isNotEmpty()) {
            filteredList = filteredList.filter { 
                it.customerName.contains(searchQuery, ignoreCase = true) 
            }
        }
        
        adapter.updateDebtorCredits(filteredList)
        
        if (filteredList.isEmpty() && allDebtorCredits.isNotEmpty()) {
            binding.textNoResults.visibility = View.VISIBLE
            binding.recyclerDebtorCredits.visibility = View.GONE
        } else {
            binding.textNoResults.visibility = View.GONE
            binding.recyclerDebtorCredits.visibility = View.VISIBLE
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_debtors, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_csv -> {
                exportDebtorsToCSV()
                true
            }
            R.id.action_settlement_history -> {
                startActivity(Intent(this, SettlementHistoryActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportDebtorsToCSV() {
        if (allDebtorCredits.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "debtors_report_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
                val file = File(getExternalFilesDir(null), fileName)
                val writer = FileWriter(file)

                // Header
                writer.append("Customer Name,Status,Amount,Last Updated\n")

                // Data
                allDebtorCredits.forEach { debtor ->
                    val status = if (debtor.amount > 0) "OWES ME" else "I OWE CHANGE"
                    val displayAmount = if (debtor.amount > 0) debtor.amount else -debtor.amount
                    val lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(debtor.lastUpdated))
                    
                    writer.append("${debtor.customerName.replace(",", " ")},$status,$displayAmount,$lastUpdated\n")
                }

                writer.flush()
                writer.close()

                withContext(Dispatchers.Main) {
                    shareFile(file)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DebtorCreditActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Debtors Report"))
    }

    private fun loadDebtorCredits() {
        lifecycleScope.launch {
            allDebtorCredits = repository.getDebtorCreditList()
            applyFilters()
            updateTotals(allDebtorCredits)
        }
    }

    private fun updateTotals(debtorCredits: List<DebtorCredit>) {
        val totalOwedToMe = debtorCredits.filter { it.amount > 0 }.sumOf { it.amount }
        val totalIOwe = debtorCredits.filter { it.amount < 0 }.sumOf { -it.amount }
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)

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
                val amountDelta = if (currentAmount >= 0) -paymentAmount else paymentAmount

                lifecycleScope.launch {
                    repository.addOrUpdateDebtorCredit(debtorCredit.customerName, amountDelta)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DebtorCreditActivity, "Payment applied successfully", Toast.LENGTH_SHORT).show()
                        loadDebtorCredits()
                    }
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
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
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
                val finalAmount = if (selectedType == "I Owe Change") -amount else amount

                lifecycleScope.launch {
                    repository.addOrUpdateDebtorCredit(customerName, finalAmount)
                    loadDebtorCredits()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditBalanceDialog(debtorCredit: DebtorCredit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_balance, null)
        val textCurrentBalance = dialogView.findViewById<android.widget.TextView>(R.id.textCurrentBalance)
        val editNewBalance = dialogView.findViewById<EditText>(R.id.editNewBalance)
        val textAdjustment = dialogView.findViewById<android.widget.TextView>(R.id.textAdjustment)
        val editReason = dialogView.findViewById<EditText>(R.id.editReason)

        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)

        val currentBalance = debtorCredit.amount
        textCurrentBalance.text = format.format(currentBalance)
        editNewBalance.setText(currentBalance.toString())

        val updateAdjustment = {
            val newVal = editNewBalance.text.toString().toDoubleOrNull() ?: 0.0
            val delta = newVal - currentBalance
            textAdjustment.text = format.format(delta)
            if (delta > 0) {
                textAdjustment.setTextColor(ContextCompat.getColor(this, R.color.brand_error))
            } else if (delta < 0) {
                textAdjustment.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
            } else {
                textAdjustment.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        editNewBalance.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateAdjustment()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateAdjustment()

        AlertDialog.Builder(this)
            .setTitle("Edit Balance for ${debtorCredit.customerName}")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newBalance = editNewBalance.text.toString().toDoubleOrNull()
                if (newBalance == null) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val reason = editReason.text.toString().trim().ifEmpty { null }

                lifecycleScope.launch {
                    try {
                        repository.adjustCustomerBalance(debtorCredit.customerId, newBalance, reason)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@DebtorCreditActivity, "Balance adjusted", Toast.LENGTH_SHORT).show()
                            loadDebtorCredits()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@DebtorCreditActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
