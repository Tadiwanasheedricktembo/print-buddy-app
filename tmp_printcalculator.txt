package com.tadiwaprintbuddy.app

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
import java.math.BigDecimal
import java.math.RoundingMode
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
        binding.editOtherAmount.addTextChangedListener(watcher)
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
        val bwRateStr = binding.editBwRate.text.toString()
        val bwRate = try { BigDecimal(bwRateStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        
        val colorPages = (binding.editColorPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val colorRateStr = binding.editColorRate.text.toString()
        val colorRate = try { BigDecimal(colorRateStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        
        val otherAmountStr = binding.editOtherAmount.text.toString()
        val otherAmount = try { BigDecimal(otherAmountStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        
        val otherDesc = binding.editOtherDesc.text.toString().trim()

        if (bwPages > 0) {
            batchList.add(PrintBatch(
                id = UUID.randomUUID().toString(),
                description = getString(R.string.bw_printing),
                quantity = bwPages,
                unitPrice = bwRate,
                totalPrice = bwRate.multiply(BigDecimal(bwPages))
            ))
        }

        if (colorPages > 0) {
            batchList.add(PrintBatch(
                id = UUID.randomUUID().toString(),
                description = getString(R.string.colour_printing),
                quantity = colorPages,
                unitPrice = colorRate,
                totalPrice = colorRate.multiply(BigDecimal(colorPages))
            ))
        }

        if (otherAmount.compareTo(BigDecimal.ZERO) > 0) {
            batchList.add(PrintBatch(
                id = UUID.randomUUID().toString(),
                description = if (otherDesc.isNotEmpty()) otherDesc else "Other Expense",
                quantity = 0, // 0 quantity for non-page items
                unitPrice = otherAmount,
                totalPrice = otherAmount
            ))
        }

        if (bwPages > 0 || colorPages > 0 || otherAmount.compareTo(BigDecimal.ZERO) > 0) {
            // Clear inputs after adding to batch
            binding.editBwPages.setText("")
            binding.editColorPages.setText("")
            binding.editOtherAmount.setText("")
            binding.editOtherDesc.setText("")
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
        val bwRateStr = binding.editBwRate.text.toString()
        val bwRate = try { BigDecimal(bwRateStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        
        val colorPages = (binding.editColorPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val colorRateStr = binding.editColorRate.text.toString()
        val colorRate = try { BigDecimal(colorRateStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        
        val otherAmountStr = binding.editOtherAmount.text.toString()
        val otherAmount = try { BigDecimal(otherAmountStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        
        val receivedStr = binding.editReceived.text.toString()
        val received = try { BigDecimal(receivedStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)

        val bwCurrentSubtotal = bwRate.multiply(BigDecimal(bwPages))
        val colorCurrentSubtotal = colorRate.multiply(BigDecimal(colorPages))

        val bwSubtotal = bwCurrentSubtotal.add(batchList.filter { it.description == getString(R.string.bw_printing) }.fold(BigDecimal.ZERO) { acc, b -> acc.add(b.totalPrice) })
        val colorSubtotal = colorCurrentSubtotal.add(batchList.filter { it.description == getString(R.string.colour_printing) }.fold(BigDecimal.ZERO) { acc, b -> acc.add(b.totalPrice) })
        val otherSubtotal = otherAmount.add(batchList.filter { it.quantity == 0 }.fold(BigDecimal.ZERO) { acc, b -> acc.add(b.totalPrice) })
        
        val grandTotal = bwSubtotal.add(colorSubtotal).add(otherSubtotal)
        
        val totalPages = bwPages + colorPages + batchList.sumOf { it.quantity }
        
        val change = if (received.compareTo(grandTotal) > 0) received.subtract(grandTotal) else BigDecimal.ZERO

        binding.textBwSubtotal.text = getString(R.string.subtotal_label, currencyFormat.format(bwCurrentSubtotal.toDouble()))
        binding.textColorSubtotal.text = getString(R.string.subtotal_label, currencyFormat.format(colorCurrentSubtotal.toDouble()))
        binding.textTotalPages.text = totalPages.toString()
        
        binding.textTotalBw.text = currencyFormat.format(bwSubtotal.toDouble())
        binding.layoutSummaryBw.visibility = if (bwSubtotal.compareTo(BigDecimal.ZERO) > 0) View.VISIBLE else View.GONE
        
        binding.textTotalColor.text = currencyFormat.format(colorSubtotal.toDouble())
        binding.layoutSummaryColor.visibility = if (colorSubtotal.compareTo(BigDecimal.ZERO) > 0) View.VISIBLE else View.GONE
        
        binding.textTotalOther.text = currencyFormat.format(otherSubtotal.toDouble())
        binding.layoutSummaryOther.visibility = if (otherSubtotal.compareTo(BigDecimal.ZERO) > 0) View.VISIBLE else View.GONE
        
        binding.textGrandTotal.text = currencyFormat.format(grandTotal.toDouble())
        binding.textChange.text = currencyFormat.format(change.toDouble())
        
        // Visibility of cart section
        binding.layoutCartHeader.visibility = if (batchList.isEmpty()) View.GONE else View.VISIBLE
        binding.recyclerBatches.visibility = if (batchList.isEmpty()) View.GONE else View.VISIBLE

        // Save rates if they changed (Converting back to Double for rateManager)
        if (bwRate.compareTo(BigDecimal.ZERO) > 0) rateManager.bwRate = bwRate.toDouble()
        if (colorRate.compareTo(BigDecimal.ZERO) > 0) rateManager.colorRate = colorRate.toDouble()
    }

    private fun addToPos() {
        val bwPages = (binding.editBwPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val colorPages = (binding.editColorPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val bwRateStr = binding.editBwRate.text.toString()
        val bwRate = try { BigDecimal(bwRateStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        val colorRateStr = binding.editColorRate.text.toString()
        val colorRate = try { BigDecimal(colorRateStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        val otherAmountStr = binding.editOtherAmount.text.toString()
        val otherAmount = try { BigDecimal(otherAmountStr) } catch (e: Exception) { BigDecimal.ZERO }.max(BigDecimal.ZERO)
        
        val otherDesc = binding.editOtherDesc.text.toString().trim()
        
        val currentSubtotal = bwRate.multiply(BigDecimal(bwPages)).add(colorRate.multiply(BigDecimal(colorPages))).add(otherAmount)
        val batchesTotal = batchList.fold(BigDecimal.ZERO) { acc, b -> acc.add(b.totalPrice) }
        val total = currentSubtotal.add(batchesTotal)
        
        val receivedStr = binding.editReceived.text.toString()
        val received = if (receivedStr.isNotEmpty()) {
            try { BigDecimal(receivedStr).max(BigDecimal.ZERO) } catch (e: Exception) { null }
        } else null

        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            finish()
            return
        }

        val noteBuilder = StringBuilder()
        if (batchList.isNotEmpty()) {
            noteBuilder.append(getString(R.string.multi_batch_label))
            batchList.forEach { batch ->
                if (batch.quantity > 0) {
                    noteBuilder.append("${batch.description}(${batch.quantity}), ")
                } else {
                    noteBuilder.append("${batch.description}, ")
                }
            }
        }
        if (bwPages > 0 || colorPages > 0) {
            noteBuilder.append(getString(R.string.current_input_label) + "$bwPages B&W, $colorPages Colour")
        }
        if (otherAmount.compareTo(BigDecimal.ZERO) > 0) {
            val desc = if (otherDesc.isNotEmpty()) otherDesc else "Misc"
            if (noteBuilder.isNotEmpty()) noteBuilder.append(", ")
            noteBuilder.append("$desc: ${currencyFormat.format(otherAmount.toDouble())}")
        }
        
        val finalNote = noteBuilder.toString().removeSuffix(", ")

        val resultIntent = Intent().apply {
            putExtra(EXTRA_TOTAL_AMOUNT, total.toPlainString())
            if (received != null) {
                putExtra(EXTRA_RECEIVED_AMOUNT, received.toPlainString())
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
            if (item.quantity > 0) {
                holder.binding.textBatchDescription.text = "${item.quantity} ${item.description}"
                holder.binding.textBatchDetails.text = getString(R.string.batch_details_format, item.quantity, currencyFormat.format(item.unitPrice.toDouble()))
            } else {
                holder.binding.textBatchDescription.text = item.description
                holder.binding.textBatchDetails.text = currencyFormat.format(item.unitPrice.toDouble())
            }
            holder.binding.textBatchTotal.text = currencyFormat.format(item.totalPrice.toDouble())
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
