package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.BeautyTransaction
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityBeautyUpiBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BeautyUpiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeautyUpiBinding
    private val viewModel: BeautyAccountViewModel by viewModels {
        BeautyAccountViewModelFactory(PrintRepository(AppDatabase.getDatabase(this).printDao()))
    }
    private val ledgerAdapter = LedgerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeautyUpiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        binding.recyclerViewLedger.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewLedger.adapter = ledgerAdapter
    }

    private fun observeViewModel() {
        viewModel.balance.observe(this) { balance ->
            val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
            val format = NumberFormat.getCurrencyInstance(locale)
            binding.textCurrentBalance.text = format.format(balance ?: 0.0)
        }

        viewModel.transactions.observe(this) { history ->
            ledgerAdapter.submitList(history)
        }
    }

    private fun setupClickListeners() {
        binding.btnRecordReturn.setOnClickListener { showRecordReturnDialog() }
        binding.btnManualCredit.setOnClickListener { showManualCreditDialog() }
    }

    private fun showRecordReturnDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = getString(R.string.upi_withdraw_amount_hint)

        AlertDialog.Builder(this)
            .setTitle(R.string.record_money_returned)
            .setMessage(R.string.upi_withdraw_full_message)
            .setView(input)
            .setPositiveButton("Record") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    viewModel.returnMoney(amount, getString(R.string.upi_manual_return_note))
                    Toast.makeText(this, "Recorded successfully", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualCreditDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = getString(R.string.upi_paid_to_hint)

        AlertDialog.Builder(this)
            .setTitle(R.string.manual_credit)
            .setMessage(R.string.upi_paid_to_message)
            .setView(input)
            .setPositiveButton("Add Credit") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0) {
                    viewModel.addMoney(amount, "Manual Entry")
                    Toast.makeText(this, "Credit added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class LedgerAdapter : RecyclerView.Adapter<LedgerAdapter.ViewHolder>() {
        private var items = listOf<BeautyTransaction>()

        fun submitList(newItems: List<BeautyTransaction>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ledger_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
            val format = NumberFormat.getCurrencyInstance(locale)
            val dateFormat = SimpleDateFormat("dd MMM, yyyy • hh:mm a", Locale.getDefault())
            
            val isAdd = item.type == "ADD" || item.type == "RESET"
            val typePrefix = if (isAdd) "+" else "-"
            
            holder.textAmount.text = "${typePrefix} ${format.format(item.amount)}"
            holder.textNote.text = item.note ?: (if (isAdd) "Credit Entry" else "Withdrawal")
            holder.textDate.text = dateFormat.format(Date(item.timestamp))
            holder.textNewBalance.text = format.format(item.newBalance)
            
            holder.textLabel.text = when(item.type) {
                "ADD" -> "CREDIT RECEIVED"
                "RETURN" -> "WITHDRAWAL"
                "RESET" -> "BALANCE RESET"
                else -> "TRANSACTION"
            }

            val color = if (isAdd) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            holder.textAmount.setTextColor(color)
            holder.textLabel.setTextColor(color)
            holder.imageIcon.setColorFilter(color)
            holder.circleIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            
            // Timeline line visibility
            holder.lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
            holder.lineBottom.visibility = if (position == itemCount - 1) View.INVISIBLE else View.VISIBLE
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textLabel: TextView = view.findViewById(R.id.textLabel)
            val textDate: TextView = view.findViewById(R.id.textDate)
            val textAmount: TextView = view.findViewById(R.id.textAmount)
            val textNote: TextView = view.findViewById(R.id.textNote)
            val textNewBalance: TextView = view.findViewById(R.id.textNewBalance)
            val imageIcon: ImageView = view.findViewById(R.id.imageIcon)
            val circleIcon: View = view.findViewById(R.id.circleIcon)
            val lineTop: View = view.findViewById(R.id.lineTop)
            val lineBottom: View = view.findViewById(R.id.lineBottom)
        }
    }
}
