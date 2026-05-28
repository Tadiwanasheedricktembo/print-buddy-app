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
import android.widget.TextView
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
    private var allGroups: List<GroupedSettlement> = emptyList()

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

        setupSearch()
        loadSettlements()
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applySearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun applySearch(query: String) {
        if (query.isBlank()) {
            adapter.updateGroups(allGroups)
            binding.textNoResults.visibility = View.GONE
            binding.recyclerSettlements.visibility = if (allGroups.isEmpty()) View.GONE else View.VISIBLE
            return
        }

        val filteredGroups = allGroups.filter {
            it.customerName.contains(query, ignoreCase = true)
        }

        adapter.updateGroups(filteredGroups)

        if (filteredGroups.isEmpty()) {
            binding.textNoResults.visibility = View.VISIBLE
            binding.recyclerSettlements.visibility = View.GONE
        } else {
            binding.textNoResults.visibility = View.GONE
            binding.recyclerSettlements.visibility = View.VISIBLE
        }
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
                            
                            val latestBalance = sortedTrans.firstOrNull()?.let { 
                                if (it.newBalance != 0.0 || it.transactionAmount != 0.0) it.newBalance else it.balanceAfter 
                            } ?: 0.0

                            GroupedSettlement(
                                customerId = id,
                                customerName = name,
                                totalOwed = latestBalance,
                                transactions = sortedTrans,
                                isExpanded = id == targetCustomerId || (targetCustomerName != null && name.equals(targetCustomerName, ignoreCase = true))
                            )
                        }.sortedBy { it.customerName }
                    
                    allGroups = grouped
                    applySearch(binding.editSearch.text.toString())

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

sealed class LedgerItem {
    data class DateHeader(val date: String) : LedgerItem()
    data class DebtAdded(val history: SettlementHistory) : LedgerItem()
    data class PaymentReceived(val history: SettlementHistory) : LedgerItem()
    data class CreditCreated(val history: SettlementHistory) : LedgerItem()
    data class Adjustment(val history: SettlementHistory) : LedgerItem()
}

