package com.tadiwaprintbuddy.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.data.SettlementHistory
import com.tadiwaprintbuddy.app.databinding.ActivitySettlementHistoryBinding
import com.tadiwaprintbuddy.app.databinding.ItemSettlementBinding
import com.tadiwaprintbuddy.app.databinding.ItemSettlementGroupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettlementHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettlementHistoryBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: GroupedSettlementAdapter
    private var allSettlements: List<SettlementHistory> = emptyList()

    private val restorePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                showRestoreConfirmDialog(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettlementHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        adapter = GroupedSettlementAdapter(emptyList())
        binding.recyclerSettlements.layoutManager = LinearLayoutManager(this)
        binding.recyclerSettlements.adapter = adapter

        loadSettlements()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_settlement_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_csv -> {
                if (allSettlements.isNotEmpty()) {
                    exportSettlementHistoryToCSV(this, allSettlements)
                } else {
                    Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_export_json -> {
                if (allSettlements.isNotEmpty()) {
                    exportToJSON(this, allSettlements)
                } else {
                    Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_restore_json -> {
                openFilePicker()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
            .setTitle("Restore Data")
            .setMessage("Do you want to MERGE with existing data or REPLACE everything?")
            .setPositiveButton("Merge") { _, _ -> restoreFromJSON(uri, false) }
            .setNeutralButton("Replace All") { _, _ -> 
                AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("This will DELETE current history. Continue?")
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

                val listType = object : TypeToken<List<SettlementHistory>>() {}.type
                val data: List<SettlementHistory> = Gson().fromJson(json, listType)

                if (data.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettlementHistoryActivity, "Invalid or empty backup file", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                repository.restoreSettlements(data, fullReplace)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettlementHistoryActivity, "Data restored successfully!", Toast.LENGTH_SHORT).show()
                    loadSettlements()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettlementHistoryActivity, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadSettlements() {
        val targetCustomerName = intent.getStringExtra("EXTRA_CUSTOMER_NAME")
        val targetCustomerId = intent.getLongExtra("EXTRA_CUSTOMER_ID", -1L)
        
        lifecycleScope.launch {
            try {
                allSettlements = repository.getAllSettlements()
                if (allSettlements.isEmpty()) {
                    binding.textEmpty.visibility = View.VISIBLE
                    binding.recyclerSettlements.visibility = View.GONE
                } else {
                    binding.textEmpty.visibility = View.GONE
                    binding.recyclerSettlements.visibility = View.VISIBLE
                    
                    val grouped = allSettlements.groupBy { it.customerId }
                        .map { (id, trans) ->
                            val sortedTrans = trans.sortedByDescending { it.timestamp }
                            val name = sortedTrans.firstOrNull { it.customerName.isNotBlank() }?.customerName ?: "Unknown"
                            
                            GroupedSettlement(
                                customerId = id,
                                customerName = name,
                                totalOwed = sortedTrans.firstOrNull()?.balanceAfter ?: 0.0,
                                transactions = sortedTrans,
                                isExpanded = id == targetCustomerId || (targetCustomerName != null && name.equals(targetCustomerName, ignoreCase = true))
                            )
                        }.sortedBy { it.customerName }
                    
                    adapter.updateGroups(grouped)

                    // Scroll to the targeted customer if found
                    if (targetCustomerId != -1L || targetCustomerName != null) {
                        val index = grouped.indexOfFirst { 
                            it.customerId == targetCustomerId || (targetCustomerName != null && it.customerName.equals(targetCustomerName, ignoreCase = true)) 
                        }
                        if (index != -1) {
                            binding.recyclerSettlements.post {
                                binding.recyclerSettlements.scrollToPosition(index)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // PHASE 4: Fallback to flat list if grouping fails
                android.util.Log.e("SettlementHistory", "Grouping failed, falling back", e)
                loadFlatSettlements()
            }
        }
    }

    private fun exportSettlementHistoryToCSV(context: Context, data: List<SettlementHistory>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "settlement_history_${System.currentTimeMillis()}.csv"
                val file = File(context.getExternalFilesDir(null), fileName)
                val writer = FileWriter(file)
                
                // Header
                writer.append("Customer,Previous Balance,Amount Paid,Remaining Balance,Type,Note,Timestamp\n")

                // Data
                data.forEach {
                    writer.append("${it.customerName},")
                    writer.append("${it.balanceBefore},")
                    writer.append("${it.amountPaid},")
                    writer.append("${it.balanceAfter},")
                    writer.append("${it.type},")
                    writer.append("${it.note.replace(",", " ")},")
                    writer.append("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it.timestamp))}\n")
                }

                writer.flush()
                writer.close()

                withContext(Dispatchers.Main) {
                    shareFile(context, file, "text/csv", "Export CSV")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportToJSON(context: Context, data: List<SettlementHistory>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val gson = Gson()
                val json = gson.toJson(data)
                val file = File(context.getExternalFilesDir(null), "backup_${System.currentTimeMillis()}.json")
                file.writeText(json)

                withContext(Dispatchers.Main) {
                    shareFile(context, file, "application/json", "Export JSON")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    private fun loadFlatSettlements() {
        lifecycleScope.launch {
            val settlements = repository.getAllSettlements()
            // We'd need a different adapter type or make GroupedSettlementAdapter handle flat data
            // For now, let's keep it robust within the main loader
        }
    }
}

data class GroupedSettlement(
    val customerId: Long,
    val customerName: String,
    val totalOwed: Double,
    val transactions: List<SettlementHistory>,
    var isExpanded: Boolean = false
)

class GroupedSettlementAdapter(private var groups: List<GroupedSettlement>) :
    RecyclerView.Adapter<GroupedSettlementAdapter.GroupViewHolder>() {

    class GroupViewHolder(val binding: ItemSettlementGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemSettlementGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

        holder.binding.textCustomerName.text = group.customerName
        holder.binding.textTransactionCount.text = "${group.transactions.size} transactions"
        
        if (group.totalOwed > 0) {
            holder.binding.textTotalOwed.text = "Owes: ${format.format(group.totalOwed)}"
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_error))
        } else if (group.totalOwed < 0) {
            holder.binding.textTotalOwed.text = "Change: ${format.format(-group.totalOwed)}"
            holder.binding.textTotalOwed.setTextColor(Color.parseColor("#22C55E")) // Green
        } else {
            holder.binding.textTotalOwed.text = "Settled"
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_primary))
        }

        holder.binding.recyclerTransactions.visibility = if (group.isExpanded) View.VISIBLE else View.GONE
        holder.binding.imageExpand.rotation = if (group.isExpanded) 180f else 0f

        holder.binding.layoutHeader.setOnClickListener {
            group.isExpanded = !group.isExpanded
            notifyItemChanged(position)
        }

        if (group.isExpanded) {
            holder.binding.recyclerTransactions.layoutManager = LinearLayoutManager(holder.itemView.context)
            holder.binding.recyclerTransactions.adapter = TransactionAdapter(group.transactions)
        }
    }

    override fun getItemCount() = groups.size

    fun updateGroups(newGroups: List<GroupedSettlement>) {
        groups = newGroups
        notifyDataSetChanged()
    }
}

class TransactionAdapter(private val transactions: List<SettlementHistory>) :
    RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSettlementBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettlementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val transaction = transactions[position]
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        val dateFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())

        holder.binding.textCustomerName.visibility = View.GONE
        holder.binding.textDate.text = dateFormat.format(Date(transaction.timestamp))
        
        // Use mapping for backward-compatible logic (Phase 3)
        holder.binding.textBalanceBefore.text = format.format(transaction.balanceBefore)
        
        // Transaction Amount Logic
        val delta = if (transaction.transactionAmount != 0.0) transaction.transactionAmount else (transaction.balanceAfter - transaction.balanceBefore)
        if (delta > 0) {
            holder.binding.textTransAmount.text = "+ ${format.format(delta)}"
            holder.binding.textTransAmount.setTextColor(0xFFC62828.toInt()) // Red
        } else if (delta < 0) {
            holder.binding.textTransAmount.text = "- ${format.format(-delta)}"
            holder.binding.textTransAmount.setTextColor(0xFF2E7D32.toInt()) // Green
        } else {
            holder.binding.textTransAmount.text = format.format(0.0)
            holder.binding.textTransAmount.setTextColor(Color.GRAY)
        }

        val displayBalanceAfter = if (transaction.newBalance != 0.0 || transaction.transactionAmount != 0.0) transaction.newBalance else transaction.balanceAfter
        holder.binding.textBalanceAfter.text = format.format(displayBalanceAfter)

        if (displayBalanceAfter > 0) {
            holder.binding.textBalanceAfter.setTextColor(Color.RED)
        } else if (displayBalanceAfter == 0.0) {
            holder.binding.textBalanceAfter.setTextColor(Color.parseColor("#22C55E"))
        }

        holder.binding.textType.text = transaction.type
        if (transaction.type == "ADJUSTMENT") {
            holder.binding.textType.setBackgroundResource(R.drawable.bg_badge_orange)
        } else {
            holder.binding.textType.setBackgroundResource(R.drawable.bg_badge_gray)
        }

        if (transaction.note.isEmpty()) {
            holder.binding.textNote.visibility = View.GONE
        } else {
            holder.binding.textNote.visibility = View.VISIBLE
            holder.binding.textNote.text = transaction.note
        }
    }

    override fun getItemCount() = transactions.size
}
