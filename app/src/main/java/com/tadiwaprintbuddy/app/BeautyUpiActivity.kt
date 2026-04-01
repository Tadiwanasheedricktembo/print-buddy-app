package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.BeautyTransaction
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityBeautyUpiBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BeautyUpiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeautyUpiBinding
    private lateinit var repository: PrintRepository
    private val ledgerAdapter = LedgerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeautyUpiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        refreshData()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        binding.recyclerViewLedger.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewLedger.adapter = ledgerAdapter
    }

    private fun setupClickListeners() {
        binding.btnRecordReturn.setOnClickListener { showRecordReturnDialog() }
        binding.btnManualCredit.setOnClickListener { showManualCreditDialog() }
    }

    private fun showRecordReturnDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Amount Beauty Rani returned to you"

        AlertDialog.Builder(this)
            .setTitle("Record Money Returned")
            .setMessage("Enter the amount Beauty Rani gave back to you:")
            .setView(input)
            .setPositiveButton("Record") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    lifecycleScope.launch {
                        repository.recordMoneyReturnedFromExternal(amount)
                        Toast.makeText(this@BeautyUpiActivity, "Recorded successfully", Toast.LENGTH_SHORT).show()
                        refreshData()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualCreditDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Amount paid to Beauty UPI"

        AlertDialog.Builder(this)
            .setTitle("Manual Credit")
            .setMessage("Enter amount paid directly to Beauty's account:")
            .setView(input)
            .setPositiveButton("Add Credit") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    lifecycleScope.launch {
                        repository.addManualExternalCredit(amount)
                        Toast.makeText(this@BeautyUpiActivity, "Credit added", Toast.LENGTH_SHORT).show()
                        refreshData()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshData() {
        lifecycleScope.launch {
            val balance = repository.getExternalBalance()
            val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            binding.textCurrentBalance.text = format.format(balance ?: 0.0)

            val history = repository.getAllExternalLedgerEntries()
            ledgerAdapter.submitList(history)
        }
    }

    private class LedgerAdapter : RecyclerView.Adapter<LedgerAdapter.ViewHolder>() {
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
            holder.text1.text = "${typePrefix}${format.format(item.amount)} - ${item.note ?: "Transaction"}"
            holder.text2.text = dateFormat.format(Date(item.timestamp))

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
