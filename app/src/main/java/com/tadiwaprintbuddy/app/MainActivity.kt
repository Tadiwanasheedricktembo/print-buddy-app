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
import kotlinx.coroutines.launch
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
        
        ReminderScheduler(this).scheduleReminder()
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
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
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

        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        
        if (binding.textLineTotal.text.toString() != format.format(total)) {
            binding.textLineTotal.animate().alpha(0f).setDuration(100).withEndAction {
                binding.textLineTotal.text = format.format(total)
                binding.textLineTotal.animate().alpha(1f).setDuration(100).start()
            }.start()
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

            if (paymentStatus == "Owes Me") {
                repository.addOrUpdateDebtorCredit(customerName, lineTotal)
            }

            repository.confirmOrder(customerName, cartItems, paymentMethod)

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
