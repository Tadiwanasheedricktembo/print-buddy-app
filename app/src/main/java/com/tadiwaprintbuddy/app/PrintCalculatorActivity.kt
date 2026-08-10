package com.tadiwaprintbuddy.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.tadiwaprintbuddy.app.databinding.ActivityPrintCalculatorBinding
import java.text.NumberFormat
import java.util.Locale

class PrintCalculatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrintCalculatorBinding
    private lateinit var rateManager: PrintRateManager
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrintCalculatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        rateManager = PrintRateManager(this)

        setupInputs()
        loadRates()
        calculateTotals()
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
        val grandTotal = bwSubtotal + colorSubtotal
        val totalPages = bwPages + colorPages
        val change = if (received > grandTotal) received - grandTotal else 0.0

        binding.textBwSubtotal.text = "Subtotal: ${currencyFormat.format(bwSubtotal)}"
        binding.textColorSubtotal.text = "Subtotal: ${currencyFormat.format(colorSubtotal)}"
        binding.textTotalPages.text = totalPages.toString()
        binding.textGrandTotal.text = currencyFormat.format(grandTotal)
        binding.textChange.text = currencyFormat.format(change)

        // Save rates if they changed
        if (bwRate > 0) rateManager.bwRate = bwRate
        if (colorRate > 0) rateManager.colorRate = colorRate
    }

    private fun addToPos() {
        val bwPages = (binding.editBwPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val colorPages = (binding.editColorPages.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
        val bwRate = (binding.editBwRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        val colorRate = (binding.editColorRate.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        
        val total = (bwPages * bwRate) + (colorPages * colorRate)
        val receivedStr = binding.editReceived.text.toString()
        val received = if (receivedStr.isNotEmpty()) receivedStr.toDoubleOrNull()?.coerceAtLeast(0.0) else null

        if (total <= 0) {
            finish()
            return
        }

        val resultIntent = Intent().apply {
            putExtra(EXTRA_TOTAL_AMOUNT, total)
            if (received != null) {
                putExtra(EXTRA_RECEIVED_AMOUNT, received)
            }
            putExtra(EXTRA_NOTE, "Printing: $bwPages B&W, $colorPages Colour")
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_TOTAL_AMOUNT = "extra_total_amount"
        const val EXTRA_RECEIVED_AMOUNT = "extra_received_amount"
        const val EXTRA_NOTE = "extra_note"
    }
}