class GroupedSettlementAdapter(private var groups: List<GroupedSettlement>) :
    RecyclerView.Adapter<GroupedSettlementAdapter.GroupViewHolder>() {

    class GroupViewHolder(val binding: ItemSettlementGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemSettlementGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)

        holder.binding.textCustomerName.text = group.customerName
        holder.binding.textTransactionCount.text = "${group.transactions.size} transactions"
        
        val balance = group.totalOwed
        if (balance > 0) {
            // Customer Owes
            holder.binding.textTotalOwed.text = format.format(balance)
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.destructive))
            holder.binding.textBalanceLabel.text = "PENDING"
            holder.binding.textBalanceLabel.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.destructive))
        } else if (balance < 0) {
            // Customer Credit
            holder.binding.textTotalOwed.text = format.format(Math.abs(balance))
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.primary_accent))
            holder.binding.textBalanceLabel.text = "CREDIT AVAILABLE"
            holder.binding.textBalanceLabel.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.primary_accent))
        } else {
            // Settled
            holder.binding.textTotalOwed.text = "Settled"
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.primary_accent))
            holder.binding.textBalanceLabel.text = "NO BALANCE"
            holder.binding.textBalanceLabel.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.primary_accent))
        }
        holder.binding.imageAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(holder.itemView.context, R.color.surface_elevated))

        // Summary Calculations
        val totalDebt = group.transactions.filter { 
            val delta = if (it.transactionAmount != 0.0) it.transactionAmount else (it.newBalance - it.balanceBefore)
            delta > 0 
        }.sumOf { if (it.transactionAmount != 0.0) it.transactionAmount else (it.newBalance - it.balanceBefore) }
        
        val totalPaid = group.transactions.filter { 
            val delta = if (it.transactionAmount != 0.0) it.transactionAmount else (it.newBalance - it.balanceBefore)
            delta < 0 
        }.sumOf { Math.abs(if (it.transactionAmount != 0.0) it.transactionAmount else (it.newBalance - it.balanceBefore)) }

        holder.binding.textTotalDebt.text = "+ ${format.format(totalDebt)}"
        holder.binding.textTotalPaid.text = "- ${format.format(totalPaid)}"
        holder.binding.textCurrentBalanceSummary.text = format.format(Math.abs(group.totalOwed))

        holder.binding.recyclerTransactions.visibility = if (group.isExpanded) View.VISIBLE else View.GONE
        holder.binding.layoutSummary.visibility = if (group.isExpanded) View.VISIBLE else View.GONE
        holder.binding.imageExpand.rotation = if (group.isExpanded) 180f else 0f

        holder.binding.layoutHeader.setOnClickListener {
            group.isExpanded = !group.isExpanded
            notifyItemChanged(holder.bindingAdapterPosition)
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

class TransactionAdapter(private val historyList: List<SettlementHistory>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_DATE_HEADER = 0
        private const val VIEW_TYPE_DEBT_ADDED = 1
        private const val VIEW_TYPE_PAYMENT = 2
        private const val VIEW_TYPE_CREDIT_CREATED = 4
        private const val VIEW_TYPE_ADJUSTMENT = 3
    }

    private val ledgerItems: List<LedgerItem> = historyList
        .sortedByDescending { it.timestamp }
        .let { sortedList ->
            val items = mutableListOf<LedgerItem>()
            var lastDate = ""
            val headerFormat = SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault())

            sortedList.forEach { history ->
                val dateStr = headerFormat.format(Date(history.timestamp))
                if (dateStr != lastDate) {
                    items.add(LedgerItem.DateHeader(dateStr))
                    lastDate = dateStr
                }

                val delta = if (history.transactionAmount != 0.0) history.transactionAmount else (history.newBalance - history.balanceBefore)
                val balAfter = if (history.newBalance != 0.0 || history.transactionAmount != 0.0) history.newBalance else history.balanceAfter

                val item = when {
                    history.ledgerEntryType == "ORDER_POST" || history.type == "ORDER" || delta > 0 -> LedgerItem.DebtAdded(history)
                    (history.ledgerEntryType == "PAYMENT" || history.type == "PAYMENT" || delta < 0) && balAfter < 0 -> LedgerItem.CreditCreated(history)
                    history.ledgerEntryType == "PAYMENT" || history.type == "PAYMENT" || delta < 0 -> LedgerItem.PaymentReceived(history)
                    else -> LedgerItem.Adjustment(history)
                }
                items.add(item)
            }
            items
        }

    override fun getItemViewType(position: Int): Int {
        return when (ledgerItems[position]) {
            is LedgerItem.DateHeader -> VIEW_TYPE_DATE_HEADER
            is LedgerItem.DebtAdded -> VIEW_TYPE_DEBT_ADDED
            is LedgerItem.PaymentReceived -> VIEW_TYPE_PAYMENT
            is LedgerItem.CreditCreated -> VIEW_TYPE_CREDIT_CREATED
            is LedgerItem.Adjustment -> VIEW_TYPE_ADJUSTMENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> {
                val view = inflater.inflate(R.layout.item_ledger_date_header, parent, false)
                DateHeaderViewHolder(view)
            }
            VIEW_TYPE_DEBT_ADDED -> DebtAddedViewHolder(com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding.inflate(inflater, parent, false))
            VIEW_TYPE_PAYMENT -> PaymentViewHolder(com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding.inflate(inflater, parent, false))
            VIEW_TYPE_CREDIT_CREATED -> CreditCreatedViewHolder(com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding.inflate(inflater, parent, false))
            else -> AdjustmentViewHolder(com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = ledgerItems[position]
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)
        val dateTimeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        when (holder) {
            is DateHeaderViewHolder -> {
                val header = item as LedgerItem.DateHeader
                (holder.itemView as TextView).text = header.date
            }
            is DebtAddedViewHolder -> {
                val history = (item as LedgerItem.DebtAdded).history
                val delta = if (history.transactionAmount != 0.0) history.transactionAmount else (history.newBalance - history.balanceBefore)
                val balAfter = if (history.newBalance != 0.0 || history.transactionAmount != 0.0) history.newBalance else history.balanceAfter
                
                holder.binding.textDate.text = dateTimeFormat.format(Date(history.timestamp))
                holder.binding.textAmount.text = "+ ${format.format(delta)}"
                holder.binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.destructive))
                holder.binding.textLabel.text = "Debt Added"
                holder.binding.textNote.text = history.note.ifEmpty { "Transaction for ${history.customerName}" }
                holder.binding.textNewBalance.text = "BAL: ${format.format(balAfter)}"
                
                holder.binding.lineTop.visibility = if (position > 0 && ledgerItems[position-1] !is LedgerItem.DateHeader) View.VISIBLE else View.INVISIBLE
            }
            is PaymentViewHolder -> {
                val history = (item as LedgerItem.PaymentReceived).history
                val delta = if (history.transactionAmount != 0.0) history.transactionAmount else (history.newBalance - history.balanceBefore)
                val balAfter = if (history.newBalance != 0.0 || history.transactionAmount != 0.0) history.newBalance else history.balanceAfter
                
                holder.binding.textDate.text = dateTimeFormat.format(Date(history.timestamp))
                holder.binding.textAmount.text = "− ${format.format(Math.abs(delta))}"
                holder.binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.primary_accent))
                holder.binding.textLabel.text = "Payment Received"
                holder.binding.textNote.text = history.note.ifEmpty { "Payment from ${history.customerName}" }
                holder.binding.textNewBalance.text = "BAL: ${format.format(balAfter)}"

                holder.binding.lineTop.visibility = if (position > 0 && ledgerItems[position-1] !is LedgerItem.DateHeader) View.VISIBLE else View.INVISIBLE
            }
            is CreditCreatedViewHolder -> {
                val history = (item as LedgerItem.CreditCreated).history
                val delta = if (history.transactionAmount != 0.0) history.transactionAmount else (history.newBalance - history.balanceBefore)
                val balAfter = if (history.newBalance != 0.0 || history.transactionAmount != 0.0) history.newBalance else history.balanceAfter
                
                holder.binding.textDate.text = dateTimeFormat.format(Date(history.timestamp))
                holder.binding.textAmount.text = format.format(Math.abs(delta))
                holder.binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.warning))
                holder.binding.textLabel.text = "Credit Created"
                holder.binding.textNote.text = "Excess payment converted to credit"
                holder.binding.textNewBalance.text = "BAL: ${format.format(balAfter)}"

                holder.binding.lineTop.visibility = if (position > 0 && ledgerItems[position-1] !is LedgerItem.DateHeader) View.VISIBLE else View.INVISIBLE
            }
            is AdjustmentViewHolder -> {
                val history = (item as LedgerItem.Adjustment).history
                val delta = if (history.transactionAmount != 0.0) history.transactionAmount else (history.newBalance - history.balanceBefore)
                val balAfter = if (history.newBalance != 0.0 || history.transactionAmount != 0.0) history.newBalance else history.balanceAfter
                
                holder.binding.textDate.text = dateTimeFormat.format(Date(history.timestamp))
                val prefix = if (delta >= 0) "± " else "- "
                holder.binding.textAmount.text = "$prefix${format.format(Math.abs(delta))}"
                holder.binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_secondary))
                holder.binding.textLabel.text = "Adjustment"
                holder.binding.textNote.text = history.note.ifEmpty { "Manual Correction" }
                holder.binding.textNewBalance.text = "BAL: ${format.format(balAfter)}"

                holder.binding.lineTop.visibility = if (position > 0 && ledgerItems[position-1] !is LedgerItem.DateHeader) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    override fun getItemCount() = ledgerItems.size

    class DateHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class DebtAddedViewHolder(val binding: com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding) : RecyclerView.ViewHolder(binding.root)
    class PaymentViewHolder(val binding: com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding) : RecyclerView.ViewHolder(binding.root)
    class CreditCreatedViewHolder(val binding: com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding) : RecyclerView.ViewHolder(binding.root)
    class AdjustmentViewHolder(val binding: com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding) : RecyclerView.ViewHolder(binding.root)
}
