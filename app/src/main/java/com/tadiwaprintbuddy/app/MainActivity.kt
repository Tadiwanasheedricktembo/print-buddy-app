package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
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

    private val services = listOf(
        Service(1, "Passport Photos", 25.00),
        Service(2, "A4 Document Printing", 2.50),
        Service(3, "Photo Prints (4x6)", 5.00),
        Service(4, "Business Cards", 250.00),
        Service(5, "Poster Printing (A3)", 75.00)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupDropdowns()
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

    private fun setupDropdowns() {
        val serviceNames = services.map { it.name }
        val serviceAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, serviceNames)
        binding.autoCompleteService.setAdapter(serviceAdapter)

        binding.autoCompleteService.setOnItemClickListener { parent, _, position, _ ->
            val selectedService = services.find { it.name == parent.getItemAtPosition(position).toString() }
            selectedService?.let {
                binding.editPrice.setText(it.price.toString())
            }
        }

        val paymentStatus = arrayOf("Paid", "Owes Me")
        val paymentAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, paymentStatus)
        binding.autoCompletePaymentStatus.setAdapter(paymentAdapter)
        binding.autoCompletePaymentStatus.setText(paymentStatus[0], false)
    }

    private fun setupClickListeners() {
        binding.btnCompleteOrder.setOnClickListener { completeOrder() }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
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

    private fun addCurrentItemToCart(): Boolean {
        val customerName = binding.editCustomerName.text.toString()
        val serviceName = binding.autoCompleteService.text.toString()
        val selectedService = services.find { it.name == serviceName }
        val quantity = binding.editQuantity.text.toString().toIntOrNull() ?: 0
        val price = binding.editPrice.text.toString().toDoubleOrNull() ?: 0.0
        val paymentStatus = binding.autoCompletePaymentStatus.text.toString()

        if (selectedService == null) {
            Toast.makeText(this, "Please select a valid service", Toast.LENGTH_SHORT).show()
            return false
        }

        if (paymentStatus == "Owes Me" && customerName.isBlank()) {
            Toast.makeText(this, "Customer name is required when payment status is 'Owes Me'", Toast.LENGTH_SHORT).show()
            return false
        }

        if (quantity <= 0 || price <= 0) {
            Toast.makeText(this, "Quantity and Price must be greater than zero", Toast.LENGTH_SHORT).show()
            return false
        }

        val lineTotal = price * quantity
        cartItems.add(CartItem(selectedService.name, price, quantity))

        if (paymentStatus == "Owes Me") {
            lifecycleScope.launch {
                repository.addOrUpdateDebtorCredit(customerName, lineTotal)
            }
        }
        return true
    }

    private fun completeOrder() {
        cartItems.clear()

        if (!addCurrentItemToCart()) {
            return
        }

        val customerName = binding.editCustomerName.text.toString()
        showProgressBar()
        lifecycleScope.launch {
            repository.confirmOrder(customerName, cartItems)
            Toast.makeText(this@MainActivity, "Order saved successfully!", Toast.LENGTH_SHORT).show()
            cartItems.clear()
            binding.editCustomerName.text?.clear()
            resetForm()
            hideProgressBar()
        }
    }

    private fun resetForm() {
        binding.autoCompleteService.setText("", false)
        binding.editQuantity.setText("1")
        binding.editPrice.setText("0")
        binding.autoCompletePaymentStatus.setText("Paid", false)
        calculateLineTotal()
    }
}
