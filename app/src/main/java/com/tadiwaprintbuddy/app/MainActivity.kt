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
            val itemNames = data?.getStringArrayListExtra(PrintCalculatorActivity.EXTRA_ITEM_NAMES).orEmpty()
            val itemPrices = data?.getStringArrayListExtra(PrintCalculatorActivity.EXTRA_ITEM_PRICES).orEmpty()
            val itemQuantities = data?.getIntegerArrayListExtra(PrintCalculatorActivity.EXTRA_ITEM_QUANTITIES).orEmpty()
            val calculatorItems = if (itemNames.isNotEmpty() && itemNames.size == itemPrices.size && itemNames.size == itemQuantities.size) {
                itemNames.indices.mapNotNull { index ->
                    val unitPrice = try { BigDecimal(itemPrices[index]) } catch (e: Exception) { null }
                    if (unitPrice == null || itemQuantities[index] <= 0 || PrintOrderLine.typeFromStoredServiceName(itemNames[index]) == null) {
                        null
                    } else {
                        CartItem(itemNames[index], unitPrice.max(BigDecimal.ZERO), itemQuantities[index])
                    }
                }.takeIf { it.size == itemNames.size }.orEmpty()
            } else emptyList()

            if (total.compareTo(BigDecimal.ZERO) > 0) {
                binding.editPrice.setText(total.toPlainString())
                binding.editQuantity.setText("1")
                viewModel.onPriceChanged(total)
                viewModel.onReceivedAmountChanged(received)
                viewModel.onCalculatorOrderSelected(
                    calculatorItems,
                    (data?.getIntExtra(PrintCalculatorActivity.EXTRA_TOTAL_PAGES, 0) ?: 0).coerceAtLeast(0)
                )
                
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
                    startActivity(Intent(this, NotesActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private var isProgrammaticChange = false
    private var hasRestoredDraft = false

    private fun setupInputs() {
        binding.editCustomerName.addTextChangedListener {
            if (!isProgrammaticChange) viewModel.onCustomerNameChanged(it.toString())
        }

        binding.editQuantity.addTextChangedListener {
            if (!isProgrammaticChange) viewModel.onQuantityInputChanged(it.toString())
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
            if (!isProgrammaticChange) viewModel.onPriceInputChanged(it.toString())
        }

        binding.btnCash.setOnClickListener { onQuickOrderPaymentButtonClicked(R.id.btnCash) }
        binding.btnUpi.setOnClickListener { onQuickOrderPaymentButtonClicked(R.id.btnUpi) }
        binding.btnCredit.setOnClickListener { onQuickOrderPaymentButtonClicked(R.id.btnCredit) }

        binding.btnShowQr.setOnClickListener {
            val state = viewModel.uiState.value
            val dialog = PaymentDialogFragment.newInstance(state.total)
            dialog.show(supportFragmentManager, "payment_qr")
        }
    }

    private fun setupNavigation() {
        binding.navTransactions.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
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
                runOnUiThread {
                    if (state.isDraftLoaded && !hasRestoredDraft) {
                        hasRestoredDraft = true
                        isProgrammaticChange = true
                        binding.editCustomerName.setText(state.customerName)
                        binding.editQuantity.setText(state.quantityInput)
                        binding.editPrice.setText(state.priceInput)
                        binding.toggleGroupPaymentMethod.check(paymentMethodButtonId(state.paymentMethod))
                        isProgrammaticChange = false
                    }
                    binding.textLineTotal.text = currencyFormat.format(state.balanceToPay.toDouble())

                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    val checkedId = binding.toggleGroupPaymentMethod.checkedButtonId
                    if (checkedId == View.NO_ID) {
                        isProgrammaticChange = true
                        binding.toggleGroupPaymentMethod.check(R.id.btnCash)
                        isProgrammaticChange = false
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                runOnUiThread {
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
    }

    override fun onStop() {
        lifecycleScope.launch { viewModel.flushDraft() }
        super.onStop()
    }

    private fun onQuickOrderPaymentButtonClicked(buttonId: Int) {
        binding.toggleGroupPaymentMethod.check(buttonId)
        viewModel.onPaymentMethodSelected(paymentMethodFor(buttonId))
    }

    private fun paymentMethodFor(buttonId: Int): String = when (buttonId) {
        R.id.btnUpi -> "UPI"
        R.id.btnCredit -> "Credit"
        else -> "Cash"
    }

    private fun paymentMethodButtonId(method: String): Int = when (method.uppercase(Locale.ROOT)) {
        "UPI" -> R.id.btnUpi
        "CREDIT" -> R.id.btnCredit
        else -> R.id.btnCash
    }

    private fun resetInputs() {
        isProgrammaticChange = true
        binding.editCustomerName.text.clear()
        binding.editQuantity.setText("1")
        binding.editPrice.setText("0")
        binding.toggleGroupPaymentMethod.check(R.id.btnCash)
        isProgrammaticChange = false
        viewModel.resetForm()
    }
}
