package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.data.SettlementHistory
import com.tadiwaprintbuddy.app.databinding.ActivitySettlementHistoryBinding
import com.tadiwaprintbuddy.app.databinding.ItemSettlementBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettlementHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettlementHistoryBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: SettlementHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettlementHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        adapter = SettlementHistoryAdapter(emptyList())
        binding.recyclerSettlements.layoutManager = LinearLayoutManager(this)
        binding.recyclerSettlements.adapter = adapter

        loadSettlements()
    }

    private fun loadSettlements() {
        lifecycleScope.launch {
            val settlements = repository.getAllSettlements()
            if (settlements.isEmpty()) {
                binding.textEmpty.visibility = View.VISIBLE
                binding.recyclerSettlements.visibility = View.GONE
            } else {
                binding.textEmpty.visibility = View.GONE
                binding.recyclerSettlements.visibility = View.VISIBLE
                adapter.updateSettlements(settlements)
            }
        }
    }
}

class SettlementHistoryAdapter(private var settlements: List<SettlementHistory>) :
    RecyclerView.Adapter<SettlementHistoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSettlementBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettlementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val settlement = settlements[position]
        val context = holder.itemView.context
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        val dateFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())

        holder.binding.textCustomerName.text = settlement.customerName
        holder.binding.textDate.text = dateFormat.format(Date(settlement.timestamp))
        holder.binding.textPreviousBalance.text = format.format(settlement.previousBalance)
        holder.binding.textSettledAmount.text = format.format(settlement.settledAmount)
        holder.binding.textRemainingBalance.text = format.format(settlement.remainingBalance)
        
        if (settlement.note.isNullOrEmpty()) {
            holder.binding.textNote.visibility = View.GONE
        } else {
            holder.binding.textNote.visibility = View.VISIBLE
            holder.binding.textNote.text = "Note: ${settlement.note}"
        }
    }

    override fun getItemCount() = settlements.size

    fun updateSettlements(newSettlements: List<SettlementHistory>) {
        settlements = newSettlements
        notifyDataSetChanged()
    }
}
