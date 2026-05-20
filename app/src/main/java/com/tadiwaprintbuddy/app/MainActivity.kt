package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: PrintRepository
    private val cartItems = mutableListOf<CartItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupClickListeners()
        setupTextWatchers()
        calculateLineTotal()
        runEntranceAnimations()
        observeMetrics()
        
        ReminderScheduler(this).scheduleReminder()
    }

    private fun observeMetrics() {
        // Stats removed from UI
    }

    private fun runEntranceAnimations() {
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade)
        binding.toolbar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in_slow))
        binding.layoutCustomerName.parent.let { (it as View).startAnimation(slideUp) }
        binding.bottomNavigation.parent.let { (it as View).startAnimation(slideUp) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_beauty_account -> {
                val intent = Intent(this, BeautyAccountActivity::class.java)
                startActivity(intent)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_right, R.anim.slide_out_left)
                } else {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                }
                true
            }
            R.id.action_expenses -> {
                startActivity(Intent(this, ExpenseActivity::class.java))
                true
            }
            R.id.action_lock -> {
                SecurityManager.getInstance(this).lockApp()
                recreate()
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

    override fun onStop() {
        super.onStop()
        hideProgressBar()
    }

    private fun showProgressBar() {
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun hideProgressBar() {
        binding.progressBar.visibility = View.GONE
    }

    private fun setupClickListeners() {
        binding.btnCompleteOrder.setOnClickListener { initiateOrderCompletion() }

        binding.btnShowQr.setOnClickListener {
            showPaymentQr()
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showProgressBar()
            when (item.itemId) {
                R.id.button_view_dashboard -> {
                    startActivity(Intent(this, DashboardActivity::class.java))
                    true
                }
                R.id.btnViewOrders -> {
                    startActivity(Intent(this, OrdersActivity::class.java))
                    true
                }
                R.id.button_view_debtor_credit -> {
                    startActivity(Intent(this, DebtorCreditActivity::class.java))
                    true
                }
                R.id.button_printer_references -> {
                    startActivity(Intent(this, PrinterReferenceActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun showPaymentQr() {
        val quantity = binding.editQuantity.text.toString().toIntOrNull() ?: 0
        val price = binding.editPrice.text.toString().toDoubleOrNull() ?: 0.0
        val currentAmount = if (quantity > 0 && price > 0) quantity * price else null
        
        val dialog = PaymentDialogFragment.newInstance(
            amount = currentAmount,
            isGeneral = true
        )
        dialog.show(supportFragmentManager, "PaymentQR")
    }

    private fun setupTextWatchers() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                calculateLineTotal()
            }
        }
        binding.editQuantity.addTextChangedListener(textWatcher)
        binding.editPrice.addTextChangedListener(textWatcher)
    }

    private fun calculateLineTotal() {
        val quantity = binding.editQuantity.text.toString().toIntOrNull() ?: 0
        val price = binding.editPrice.text.toString().toDoubleOrNull() ?: 0.0
        val total = quantity * price

        val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
        val format = NumberFormat.getCurrencyInstance(locale)
        
        lifecycleScope.launch {
            val customerName = binding.editCustomerName.text.toString()
            val existingBalance = if (customerName.isNotBlank()) {
                repository.getCustomerBalance(customerName)
            } else 0.0

            withContext(Dispatchers.Main) {
                if (total > 0) {
                    binding.layoutOrderBreakdown.visibility = View.VISIBLE
                    binding.textOrderTotalRaw.text = format.format(total)

                    if (existingBalance < 0) {
                        // Customer has credit
                        val creditAvailable = Math.abs(existingBalance)
                        val creditUsed = Math.min(total, creditAvailable)
                        val toPay = Math.max(0.0, total - creditUsed)

                        binding.layoutCreditUsage.visibility = View.VISIBLE
                        binding.textCreditUsed.text = "- ${format.format(creditUsed)}"
                        binding.textLineTotal.text = format.format(toPay)
                    } else {
                        binding.layoutCreditUsage.visibility = View.GONE
                        binding.textLineTotal.text = format.format(total)
                    }
                } else {
                    binding.layoutOrderBreakdown.visibility = View.GONE
                }
            }
        }

        binding.btnCompleteOrder.isEnabled = quantity > 0 && price > 0
    }

    private fun initiateOrderCompletion() {
        val quantity = binding.editQuantity.text.toString().toIntOrNull() ?: 0
        val price = binding.editPrice.text.toString().toDoubleOrNull() ?: 0.0

        if (quantity <= 0 || price <= 0) {
            Toast.makeText(this, "Quantity and Price must be greater than zero", Toast.LENGTH_SHORT).show()
            return
        }

        val sheet = PaymentBottomSheet { status, method ->
            completeOrder(status, method)
        }
        sheet.show(supportFragmentManager, PaymentBottomSheet.TAG)
    }

    private fun completeOrder(paymentStatus: String, paymentMethod: String) {
        val customerName = binding.editCustomerName.text.toString()
        val quantity = binding.editQuantity.text.toString().toIntOrNull() ?: 0
        val price = binding.editPrice.text.toString().toDoubleOrNull() ?: 0.0
        val lineTotal = price * quantity

        if (paymentStatus == "Owes Me" && customerName.isBlank()) {
            Toast.makeText(this, "Customer name is required for Credit orders", Toast.LENGTH_SHORT).show()
            return
        }

        showProgressBar()
        lifecycleScope.launch {
            cartItems.clear()
            // Using "General" as a fallback default service name to ensure database compatibility
            cartItems.add(CartItem("General", price, quantity))

            // Use orders as the source of truth for customer debt.
            // Do NOT call addOrUpdateDebtorCredit here as it creates a
            // settlement and summary entry in addition to the order
            // which results in double-counting the same debt.
            repository.confirmOrder(customerName, cartItems, paymentMethod)

            // Auto-deduct from stock if service name matches a stock item
            for (item in cartItems) {
                val stockItem = repository.getStockItemByName(item.serviceName)
                if (stockItem != null) {
                    repository.deductStockByName(item.serviceName, item.quantity)
                }
            }

            Toast.makeText(this@MainActivity, "Order saved successfully!", Toast.LENGTH_SHORT).show()
            cartItems.clear()
            binding.editCustomerName.text?.clear()
            resetForm()
            hideProgressBar()
        }
    }

    private fun resetForm() {
        binding.editQuantity.setText("1")
        binding.editPrice.setText("0")
        calculateLineTotal()
    }
}
