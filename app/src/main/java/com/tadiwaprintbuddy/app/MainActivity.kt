package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cartItems = mutableListOf<CartItem>()

    private val services = listOf(
        Service(1, "Passport Photos", 25.00),
        Service(2, "A4 Document Printing", 2.50),
        Service(3, "Photo Prints (4x6)", 5.00),
        Service(4, "Business Cards", 250.00),
        Service(5, "Poster Printing (A3)", 75.00)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = ServiceAdapter(services) { service ->
            cartItems.add(
                CartItem(
                    service.name,
                    service.price,
                    1
                )
            )
        }

        binding.recyclerServices.layoutManager = LinearLayoutManager(this)
        binding.recyclerServices.adapter = adapter

        binding.buttonGoToCart.setOnClickListener {
            val intent = Intent(this, CartActivity::class.java)
            intent.putExtra(CartActivity.EXTRA_CART, ArrayList(cartItems))
            startActivity(intent)
        }

        binding.buttonViewDashboard.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        binding.btnViewOrders.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
    }
}
