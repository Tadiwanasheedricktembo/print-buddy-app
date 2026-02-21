package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.data.OrderItem
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

            lifecycleScope.launch {
                val total = adapter.getTotal()
                val order = Order(
                    totalAmount = total,
                    date = System.currentTimeMillis()
                )

                val orderItems = cartItems.map { cartItem ->
                    OrderItem(
                        serviceName = cartItem.serviceName,
                        price = cartItem.price,
                        quantity = cartItem.quantity,
                        orderId = 0 // Will be set after order insertion
                    )
                }

                repository.saveOrder(order, orderItems)

                cartItems.clear()
                adapter.notifyDataSetChanged()

                Toast.makeText(this@CartActivity, "Order Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCartUpdated(total: Double) {
        updateTotal(total)
        binding.buttonCompleteOrder.isEnabled = cartItems.isNotEmpty()
    }

    private fun updateTotal(total: Double) {
        binding.textTotal.text = getString(R.string.cart_total_format, total)
    }
}
