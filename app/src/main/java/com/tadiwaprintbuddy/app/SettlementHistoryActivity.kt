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
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.data.SettlementHistory
import com.tadiwaprintbuddy.app.data.BusinessEvent
import com.tadiwaprintbuddy.app.databinding.ActivitySettlementHistoryBinding
import com.tadiwaprintbuddy.app.databinding.ItemSettlementBinding
import com.tadiwaprintbuddy.app.databinding.ItemSettlementGroupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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
    private val viewModel: SettlementHistoryViewModel by viewModels {
        SettlementHistoryViewModelFactory(PrintRepository(AppDatabase.getDatabase(this).printDao()))
    }
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

        adapter = GroupedSettlementAdapter(emptyList()) { customerId ->
            viewModel.toggleExpansion(customerId)
        }
        binding.recyclerSettlements.layoutManager = LinearLayoutManager(this)
        binding.recyclerSettlements.adapter = adapter

        setupSearch()
        setupSorting()
        observeViewModel()
        
        val targetCustomerId = intent.getLongExtra("EXTRA_CUSTOMER_ID", -1L)
        viewModel.setInitialExpansion(targetCustomerId)
        viewModel.loadSettlements()
    }

    private fun setupSorting() {
        binding.chipGroupSort.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipSortNewest -> viewModel.setSortOrder(TransactionSortOrder.NEWEST_FIRST)
                R.id.chipSortOldest -> viewModel.setSortOrder(TransactionSortOrder.OLDEST_FIRST)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.groupedSettlements.collectLatest { groups ->
                if (groups.isEmpty()) {
                    binding.textEmpty.visibility = View.VISIBLE
                    binding.recyclerSettlements.visibility = View.GONE
                } else {
                    binding.textEmpty.visibility = View.GONE
                    binding.recyclerSettlements.visibility = View.VISIBLE
                    adapter.updateGroups(groups)
                }
            }
        }
        
        lifecycleScope.launch {
            repository.getAllSettlements().let {
                allSettlements = it
            }
        }
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
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
                    viewModel.loadSettlements()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettlementHistoryActivity, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
            // Unused
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
}

class GroupedSettlementAdapter(
    private var groups: List<GroupedSettlement>,
    private val onHeaderClick: (Long) -> Unit
) : RecyclerView.Adapter<GroupedSettlementAdapter.GroupViewHolder>() {

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
        holder.binding.textTransactionCount.text = "${group.events.size} activities"
        
        val balance = group.totalOwed
        if (balance > 0) {
            holder.binding.textTotalOwed.text = format.format(balance)
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_error))
            holder.binding.textBalanceLabel.text = "AMOUNT DUE"
            holder.binding.textBalanceLabel.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_error))
        } else if (balance < 0) {
            holder.binding.textTotalOwed.text = format.format(Math.abs(balance))
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_primary))
            holder.binding.textBalanceLabel.text = "CUSTOMER CREDIT"
            holder.binding.textBalanceLabel.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_primary))
        } else {
            holder.binding.textTotalOwed.text = "₹ 0.00"
            holder.binding.textTotalOwed.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_success))
            holder.binding.textBalanceLabel.text = "NO OUTSTANDING BALANCE"
            holder.binding.textBalanceLabel.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_success))
        }

        // Business Oriented Summary Metrics
        val ordersTotal = group.rawTransactions.filter { it.ledgerEntryType == "ORDER_POST" }.sumOf { it.transactionAmount }
        val cashReceived = group.rawTransactions.filter { it.ledgerEntryType in listOf("PAYMENT", "CREDIT") }.sumOf { it.amountPaid }
        val outstandingDebt = if (balance > 0) balance else 0.0
        val customerCredit = if (balance < 0) -balance else 0.0

        holder.binding.textOrdersTotal.text = format.format(ordersTotal)
        holder.binding.textCashReceived.text = format.format(cashReceived)
        holder.binding.textOutstandingDebt.text = format.format(outstandingDebt)
        holder.binding.textCustomerCredit.text = format.format(customerCredit)

        holder.binding.recyclerTransactions.visibility = if (group.isExpanded) View.VISIBLE else View.GONE
        holder.binding.layoutSummary.visibility = if (group.isExpanded) View.VISIBLE else View.GONE
        holder.binding.imageExpand.rotation = if (group.isExpanded) 180f else 0f

        holder.binding.layoutHeader.setOnClickListener {
            onHeaderClick(group.customerId)
        }

        if (group.isExpanded) {
            holder.binding.recyclerTransactions.layoutManager = LinearLayoutManager(holder.itemView.context)
            holder.binding.recyclerTransactions.adapter = BusinessEventAdapter(group.events)
        }
    }

    override fun getItemCount() = groups.size

    fun updateGroups(newGroups: List<GroupedSettlement>) {
        groups = newGroups
        notifyDataSetChanged()
    }
}

