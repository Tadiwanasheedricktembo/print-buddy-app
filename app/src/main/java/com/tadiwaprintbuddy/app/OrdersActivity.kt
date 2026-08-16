package com.tadiwaprintbuddy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityOrdersBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OrdersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrdersBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: OrdersAdapter
    private val currencyFormat: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())
    }

    private var currentFilterType = FilterType.ALL
    private var currentPaymentFilter = PaymentFilter.ALL
    private var currentSearchQuery = ""
    private var currentStart: Long? = null
    private var currentEnd: Long? = null
    private var allOrders: List<Order> = emptyList()
    private var currentOrders: List<Order> = emptyList()

    private enum class PaymentFilter {
        ALL, CASH, UPI, CREDIT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setupSearch()

        val initialFilter = intent.getStringExtra("filter")
        if (initialFilter == "MONTH") {
            binding.chipMonth.isChecked = true
            loadOrders(FilterType.MONTH)
        } else {
            loadOrders(FilterType.ALL)
        }

        binding.btnClearFilters.setOnClickListener {
            binding.chipAll.isChecked = true
            binding.chipPaymentAll.isChecked = true
            binding.editSearchOrders.text.clear()
            currentPaymentFilter = PaymentFilter.ALL
            loadOrders(FilterType.ALL)
        }
    }

    private fun setupSearch() {
        binding.editSearchOrders.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString() ?: ""
                binding.btnClearSearch.visibility = if (currentSearchQuery.isEmpty()) View.GONE else View.VISIBLE
                applyAllFilters()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.editSearchOrders.text.clear()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_orders, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_filtered -> {
                showBulkDeleteSafetyDialog(isAll = false)
                true
            }
            R.id.action_clear_all -> {
                showBulkDeleteSafetyDialog(isAll = true)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = OrdersAdapter(emptyList()) { order ->
            showDeleteConfirmation(order)
        }
        binding.recyclerOrders.layoutManager = LinearLayoutManager(this)
        binding.recyclerOrders.adapter = adapter
    }

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedStateChangeListener { group, _ ->
            when (group.checkedChipId) {
                R.id.chipAll -> loadOrders(FilterType.ALL)
                R.id.chipToday -> loadOrders(FilterType.TODAY)
                R.id.chipWeek -> loadOrders(FilterType.WEEK)
                R.id.chipMonth -> loadOrders(FilterType.MONTH)
                R.id.chipCustom -> showCustomDatePicker()
            }
        }

        binding.chipGroupPaymentFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipPaymentAll
            currentPaymentFilter = when (checkedId) {
                R.id.chipPaymentAll -> PaymentFilter.ALL
                R.id.chipPaymentCash -> PaymentFilter.CASH
                R.id.chipPaymentUpi -> PaymentFilter.UPI
                R.id.chipPaymentCredit -> PaymentFilter.CREDIT
                else -> PaymentFilter.ALL
            }
            applyAllFilters()
        }
    }

    private enum class FilterType {
        ALL, TODAY, WEEK, MONTH, LAST_MONTH, LAST_3_MONTHS, YEAR, CUSTOM
    }

    private fun loadOrders(filter: FilterType, customStart: Long? = null, customEnd: Long? = null) {
        currentFilterType = filter
        currentStart = customStart
        currentEnd = customEnd

        lifecycleScope.launch {
            val orders = when (filter) {
                FilterType.ALL -> {
                    updateSummary("All Time")
                    repository.getAllOrders()
                }
                FilterType.CUSTOM -> {
                    if (customStart != null && customEnd != null) {
                        updateSummary("Custom Range")
                        repository.getOrdersBetween(customStart, customEnd)
                    } else {
                        repository.getAllOrders()
                    }
                }
                else -> {
                    val range = getDateRange(filter)
                    currentStart = range.first
                    currentEnd = range.second
                    updateSummary(getFilterName(filter))
                    repository.getOrdersBetween(range.first, range.second)
                }
            }
            allOrders = orders
            applyAllFilters()
        }
    }

    private fun applyAllFilters() {
        var filtered = allOrders
        
        // 1. Apply Payment Filter with explicit semantics to handle new mixed/credit states
        if (currentPaymentFilter != PaymentFilter.ALL) {
            filtered = filtered.filter { order ->
                when (currentPaymentFilter) {
                    PaymentFilter.CASH -> {
                        // Includes pure cash, mixed payments involving cash, 
                        // and legacy mixed transactions (usually cash-based)
                        order.paymentMethod == "CASH" || 
                        order.paymentMethod == "CASH_MIXED" || 
                        order.paymentMethod == "MIXED"
                    }
                    PaymentFilter.UPI -> {
                        // Includes pure digital and mixed payments involving UPI
                        order.paymentMethod == "UPI" || 
                        order.paymentMethod == "UPI_MIXED"
                    }
                    PaymentFilter.CREDIT -> {
                        // Includes pure debt, overpayment credits, 
                        // and any order with an outstanding balance
                        order.paymentMethod == "NONE" || 
                        order.paymentMethod == "CREDIT" || 
                        order.paymentStatus != "PAID"
                    }
                    else -> true
                }
            }
        }

        // 2. Apply Search Filter (Customer name or Order ID)
        if (currentSearchQuery.isNotEmpty()) {
            filtered = filtered.filter { 
                it.customerName.contains(currentSearchQuery, ignoreCase = true) ||
                it.id.toString().contains(currentSearchQuery)
            }
        }

        // 3. Update UI (Ensures List, Count, and Total are derived from the exact same dataset)
        updateUI(filtered)
    }

    private fun updateUI(orders: List<Order>) {
        currentOrders = orders
        if (orders.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.recyclerOrders.visibility = View.GONE
            binding.textOrderCount.text = getString(R.string.zero_orders)
            binding.textTotalRevenue.text = currencyFormat.format(0.0).replace(" ", "")
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.recyclerOrders.visibility = View.VISIBLE
            
            binding.recyclerOrders.post {
                adapter.updateOrders(orders)
            }
            
            // Total represents authoritative Value of Work (Total Amount of orders)
            // Filter: Only include active or legacy (null status) orders in the financial total
            val activeOrders = orders.filter { 
                it.orderStatus == "ACTIVE" || it.orderStatus.isNullOrEmpty() 
            }
            
            val totalWorkValue = activeOrders.sumOf { it.totalAmount }
            
            binding.textOrderCount.text = getString(R.string.order_count_format, orders.size)
            binding.textTotalRevenue.text = currencyFormat.format(totalWorkValue).replace(" ", "")
        }
    }

    private fun updateSummary(text: String) {
        binding.textFilterLabel.text = text
    }

    private fun getFilterName(filter: FilterType): String = when (filter) {
        FilterType.TODAY -> "Today"
        FilterType.WEEK -> "This Week"
        FilterType.MONTH -> "This Month"
        else -> "All Time"
    }

    private fun getDateRange(filter: FilterType): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        val end = calendar.timeInMillis
        
        when (filter) {
            FilterType.TODAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
            }
            FilterType.WEEK -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
            FilterType.MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
            FilterType.LAST_MONTH -> {
                calendar.add(Calendar.MONTH, -1)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                val start = calendar.timeInMillis
                calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                return Pair(start, calendar.timeInMillis)
            }
            FilterType.LAST_3_MONTHS -> {
                calendar.add(Calendar.MONTH, -3)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
            }
            FilterType.YEAR -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
            }
            else -> {}
        }
        
        return Pair(calendar.timeInMillis, end)
    }

    private fun showCustomDatePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
        builder.setTitleText("Select Date Range")
        val picker = builder.build()
        
        picker.addOnPositiveButtonClickListener { range ->
            val start = range.first
            val end = range.second
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = end
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            
            loadOrders(FilterType.CUSTOM, start, calendar.timeInMillis)
        }
        
        picker.addOnCancelListener {
             binding.chipAll.isChecked = true
        }
        
        picker.addOnNegativeButtonClickListener {
             binding.chipAll.isChecked = true
        }

        picker.show(supportFragmentManager, "DATE_PICKER")
    }

    private fun showDeleteConfirmation(order: Order) {
        AlertDialog.Builder(this)
            .setTitle("Delete Order")
            .setMessage("Are you sure you want to delete Order #${order.id} for ${order.customerName}? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteOrder(order.id)
                    loadOrders(currentFilterType, currentStart, currentEnd)
                    Toast.makeText(this@OrdersActivity, "Order deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBulkDeleteSafetyDialog(isAll: Boolean) {
        val title = if (isAll) "Clear All History" else "Clear Filtered History"
        val message = "You are about to delete business records. It is highly recommended to export a backup before deletion. This action cannot be undone."
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Export & Delete") { _, _ ->
                handleExportAndDelete(isAll)
            }
            .setNeutralButton("Delete Without Export") { _, _ ->
                if (isAll) performClearAll() else performClearFiltered()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleExportAndDelete(isAll: Boolean) {
        lifecycleScope.launch {
            val fileName = getExportFileName(isAll)
            val success = exportOrdersToCsv(currentOrders, fileName)
            if (success) {
                if (isAll) performClearAll() else performClearFiltered()
            } else {
                Toast.makeText(this@OrdersActivity, "Export failed. Deletion aborted.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getExportFileName(isAll: Boolean): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return when {
            isAll -> "orders_all_$dateStr.csv"
            currentFilterType == FilterType.CUSTOM -> {
                val startStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(currentStart ?: 0))
                val endStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(currentEnd ?: 0))
                "orders_custom_${startStr}_to_$endStr.csv"
            }
            else -> "orders_${getFilterName(currentFilterType).lowercase().replace(" ", "_")}_$dateStr.csv"
        }
    }

    private suspend fun exportOrdersToCsv(orders: List<Order>, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val file = File(exportDir, fileName)
            val writer = FileWriter(file)

            // CSV Header
            writer.append("Order ID,Date,Customer Name,Total Amount,Paid Amount,Payment Method,Status\n")

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            for (order in orders) {
                val status = if (order.paidAmount >= order.totalAmount) "PAID" else "PENDING"
                writer.append("${order.id},")
                writer.append("${dateFormat.format(Date(order.date))},")
                writer.append("\"${order.customerName.replace("\"", "\"\"")}\",")
                writer.append("${order.totalAmount},")
                writer.append("${order.paidAmount},")
                writer.append("${order.paymentMethod},")
                writer.append("$status\n")
            }
            writer.flush()
            writer.close()

            // Share/Open File
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(this@OrdersActivity, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/csv"
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "Export Order History"))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun performClearFiltered() {
        lifecycleScope.launch {
            val start = currentStart
            val end = currentEnd
            if (start != null && end != null) {
                repository.deleteOrdersBetween(start, end)
                loadOrders(currentFilterType, currentStart, currentEnd)
                Toast.makeText(this@OrdersActivity, "Filtered history cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performClearAll() {
        lifecycleScope.launch {
            repository.deleteAllOrders()
            loadOrders(FilterType.ALL)
            Toast.makeText(this@OrdersActivity, "All history cleared", Toast.LENGTH_SHORT).show()
        }
    }
}
