package com.tadiwaprintbuddy.app

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.Locale

class OrdersAdapter(
    private var orders: List<Order>,
    private val onDeleteClick: (Order) -> Unit
) : RecyclerView.Adapter<OrdersAdapter.ViewHolder>() {

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

            // Balance Clarity Logic
            if (order.newBalance != 0.0 || order.previousBalance != 0.0) {
                binding.layoutClarity.visibility = android.view.View.VISIBLE
                binding.textPrevBalance.text = "₹ %.2f".format(order.previousBalance)
                binding.textNewBalance.text = "₹ %.2f".format(order.newBalance)
                
                val delta = order.transactionAmount
                if (delta > 0) {
                    binding.textTransAmount.text = "+ ₹ %.2f".format(delta)
                    binding.textTransAmount.setTextColor(0xFFC62828.toInt()) // Red (Debt Increase)
                    binding.labelAmount.text = "Added Debt:"
                } else if (delta < 0) {
                    binding.textTransAmount.text = "- ₹ %.2f".format(-delta)
                    binding.textTransAmount.setTextColor(0xFF2E7D32.toInt()) // Green (Settlement)
                    binding.labelAmount.text = "Reduction:"
                } else {
                    binding.textTransAmount.text = "₹ 0.00"
                    binding.textTransAmount.setTextColor(0xFF757575.toInt())
                    binding.labelAmount.text = "No Change:"
                }
            } else {
                binding.layoutClarity.visibility = android.view.View.GONE
            }

            itemView.setOnLongClickListener {
                onDeleteClick(order)
                true
            }
        }
    }
}
