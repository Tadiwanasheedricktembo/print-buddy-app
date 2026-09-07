package com.tadiwaprintbuddy.app

import android.R
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityOrderDetailsBinding
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale

class OrderDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrderDetailsBinding
    private lateinit var viewModel: OrderDetailsViewModel
    private lateinit var repository: PrintRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrderDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val orderId = intent.getIntExtra("ORDER_ID", -1)
        if (orderId == -1) {
            finish()
            return
        }

        val database = AppDatabase.getDatabase(this)
        val dao = database.printDao()
        repository = PrintRepository(dao)
        
        val viewModelFactory = OrderDetailsViewModelFactory(orderId, dao)
        viewModel = ViewModelProvider(this, viewModelFactory).get(OrderDetailsViewModel::class.java)

        val adapter = OrderDetailAdapter()
        binding.recyclerViewOrderItems.adapter = adapter
        binding.recyclerViewOrderItems.layoutManager = LinearLayoutManager(this)

        viewModel.order.observe(this) { order ->
            order?.let { displayOrderDetails(it) }
        }

        viewModel.orderItems.observe(this) { items ->
            items?.let { adapter.submitList(it) }
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun displayOrderDetails(order: Order) {
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        binding.textOrderId.text = "Order #${order.id}"
        binding.textOrderDate.text = dateFormat.format(order.date)
        binding.textOrderTotal.text = "Total: ₹ %.2f".format(order.totalAmount.toDouble())

        val pendingAmount = order.totalAmount.subtract(order.paidAmount)
        if (pendingAmount.compareTo(BigDecimal.ZERO) > 0) {
            binding.textPaymentStatus.visibility = View.VISIBLE
            binding.textPaymentStatus.text = "Pending: ₹ %.2f".format(pendingAmount.toDouble())
            binding.buttonPayNow.visibility = View.VISIBLE
            binding.buttonPayNow.setOnClickListener {
                showPaymentMethodDialog(pendingAmount, order)
            }
        } else {
            binding.textPaymentStatus.visibility = View.GONE
            binding.buttonPayNow.visibility = View.GONE
        }
    }

    private fun showPaymentMethodDialog(amount: BigDecimal, order: Order) {
        val methods = arrayOf("CASH", "UPI")
        val adapter = ArrayAdapter(this, R.layout.simple_list_item_1, methods)
        
        AlertDialog.Builder(this)
            .setTitle("Select Payment Method")
            .setAdapter(adapter) { _, which ->
                val selectedMethod = methods[which]
                if (selectedMethod == "UPI") {
                    showPaymentQr(amount, order.id)
                } else {
                    processCashPayment(amount, order)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processCashPayment(amount: BigDecimal, order: Order) {
        lifecycleScope.launch {
            repository.updatePayment(order.id, order.paidAmount.add(amount))
            Toast.makeText(this@OrderDetailsActivity, "Payment received in Cash", Toast.LENGTH_SHORT).show()
            finish()
            startActivity(intent)
        }
    }

    private fun showPaymentQr(amount: BigDecimal, orderId: Int) {
        val paymentDialog = PaymentDialogFragment.newInstance(amount, orderId)
        paymentDialog.setOnPaymentConfirmedListener {
            lifecycleScope.launch {
                val currentOrder = viewModel.order.value
                if (currentOrder != null) {
                    repository.updatePayment(orderId, currentOrder.paidAmount.add(amount), "UPI")
                }
                finish() 
                startActivity(intent)
            }
        }
        paymentDialog.show(supportFragmentManager, "payment_qr")
    }
}
