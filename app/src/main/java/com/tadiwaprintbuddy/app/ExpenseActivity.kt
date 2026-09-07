package com.tadiwaprintbuddy.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Expense
import com.tadiwaprintbuddy.app.data.ExpenseCategory
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityExpenseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExpenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExpenseBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: ExpenseAdapter
    private var allExpenses: List<Expense> = emptyList()

    private val restorePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                showRestoreConfirmDialog(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupRecyclerView()
        observeExpenses()

        binding.btnAddExpense.setOnClickListener {
            showAddExpenseDialog()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_expense, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_csv -> {
                if (allExpenses.isNotEmpty()) exportToCSV(this, allExpenses)
                else Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_export_json -> {
                if (allExpenses.isNotEmpty()) exportToJSON(this, allExpenses)
                else Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_restore_json -> {
                openFilePicker()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = ExpenseAdapter(emptyList()) { expense ->
            showDeleteExpenseDialog(expense)
        }
        binding.recyclerExpenses.layoutManager = LinearLayoutManager(this)
        binding.recyclerExpenses.adapter = adapter
    }

    private fun observeExpenses() {
        lifecycleScope.launch {
            repository.getAllExpensesFlow().collectLatest { expenses ->
                allExpenses = expenses
                adapter.updateExpenses(expenses)
                updateTotal(expenses.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) })
                binding.textEmpty.visibility = if (expenses.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateTotal(total: BigDecimal) {
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)
        binding.textTotalExpenses.text = format.format(total.toDouble())
    }

    private fun showAddExpenseDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_expense, null)
        val editAmount = dialogView.findViewById<EditText>(R.id.editAmount)
        val editCategory = dialogView.findViewById<EditText>(R.id.editCategory)
        val editNote = dialogView.findViewById<EditText>(R.id.editNote)
        val spinnerPaymentMethod = dialogView.findViewById<Spinner>(R.id.spinnerPaymentMethod)

        val methods = arrayOf("CASH", "UPI")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, methods)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPaymentMethod.adapter = spinnerAdapter

        AlertDialog.Builder(this)
            .setTitle("Add New Expense")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val amountStr = editAmount.text.toString()
                val amount = try { BigDecimal(amountStr) } catch (e: Exception) { BigDecimal.ZERO }
                val category = editCategory.text.toString()
                val note = editNote.text.toString()
                val method = spinnerPaymentMethod.selectedItem.toString()

                if (amount.compareTo(BigDecimal.ZERO) > 0 && category.isNotBlank()) {
                    lifecycleScope.launch {
                        repository.addExpense(amount, category, note.ifBlank { null }, method)
                        Toast.makeText(this@ExpenseActivity, "Expense added", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Amount and Category are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteExpenseDialog(expense: Expense) {
        AlertDialog.Builder(this)
            .setTitle("Delete Expense")
            .setMessage("Are you sure you want to delete this expense of ${expense.amount.toPlainString()}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteExpense(expense.id)
                    Toast.makeText(this@ExpenseActivity, "Expense deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        restorePickerLauncher.launch(intent)
    }

    private fun showRestoreConfirmDialog(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restore Expenses")
            .setMessage("Do you want to MERGE with existing data or REPLACE everything?")
            .setPositiveButton("Merge") { _, _ -> restoreFromJSON(uri, false) }
            .setNeutralButton("Replace All") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("This will DELETE all current expenses. Continue?")
                    .setPositiveButton("Yes, Replace") { _, _ -> restoreFromJSON(uri, true) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreFromJSON(uri: Uri, fullReplace: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val json = reader.readText()
                reader.close()

                val listType = object : TypeToken<List<Expense>>() {}.type
                val data: List<Expense> = Gson().fromJson(json, listType)

                if (data.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ExpenseActivity, "Invalid or empty backup file", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                repository.restoreExpenses(data, fullReplace)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ExpenseActivity, "Expenses restored successfully!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ExpenseActivity, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportToCSV(context: Context, data: List<Expense>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "expenses_${System.currentTimeMillis()}.csv"
                val file = File(context.getExternalFilesDir(null), fileName)
                val writer = FileWriter(file)
                writer.append("Category,Amount,Method,Note,Timestamp\n")
                data.forEach {
                    writer.append("${it.category},${it.amount.toPlainString()},${it.paymentMethod},${it.note?.replace(",", " ") ?: ""},${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it.timestamp))}\n")
                }
                writer.flush()
                writer.close()
                withContext(Dispatchers.Main) { shareFile(context, file, "text/csv", "Export CSV") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun exportToJSON(context: Context, data: List<Expense>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = Gson().toJson(data)
                val file = File(context.getExternalFilesDir(null), "expenses_backup_${System.currentTimeMillis()}.json")
                file.writeText(json)
                withContext(Dispatchers.Main) { shareFile(context, file, "application/json", "Export JSON") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
