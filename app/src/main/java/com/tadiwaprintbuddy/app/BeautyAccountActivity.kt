package com.tadiwaprintbuddy.app

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.BeautyTransaction
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityBeautyAccountBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BeautyAccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeautyAccountBinding
    private val viewModel: BeautyAccountViewModel by viewModels {
        BeautyAccountViewModelFactory(PrintRepository(AppDatabase.getDatabase(this).printDao()))
    }
    private lateinit var transactionAdapter: TransactionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeautyAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transactionAdapter = TransactionAdapter(this) { transaction ->
            showDeleteConfirmDialog(transaction)
        }

        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupFilters() {
        binding.chipGroupTimeFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val period = when (checkedId) {
                R.id.chipToday -> "Today"
                R.id.chipThisWeek -> "This Week"
                R.id.chipThisMonth -> "This Month"
                R.id.chipAllTime -> "All Time"
                else -> "Today"
            }
            viewModel.setPeriod(period)
        }
    }

    private fun showDeleteConfirmDialog(transaction: BeautyTransaction) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_note) + "?")
            .setMessage(R.string.upi_delete_confirm)
            .setPositiveButton(R.string.delete_note) { _, _ ->
                viewModel.deleteTransaction(transaction)
                Toast.makeText(this, R.string.note_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { 
            finish() 
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerViewTransactions.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewTransactions.adapter = transactionAdapter
    }

    private fun setupClickListeners() {
        binding.btnAddMoney.setOnClickListener { showAddMoneyDialog() }
        binding.btnReturnMoney.setOnClickListener { showReturnMoneyDialog() }
        binding.btnResetBalance.setOnClickListener { showResetConfirmDialog() }
    }

    private fun showResetConfirmDialog() {
        val currentBalance = viewModel.balance.value ?: 0.0
        if (currentBalance == 0.0) {
            Toast.makeText(this, "Balance is already zero", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Reset Totals?")
            .setMessage(R.string.upi_reset_confirm)
            .setPositiveButton("Reset") { _, _ ->
                viewModel.resetBalance()
                Toast.makeText(this, "Totals reset recorded", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun observeViewModel() {
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)

        viewModel.balance.observe(this) { balance ->
            val current = balance ?: 0.0
            
            binding.textCurrentBalance.text = format.format(kotlin.math.abs(current))
            binding.textCurrentBalance.setTextColor(Color.WHITE)
            
            if (current == 0.0) {
                binding.textCurrentBalance.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42f)
                binding.textCurrentBalance.alpha = 0.5f
            } else {
                binding.textCurrentBalance.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 48f)
                binding.textCurrentBalance.alpha = 1.0f
            }
        }

        viewModel.transactions.observe(this) { transactions ->
            transactionAdapter.submitList(transactions ?: emptyList())
        }

        viewModel.periodSummary.observe(this) { summary ->
            binding.textSummaryPeriodName.text = viewModel.filterPeriod.value?.uppercase()
            binding.textSummaryReceived.text = format.format(kotlin.math.abs(summary.received))
            binding.textSummaryReturned.text = format.format(kotlin.math.abs(summary.returned))
            
            val netFlow = summary.netFlow
            binding.textSummaryNetFlow.text = format.format(kotlin.math.abs(netFlow))
            binding.textSummaryNetFlow.setTextColor(if (netFlow >= 0) Color.parseColor("#00C853") else Color.parseColor("#FF5252"))
            
            binding.textSummaryCount.text = summary.count.toString()
            binding.textTransactionCount.text = getString(R.string.upi_transactions_count, summary.count)
        }
    }

    private fun showAddMoneyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = getString(R.string.upi_amount_hint)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_upi_money)
            .setMessage(R.string.upi_add_message)
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    viewModel.addMoney(amount, "Manual Entry")
                } else {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReturnMoneyDialog() {
        val currentBalance = viewModel.balance.value ?: 0.0
        if (currentBalance <= 0) {
            Toast.makeText(this, "No balance to return", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = getString(R.string.upi_withdraw_hint)

        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)
        val balanceStr = format.format(currentBalance)

        AlertDialog.Builder(this)
            .setTitle(R.string.return_upi_money)
            .setMessage(getString(R.string.upi_withdraw_message, balanceStr))
            .setView(input)
            .setPositiveButton("Record") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    if (amount > currentBalance) {
                        Toast.makeText(this, R.string.upi_insufficient_balance, Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.returnMoney(amount, "Manual Return")
                    }
                } else {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private class TransactionAdapter(
        private val context: android.content.Context,
        private val onLongPress: (BeautyTransaction) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var originalTransactions = listOf<BeautyTransaction>()
        private var items = listOf<Any>()
        private val expandedHeaders = mutableSetOf<String>()

        private data class HeaderData(val title: String, val count: Int)

        fun submitList(newItems: List<BeautyTransaction>) {
            originalTransactions = newItems
            updateList()
        }

        private fun updateList() {
            val groupedItems = mutableListOf<Any>()
            val groups = originalTransactions.sortedByDescending { it.timestamp }.groupBy { transaction ->
                originalTransactions.count { it.type == "RESET" && it.timestamp > transaction.timestamp }
            }

            groups.keys.sorted().forEach { key ->
                val groupTransactions = groups[key]!!
                if (key > 0) {
                    val resetTime = originalTransactions.filter { it.type == "RESET" }
                        .sortedByDescending { it.timestamp }[key - 1].timestamp
                    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    val headerTitle = context.getString(R.string.upi_history_before, dateFormat.format(Date(resetTime)))
                    
                    groupedItems.add(HeaderData(headerTitle, groupTransactions.size))
                    if (expandedHeaders.contains(headerTitle)) {
                        groupedItems.addAll(groupTransactions)
                    }
                } else {
                    groupedItems.addAll(groupTransactions)
                }
            }

            items = groupedItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is HeaderData) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_beauty_history_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_beauty_transaction, parent, false)
                TransactionViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder && item is HeaderData) {
                holder.textHeaderTitle.text = item.title
                holder.textItemCount.text = if (item.count == 1) "1 item" else "${item.count} items"
                val isExpanded = expandedHeaders.contains(item.title)
                holder.imageChevron.rotation = if (isExpanded) 180f else 0f
                
                holder.itemView.setOnClickListener {
                    if (isExpanded) expandedHeaders.remove(item.title)
                    else expandedHeaders.add(item.title)
                    updateList()
                }
            } else if (holder is TransactionViewHolder && item is BeautyTransaction) {
                val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
                val format = NumberFormat.getCurrencyInstance(locale)
                val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                
                holder.textNote.text = (if (item.type == "RESET") "TOTALS RESET" else item.note ?: "MANUAL ENTRY").uppercase(Locale.getDefault())
                holder.textTimestamp.text = dateFormat.format(Date(item.timestamp)).uppercase(Locale.getDefault())

                when (item.type) {
                    "ADD" -> {
                        holder.textAmount.text = format.format(kotlin.math.abs(item.amount))
                        holder.textAmount.setTextColor(0xFF00C853.toInt()) // primary_accent
                        holder.imageTransactionType.setImageResource(R.drawable.ic_check)
                        holder.imageTransactionType.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        holder.cardIcon.setCardBackgroundColor(0xFF00C853.toInt())
                    }
                    "RETURN" -> {
                        holder.textAmount.text = format.format(kotlin.math.abs(item.amount))
                        holder.textAmount.setTextColor(0xFFFF5252.toInt()) // destructive
                        holder.imageTransactionType.setImageResource(R.drawable.ic_history)
                        holder.imageTransactionType.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        holder.cardIcon.setCardBackgroundColor(0xFFFF5252.toInt())
                    }
                    "RESET" -> {
                        holder.textAmount.text = format.format(kotlin.math.abs(item.amount))
                        holder.textAmount.setTextColor(0xFFFFB300.toInt()) // warning
                        holder.imageTransactionType.setImageResource(android.R.drawable.ic_menu_delete)
                        holder.imageTransactionType.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        holder.cardIcon.setCardBackgroundColor(0xFFFFB300.toInt())
                    }
                }

                // Balance Clarity
                if (item.newBalance != 0.0 || item.previousBalance != 0.0) {
                    holder.layoutClarity.visibility = View.VISIBLE
                    val flowText = "Balance: ${format.format(kotlin.math.abs(item.previousBalance))} → ${format.format(kotlin.math.abs(item.newBalance))}"
                    holder.textBalanceFlow.text = flowText
                } else {
                    holder.layoutClarity.visibility = View.GONE
                }

                holder.itemView.setOnLongClickListener {
                    onLongPress(item)
                    true
                }
            }
        }

        override fun getItemCount() = items.size

        class TransactionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textNote: TextView = view.findViewById(R.id.textNote)
            val textTimestamp: TextView = view.findViewById(R.id.textTimestamp)
            val textAmount: TextView = view.findViewById(R.id.textAmount)
            val imageTransactionType: android.widget.ImageView = view.findViewById(R.id.imageTransactionType)
            val cardIcon: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.cardIcon)
            val layoutClarity: View = view.findViewById(R.id.layoutClarity)
            val textBalanceFlow: TextView = view.findViewById(R.id.textBalanceFlow)
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textHeaderTitle: TextView = view.findViewById(R.id.textHeaderTitle)
            val textItemCount: TextView = view.findViewById(R.id.textItemCount)
            val imageChevron: android.widget.ImageView = view.findViewById(R.id.imageChevron)
        }
    }
}