class BusinessEventAdapter(private val events: List<BusinessEvent>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_DATE_HEADER = 0
        private const val VIEW_TYPE_EVENT = 1
    }

    private val timelineItems: List<Any> = events
        .let { sortedEvents ->
            val items = mutableListOf<Any>()
            var lastDate = ""
            val headerFormat = SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault())

            sortedEvents.forEach { event ->
                val dateStr = headerFormat.format(Date(event.timestamp))
                if (dateStr != lastDate) {
                    items.add(dateStr)
                    lastDate = dateStr
                }
                items.add(event)
            }
            items
        }

    private var expandedPositions = mutableSetOf<Int>()

    override fun getItemViewType(position: Int): Int {
        return if (timelineItems[position] is String) VIEW_TYPE_DATE_HEADER else VIEW_TYPE_EVENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_DATE_HEADER) {
            val view = inflater.inflate(R.layout.item_ledger_date_header, parent, false)
            DateHeaderViewHolder(view)
        } else {
            EventViewHolder(com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)
        val dateTimeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        if (holder is DateHeaderViewHolder) {
            (holder.itemView as TextView).text = timelineItems[position] as String
            return
        }

        val eventHolder = holder as EventViewHolder
        val event = timelineItems[position] as BusinessEvent
        val binding = eventHolder.binding

        val isExpanded = expandedPositions.contains(position)
        binding.layoutEventDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
        
        binding.textDate.text = dateTimeFormat.format(Date(event.timestamp))
        
        val balAfter = event.balanceAfter
        binding.textNewBalance.text = when {
            balAfter < 0 -> "Customer Credit: ${format.format(Math.abs(balAfter))}"
            balAfter > 0 -> "Amount Due: ${format.format(balAfter)}"
            else -> "Balance Cleared"
        }
        binding.lineTop.visibility = if (position > 0 && timelineItems[position-1] !is String) View.VISIBLE else View.INVISIBLE

        when (event) {
            is BusinessEvent.PaymentReceived -> {
                binding.textLabel.text = "Payment Received"
                binding.textNote.text = "Paid ${format.format(event.totalPaid)}"
                binding.textAmount.text = "− ${format.format(event.totalPaid)}"
                binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_success))
                
                binding.textEventBreakdown.text = buildString {
                    if (event.debtCleared > 0) {
                        append("✓ Applied to outstanding debt .. ${format.format(event.debtCleared)}\n")
                    }
                    if (event.creditCreated > 0) {
                        append("✓ Added to customer credit ...... ${format.format(event.creditCreated)}\n")
                    }
                    
                    append("\nStatus: ")
                    if (event.balanceAfter < 0) append("Customer Credit ${format.format(Math.abs(event.balanceAfter))}")
                    else if (event.balanceAfter > 0) append("Remaining Debt ${format.format(event.balanceAfter)}")
                    else append("Account Cleared")
                }
            }
            is BusinessEvent.NewOrder -> {
                binding.textLabel.text = if (event.outstanding > 0) "Partial Payment" else "New Order"
                binding.textNote.text = "Order Total: ${format.format(event.orderTotal)}"
                binding.textAmount.text = "+ ${format.format(event.orderTotal)}"
                binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_error))

                binding.textEventBreakdown.text = buildString {
                    if (event.creditUsed > 0) append("• Used Credit ............ ${format.format(event.creditUsed)}\n")
                    if (event.cashPaid > 0) append("• Cash Paid .............. ${format.format(event.cashPaid)}\n")
                    if (event.outstanding > 0) append("• Outstanding ............ ${format.format(event.outstanding)}")
                    else append("• Outstanding ............ Cleared")
                }
            }
            is BusinessEvent.DebtAdded -> {
                binding.textLabel.text = "Debt Added"
                binding.textNote.text = event.note
                binding.textAmount.text = "+ ${format.format(event.amount)}"
                binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_error))
                binding.textEventBreakdown.text = "Customer purchased on credit."
            }
            is BusinessEvent.Adjustment -> {
                binding.textLabel.text = "Adjustment"
                binding.textNote.text = event.note
                val prefix = if (event.amount >= 0) "+ " else "− "
                binding.textAmount.text = "$prefix${format.format(Math.abs(event.amount))}"
                binding.textAmount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_secondary))
                binding.textEventBreakdown.text = "Manual account adjustment."
            }
        }

        binding.textEventAudit.text = buildString {
            append("Ledger IDs: ")
            append(event.details.joinToString(", ") { "#${it.id}" })
            val orderRef = event.details.firstNotNullOfOrNull { it.originId }
            if (orderRef != null) append(" | Order Ref: #$orderRef")
        }

        holder.itemView.setOnClickListener {
            if (expandedPositions.contains(position)) {
                expandedPositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = timelineItems.size

    class DateHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class EventViewHolder(val binding: com.tadiwaprintbuddy.app.databinding.ItemLedgerEntryBinding) : RecyclerView.ViewHolder(binding.root)
}
