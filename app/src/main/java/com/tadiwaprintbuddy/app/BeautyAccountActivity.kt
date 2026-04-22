package com.tadiwaprintbuddy.app

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
    private val transactionAdapter = TransactionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeautyAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { 
            finish() 
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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
            .setMessage("This will bring your balance to ₹0 by recording a reset entry. History will NOT be deleted.")
            .setPositiveButton("Reset") { _, _ ->
                viewModel.resetBalance()
                Toast.makeText(this, "Totals reset recorded", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.balance.observe(this) { balance ->
            val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            binding.textCurrentBalance.text = format.format(balance ?: 0.0)
        }

        viewModel.transactions.observe(this) { transactions ->
            transactionAdapter.submitList(transactions ?: emptyList())
        }
    }

    private fun showAddMoneyDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Amount paid to UPI"

        AlertDialog.Builder(this)
            .setTitle("Add UPI Money")
            .setMessage("Enter amount received in Beauty's account:")
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
        input.hint = "Amount returned by Beauty"

        AlertDialog.Builder(this)
            .setTitle("Return Money")
            .setMessage("Enter the amount Beauty Rani gave back (Max: $currentBalance):")
            .setView(input)
            .setPositiveButton("Record") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    if (amount > currentBalance) {
                        Toast.makeText(this, "Cannot return more than balance", Toast.LENGTH_SHORT).show()
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

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private class TransactionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var originalTransactions = listOf<BeautyTransaction>()
        private var items = listOf<Any>()
        private val expandedHeaders = mutableSetOf<String>()

        private companion object {
            const val TYPE_TRANSACTION = 0
            const val TYPE_HEADER = 1
        }

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
                    val headerTitle = "History before ${dateFormat.format(Date(resetTime))}"
                    
                    groupedItems.add(headerTitle)
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
            return if (items[position] is String) TYPE_HEADER else TYPE_TRANSACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_beauty_history_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_beauty_transaction, parent, false)
                TransactionViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder && item is String) {
                holder.textHeaderTitle.text = item
                val isExpanded = expandedHeaders.contains(item)
                holder.imageChevron.rotation = if (isExpanded) 180f else 0f
                
                holder.itemView.setOnClickListener {
                    if (isExpanded) expandedHeaders.remove(item)
                    else expandedHeaders.add(item)
                    updateList()
                }
            } else if (holder is TransactionViewHolder && item is BeautyTransaction) {
                val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
                val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                
                holder.textNote.text = if (item.type == "RESET") "Totals Reset" else item.note ?: "Manual Entry"
                holder.textTimestamp.text = dateFormat.format(Date(item.timestamp))

                when (item.type) {
                    "ADD" -> {
                        holder.textAmount.text = "+ ${format.format(item.amount)}"
                        holder.textAmount.setTextColor(0xFF2E7D32.toInt())
                        holder.imageTransactionType.setImageResource(android.R.drawable.ic_input_add)
                        holder.imageTransactionType.imageTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
                        holder.cardIcon.setCardBackgroundColor(0x1A2E7D32)
                    }
                    "RETURN" -> {
                        holder.textAmount.text = "- ${format.format(item.amount)}"
                        holder.textAmount.setTextColor(0xFFC62828.toInt())
                        holder.imageTransactionType.setImageResource(android.R.drawable.ic_menu_revert)
                        holder.imageTransactionType.imageTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
                        holder.cardIcon.setCardBackgroundColor(0x1AC62828)
                    }
                    "RESET" -> {
                        holder.textAmount.text = format.format(item.amount)
                        holder.textAmount.setTextColor(0xFFFF9800.toInt())
                        holder.imageTransactionType.setImageResource(android.R.drawable.ic_menu_delete)
                        holder.imageTransactionType.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
                        holder.cardIcon.setCardBackgroundColor(0x1AFF9800)
                    }
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
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textHeaderTitle: TextView = view.findViewById(R.id.textHeaderTitle)
            val textItemCount: TextView = view.findViewById(R.id.textItemCount)
            val imageChevron: android.widget.ImageView = view.findViewById(R.id.imageChevron)
        }
    }
}
