package com.tadiwaprintbuddy.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(PrintRepository(AppDatabase.getDatabase(this).printDao()))
    }

    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    private val startForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val totalStr = data?.getStringExtra(PrintCalculatorActivity.EXTRA_TOTAL_AMOUNT)
            val total = totalStr?.let { try { BigDecimal(it) } catch (e: Exception) { BigDecimal.ZERO } } ?: BigDecimal.ZERO
            
            val receivedStr = data?.getStringExtra(PrintCalculatorActivity.EXTRA_RECEIVED_AMOUNT)
            val received = receivedStr?.let { try { BigDecimal(it) } catch (e: Exception) { null } }
            
            val note = data?.getStringExtra(PrintCalculatorActivity.EXTRA_NOTE) ?: ""

            if (total.compareTo(BigDecimal.ZERO) > 0) {
                binding.editPrice.setText(total.toPlainString())
                binding.editQuantity.setText("1")
                viewModel.onPriceChanged(total)
                viewModel.onReceivedAmountChanged(received)
                
                if (note.isNotBlank()) {
                    Toast.makeText(this, note, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupInputs()
        setupNavigation()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_calculator -> {
                    startForResult.launch(Intent(this, PrintCalculatorActivity::class.java))
                    true
                }
                R.id.action_beauty_account -> {
                    startActivity(Intent(this, BeautyAccountActivity::class.java))
                    true
                }
                R.id.action_expenses -> {
                    startActivity(Intent(this, ExpenseActivity::class.java))
                    true
                }
                R.id.action_notes -> {
                    // Not implemented here, but can add if needed
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupInputs() {
        binding.editCustomerName.addTextChangedListener {
            viewModel.onCustomerNameChanged(it.toString())
        }

        binding.editQuantity.addTextChangedListener {
            val q = it.toString().toIntOrNull() ?: 1
            viewModel.onQuantityChanged(q)
        }

        binding.btnQtyPlus.setOnClickListener {
            val current = binding.editQuantity.text.toString().toIntOrNull() ?: 1
            binding.editQuantity.setText((current + 1).toString())
        }

        binding.btnQtyMinus.setOnClickListener {
            val current = binding.editQuantity.text.toString().toIntOrNull() ?: 1
            if (current > 1) {
                binding.editQuantity.setText((current - 1).toString())
            }
        }

        binding.editPrice.addTextChangedListener {
            val p = try { BigDecimal(it.toString()) } catch (e: Exception) { BigDecimal.ZERO }
            viewModel.onPriceChanged(p)
        }

        binding.btnCompleteOrder.setOnClickListener {
            showPaymentStatusDialog()
        }

        binding.btnShowQr.setOnClickListener {
            val state = viewModel.uiState.value
            val dialog = PaymentDialogFragment.newInstance(state.total)
            dialog.show(supportFragmentManager, "payment_qr")
        }
    }

    private fun setupNavigation() {
        binding.navTransactions.setOnClickListener {
            startActivity(Intent(this, SettlementHistoryActivity::class.java))
        }
        binding.navDebtors.setOnClickListener {
            startActivity(Intent(this, DebtorCreditActivity::class.java))
        }
        binding.navAnalytics.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        binding.navRefs.setOnClickListener {
            // Refers to Account/Settings
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.textLineTotal.text = currencyFormat.format(state.balanceToPay.toDouble())
                
                binding.btnCompleteOrder.isEnabled = state.isCompleteEnabled
                binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                // Update UI based on payment method selection if needed
                val checkedId = binding.toggleGroupPaymentMethod.checkedButtonId
                if (checkedId == View.NO_ID) {
                    binding.toggleGroupPaymentMethod.check(R.id.btnCash)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is MainViewModel.MainEvent.OrderCompleted -> {
                        Toast.makeText(this@MainActivity, "Order Completed", Toast.LENGTH_SHORT).show()
                        resetInputs()
                    }
                    is MainViewModel.MainEvent.ShowError -> {
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showPaymentStatusDialog() {
        val checkedId = binding.toggleGroupPaymentMethod.checkedButtonId
        val method = when (checkedId) {
            R.id.btnUpi -> "UPI"
            R.id.btnCredit -> "Credit"
            else -> "Cash"
        }

        if (method == "Credit") {
            viewModel.completeOrder("Credit", "NONE")
        } else {
            viewModel.completeOrder("Paid", method)
        }
    }

    private fun resetInputs() {
        binding.editCustomerName.text.clear()
        binding.editQuantity.setText("1")
        binding.editPrice.setText("0")
        viewModel.resetForm()
    }
}
