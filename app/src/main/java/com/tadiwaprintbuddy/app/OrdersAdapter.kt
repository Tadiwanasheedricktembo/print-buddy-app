package com.tadiwaprintbuddy.app

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.Locale

class OrdersAdapter(private var orders: List<Order>) : RecyclerView.Adapter<OrdersAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

    fun updateOrders(newOrders: List<Order>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(orders[position])
    }

    override fun getItemCount(): Int = orders.size

    inner class ViewHolder(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            binding.textOrderId.text = "Order #${order.id}"
            binding.textTotalAmount.text = "₹ %.2f".format(order.totalAmount)
            binding.textDate.text = dateFormat.format(order.date)

            itemView.setOnClickListener {
                val context = itemView.context
                val intent = Intent(context, OrderDetailsActivity::class.java).apply {
                    putExtra("ORDER_ID", order.id)
                }
                context.startActivity(intent)
            }
        }
    }
}
