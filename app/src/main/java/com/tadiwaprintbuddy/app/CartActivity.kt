package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityCartBinding
import kotlinx.coroutines.launch

class CartActivity : AppCompatActivity(), CartAdapter.OnCartChangedListener {

    companion object {
        const val EXTRA_CART = "cart"
    }

    private lateinit var binding: ActivityCartBinding
    private lateinit var cartItems: MutableList<CartItem>
    private lateinit var adapter: CartAdapter
    private lateinit var repository: PrintRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        cartItems = (intent.getSerializableExtra(EXTRA_CART) as? ArrayList<CartItem>)?.toMutableList()
            ?: mutableListOf()

        adapter = CartAdapter(cartItems, this)

        binding.recyclerCart.layoutManager = LinearLayoutManager(this)
        binding.recyclerCart.adapter = adapter

        updateTotal(adapter.getTotal())
        binding.buttonCompleteOrder.isEnabled = cartItems.isNotEmpty()

        binding.buttonCompleteOrder.setOnClickListener {
            if (cartItems.isEmpty()) {
                Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showCustomerNameDialog()
        }
    }

    private fun showCustomerNameDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_customer_name, null)
        val editCustomerName = dialogView.findViewById<EditText>(R.id.editCustomerName)

        AlertDialog.Builder(this)
            .setTitle("Enter Customer Name")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val customerName = editCustomerName.text.toString()
                if (customerName.isNotBlank()) {
                    lifecycleScope.launch {
                        repository.confirmOrder(customerName, cartItems)
                        Toast.makeText(this@CartActivity, "Order Saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    Toast.makeText(this, "Customer name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCartUpdated(total: Double) {
        updateTotal(total)
        binding.buttonCompleteOrder.isEnabled = cartItems.isNotEmpty()
    }

    private fun updateTotal(total: Double) {
        binding.textTotal.text = getString(R.string.cart_total_format, total)
    }
}
