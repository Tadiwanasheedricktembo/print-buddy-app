package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.databinding.ActivityOrderDetailsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class OrderDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrderDetailsBinding
    private lateinit var viewModel: OrderDetailsViewModel

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
        val viewModelFactory = OrderDetailsViewModelFactory(orderId, database.printDao())
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
    }

    private fun displayOrderDetails(order: Order) {
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        binding.textOrderId.text = "Order #${order.id}"
        binding.textOrderDate.text = dateFormat.format(order.date)
        binding.textOrderTotal.text = "Total: ₹ %.2f".format(order.totalAmount)

        val pendingAmount = order.totalAmount - order.paidAmount
        if (pendingAmount > 0) {
            binding.textPaymentStatus.visibility = View.VISIBLE
            binding.textPaymentStatus.text = "Pending: ₹ %.2f".format(pendingAmount)
            binding.buttonPayNow.visibility = View.VISIBLE
            binding.buttonPayNow.setOnClickListener {
                showPaymentQr(pendingAmount, order.id)
            }
        } else {
            binding.textPaymentStatus.visibility = View.GONE
            binding.buttonPayNow.visibility = View.GONE
        }
    }

    private fun showPaymentQr(amount: Double, orderId: Int) {
        val paymentDialog = PaymentDialogFragment.newInstance(amount, orderId)
        paymentDialog.setOnPaymentConfirmedListener {
            lifecycleScope.launch {
                val database = AppDatabase.getDatabase(this@OrderDetailsActivity)
                database.printDao().updatePayment(orderId, amount) // In a real app, you'd add to existing paidAmount
                // Refreshing the view model would be better, but simple update for now
                finish() 
                startActivity(intent)
            }
        }
        paymentDialog.show(supportFragmentManager, "payment_qr")
    }
}
