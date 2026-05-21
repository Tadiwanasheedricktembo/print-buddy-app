package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(PrintRepository(AppDatabase.getDatabase(this).printDao()))
    }

    private val currencyFormat: NumberFormat by lazy {
        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        NumberFormat.getCurrencyInstance(locale)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setSupportActionBar(binding.toolbar)

        setupClickListeners()
        setupTextWatchers()
        setupStepper()
        observeViewModel()
        
        ReminderScheduler(this).scheduleReminder()
    }

    private fun setupStepper() {
        binding.btnQtyMinus.setOnClickListener {
            val current = binding.editQuantity.text.toString().toIntOrNull() ?: 1
            if (current > 1) {
                binding.editQuantity.setText((current - 1).toString())
            }
        }
        binding.btnQtyPlus.setOnClickListener {
            val current = binding.editQuantity.text.toString().toIntOrNull() ?: 1
            binding.editQuantity.setText((current + 1).toString())
        }
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom)
            binding.appBarLayout.updatePadding(top = systemBars.top)
            insets
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUi(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun handleEvent(event: MainViewModel.MainEvent) {
        when (event) {
            MainViewModel.MainEvent.OrderCompleted -> {
                Toast.makeText(this, "Order saved successfully!", Toast.LENGTH_SHORT).show()
                clearFormFields()
            }
            is MainViewModel.MainEvent.ShowError -> {
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearFormFields() {
        binding.editCustomerName.setText("")
        binding.editQuantity.setText("1")
        binding.editPrice.setText("0")
        binding.editCustomerName.clearFocus()
    }

    private fun updateUi(state: OrderUiState) {
        binding.layoutOrderBreakdown.visibility = if (state.total > 0) View.VISIBLE else View.GONE
        binding.textLineTotal.text = currencyFormat.format(state.balanceToPay)
        
        binding.btnCompleteOrder.isEnabled = state.isCompleteEnabled
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_beauty_account -> {
                startActivityWithTransition(BeautyAccountActivity::class.java)
                true
            }
            R.id.action_expenses -> {
                startActivity(Intent(this, ExpenseActivity::class.java))
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startActivityWithTransition(activityClass: Class<*>) {
        val intent = Intent(this, activityClass)
        startActivity(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_right, R.anim.slide_out_left)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    private fun setupClickListeners() {
        binding.btnCompleteOrder.setOnClickListener { initiateOrderCompletion() }
        
        binding.btnCash.setOnClickListener { viewModel.completeOrder("Paid", "CASH") }
        binding.btnUpi.setOnClickListener { viewModel.completeOrder("Paid", "UPI") }
        binding.btnCredit.setOnClickListener { viewModel.completeOrder("Credit", "OWES_ME") }

        binding.btnShowQr.setOnClickListener { showPaymentQr() }

        binding.navHome.setOnClickListener {
            binding.appBarLayout.setExpanded(true)
            binding.editCustomerName.requestFocus()
        }

        binding.navAnalytics.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        binding.navTransactions.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }

        binding.navDebtors.setOnClickListener {
            startActivity(Intent(this, DebtorCreditActivity::class.java))
        }

        binding.navRefs.setOnClickListener {
            startActivity(Intent(this, PrinterReferenceActivity::class.java))
        }
    }

    private fun showPaymentQr() {
        val state = viewModel.uiState.value
        val dialog = PaymentDialogFragment.newInstance(
            amount = if (state.total > 0) state.total else null,
            isGeneral = true
        )
        dialog.show(supportFragmentManager, "PaymentQR")
    }

    private fun setupTextWatchers() {
        binding.editCustomerName.afterTextChanged { viewModel.onCustomerNameChanged(it) }
        binding.editQuantity.afterTextChanged { viewModel.onQuantityChanged(it.toIntOrNull() ?: 0) }
        binding.editPrice.afterTextChanged { viewModel.onPriceChanged(it.toDoubleOrNull() ?: 0.0) }
    }

    private fun EditText.afterTextChanged(action: (String) -> Unit) {
        this.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { action(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun initiateOrderCompletion() {
        val state = viewModel.uiState.value
        if (state.quantity <= 0 || state.price <= 0) {
            Toast.makeText(this, "Quantity and Price must be greater than zero", Toast.LENGTH_SHORT).show()
            return
        }

        val sheet = PaymentBottomSheet { status, method ->
            viewModel.completeOrder(status, method)
        }
        sheet.show(supportFragmentManager, PaymentBottomSheet.TAG)
    }
}
