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

    private class TransactionAdapter : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {
        private var items = listOf<BeautyTransaction>()

        fun submitList(newItems: List<BeautyTransaction>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            
            val typePrefix = if (item.type == "ADD") "+" else "-"
            holder.text1.text = "${typePrefix}${format.format(item.amount)} (${if(item.type == "ADD") "Added" else "Returned"})"
            holder.text2.text = "${dateFormat.format(Date(item.timestamp))} ${item.note?.let { "- $it" } ?: ""}"

            val color = if (item.type == "ADD") 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            holder.text1.setTextColor(color)
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text1: TextView = view.findViewById(android.R.id.text1)
            val text2: TextView = view.findViewById(android.R.id.text2)
        }
    }
}
