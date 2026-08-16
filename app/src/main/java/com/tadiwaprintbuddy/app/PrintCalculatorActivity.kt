package com.tadiwaprintbuddy.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.databinding.ActivityPrintCalculatorBinding
import com.tadiwaprintbuddy.app.databinding.ItemPrintBatchBinding
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

class PrintCalculatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrintCalculatorBinding
    private lateinit var rateManager: PrintRateManager
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())
    
    private val batchList = mutableListOf<PrintBatch>()
    private lateinit var batchAdapter: PrintBatchAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrintCalculatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        rateManager = PrintRateManager(this)

        setupRecyclerView()
        setupInputs()
        loadRates()
        calculateTotals()
    }

    private fun setupRecyclerView() {
        batchAdapter = PrintBatchAdapter(batchList) { batch ->
            batchList.remove(batch)
            batchAdapter.notifyDataSetChanged()
            calculateTotals()
        }
        binding.recyclerBatches.layoutManager = LinearLayoutManager(this)
        binding.recyclerBatches.adapter = batchAdapter
    }

    private fun setupInputs() {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { calculateTotals() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        binding.editBwPages.addTextChangedListener(watcher)
        binding.editBwRate.addTextChangedListener(watcher)
        binding.editColorPages.addTextChangedListener(watcher)
        binding.editColorRate.addTextChangedListener(watcher)
        binding.editReceived.addTextChangedListener(watcher)

        binding.btnAddtoPos.setOnClickListener {
            addToPos()
        }

        binding.btnAddBatch.setOnClickListener {
            addCurrentToBatch()
        }

        binding.btnClearCart.setOnClickListener {
            batchList.clear()
            batchAdapter.notifyDataSetChanged()
            calculateTotals()
        }
    }

    private fun addCurrentToBatch() {
        val bwPages = (binding.editBwPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val bwRate = (binding.editBwRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        val colorPages = (binding.editColorPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val colorRate = (binding.editColorRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)

        if (bwPages > 0) {
            batchList.add(PrintBatch(
                id = UUID.randomUUID().toString(),
                description = getString(R.string.bw_printing),
                quantity = bwPages,
                unitPrice = bwRate,
                totalPrice = bwPages * bwRate
            ))
        }

        if (colorPages > 0) {
            batchList.add(PrintBatch(
                id = UUID.randomUUID().toString(),
                description = getString(R.string.colour_printing),
                quantity = colorPages,
                unitPrice = colorRate,
                totalPrice = colorPages * colorRate
            ))
        }

        if (bwPages > 0 || colorPages > 0) {
            // Clear inputs after adding to batch
            binding.editBwPages.setText("")
            binding.editColorPages.setText("")
            batchAdapter.notifyDataSetChanged()
            calculateTotals()
        }
    }

    private fun loadRates() {
        binding.editBwRate.setText(rateManager.bwRate.toString())
        binding.editColorRate.setText(rateManager.colorRate.toString())
    }

    private fun calculateTotals() {
        val bwPages = (binding.editBwPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val bwRate = (binding.editBwRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        val colorPages = (binding.editColorPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val colorRate = (binding.editColorRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        val received = (binding.editReceived.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)

        val bwSubtotal = bwPages * bwRate
        val colorSubtotal = colorPages * colorRate
        
        val batchesTotal = batchList.sumOf { it.totalPrice }
        val grandTotal = bwSubtotal + colorSubtotal + batchesTotal
        
        val batchesPages = batchList.sumOf { it.quantity }
        val totalPages = bwPages + colorPages + batchesPages
        
        val change = if (received > grandTotal) received - grandTotal else 0.0

        binding.textBwSubtotal.text = getString(R.string.subtotal_label, currencyFormat.format(bwSubtotal))
        binding.textColorSubtotal.text = getString(R.string.subtotal_label, currencyFormat.format(colorSubtotal))
        binding.textTotalPages.text = totalPages.toString()
        binding.textGrandTotal.text = currencyFormat.format(grandTotal)
        binding.textChange.text = currencyFormat.format(change)
        
        // Visibility of cart section
        binding.layoutCartHeader.visibility = if (batchList.isEmpty()) View.GONE else View.VISIBLE
        binding.recyclerBatches.visibility = if (batchList.isEmpty()) View.GONE else View.VISIBLE

        // Save rates if they changed
        if (bwRate > 0) rateManager.bwRate = bwRate
        if (colorRate > 0) rateManager.colorRate = colorRate
    }

    private fun addToPos() {
        val bwPages = (binding.editBwPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val colorPages = (binding.editColorPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val bwRate = (binding.editBwRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        val colorRate = (binding.editColorRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        
        val currentSubtotal = (bwPages * bwRate) + (colorPages * colorRate)
        val batchesTotal = batchList.sumOf { it.totalPrice }
        val total = currentSubtotal + batchesTotal
        
        val receivedStr = binding.editReceived.text.toString()
        val received = if (receivedStr.isNotEmpty()) receivedStr.toDoubleOrNull()?.coerceAtLeast(0.0) else null

        if (total <= 0) {
            finish()
            return
        }

        val noteBuilder = StringBuilder()
        if (batchList.isNotEmpty()) {
            noteBuilder.append(getString(R.string.multi_batch_label))
            batchList.forEach { batch ->
                noteBuilder.append("${batch.description}(${batch.quantity}), ")
            }
        }
        if (bwPages > 0 || colorPages > 0) {
            noteBuilder.append(getString(R.string.current_input_label) + "$bwPages B&W, $colorPages Colour")
        }
        
        val finalNote = noteBuilder.toString().removeSuffix(", ")

        val resultIntent = Intent().apply {
            putExtra(EXTRA_TOTAL_AMOUNT, total)
            if (received != null) {
                putExtra(EXTRA_RECEIVED_AMOUNT, received)
            }
            putExtra(EXTRA_NOTE, finalNote)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    inner class PrintBatchAdapter(
        private val items: List<PrintBatch>,
        private val onRemove: (PrintBatch) -> Unit
    ) : RecyclerView.Adapter<PrintBatchAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemPrintBatchBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemPrintBatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.textBatchDescription.text = "${item.quantity} ${item.description}"
            holder.binding.textBatchDetails.text = getString(R.string.batch_details_format, item.quantity, currencyFormat.format(item.unitPrice))
            holder.binding.textBatchTotal.text = currencyFormat.format(item.totalPrice)
            holder.binding.btnRemoveBatch.setOnClickListener { onRemove(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val EXTRA_TOTAL_AMOUNT = "extra_total_amount"
        const val EXTRA_RECEIVED_AMOUNT = "extra_received_amount"
        const val EXTRA_NOTE = "extra_note"
    }
}
