package com.tadiwaprintbuddy.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.databinding.ItemOrderBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

class OrdersAdapter(
    private var orders: List<Order>,
    private val onDeleteClick: (Order) -> Unit
) : RecyclerView.Adapter<OrdersAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    fun updateOrders(newOrders: List<Order>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val order = orders[position]
        
        // Handle deleted order gaps
        val previousOrder = if (position > 0) orders[position - 1] else null
        val gap = if (previousOrder != null) previousOrder.id - order.id - 1 else 0
        
        holder.bind(order, gap)
    }

    override fun getItemCount(): Int = orders.size

    inner class ViewHolder(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order, gap: Int) {
            val context = itemView.context
            
            // Gap indicator
            if (gap > 0) {
                binding.textDeletedIndicator.visibility = View.VISIBLE
                val gapText = if (gap == 1) {
                    "─── Order #${order.id + 1} deleted ───"
                } else {
                    "─── Orders #${order.id + 1} to #${order.id + gap} deleted ───"
                }
                binding.textDeletedIndicator.text = gapText
            } else {
                binding.textDeletedIndicator.visibility = View.GONE
            }

            binding.textOrderId.text = "Order #${order.id}"
            // Format currency without space: ₹500.00
            val amountStr = currencyFormat.format(order.totalAmount).replace(" ", "")
            binding.textAmount.text = amountStr
            binding.textDate.text = dateFormat.format(order.date)

            // Dynamic Icons and Badges
            when (order.paymentMethod) {
                "CASH" -> {
                    binding.imagePaymentIcon.setImageResource(R.drawable.ic_wallet)
                    binding.cardIcon.setCardBackgroundColor(Color.parseColor("#1B5E20"))
                    binding.badgePaymentMethod.text = "CASH"
                    binding.badgePaymentMethod.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1B5E20"))
                    binding.badgePaymentMethod.setTextColor(Color.parseColor("#00C853"))
                    binding.tagCreditHeader.visibility = View.GONE
                }
                "UPI" -> {
                    binding.imagePaymentIcon.setImageResource(R.drawable.ic_qr_code)
                    binding.cardIcon.setCardBackgroundColor(Color.parseColor("#0D47A1"))
                    binding.badgePaymentMethod.text = "UPI"
                    binding.badgePaymentMethod.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0D47A1"))
                    binding.badgePaymentMethod.setTextColor(Color.parseColor("#448AFF"))
                    binding.tagCreditHeader.visibility = View.GONE
                }
                else -> { // CREDIT / OWES_ME
                    binding.imagePaymentIcon.setImageResource(R.drawable.ic_history)
                    binding.cardIcon.setCardBackgroundColor(Color.parseColor("#4A1500"))
                    binding.badgePaymentMethod.text = "CREDIT"
                    binding.badgePaymentMethod.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A1500"))
                    binding.badgePaymentMethod.setTextColor(Color.parseColor("#FF5252"))
                    binding.tagCreditHeader.visibility = View.VISIBLE
                }
            }

            // Balance Clarity Logic
            if (order.newBalance != 0.0 || order.previousBalance != 0.0) {
                binding.layoutClarity.visibility = View.VISIBLE
                binding.textPrevBalance.text = currencyFormat.format(order.previousBalance).replace(" ", "")
                binding.textNewBalance.text = currencyFormat.format(order.newBalance).replace(" ", "")
                
                val delta = order.transactionAmount
                val deltaStr = currencyFormat.format(Math.abs(delta)).replace(" ", "")
                if (delta > 0) {
                    binding.textTransAmount.text = "+ $deltaStr"
                    binding.textTransAmount.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    binding.textTransAmount.text = "- $deltaStr"
                    binding.textTransAmount.setTextColor(Color.parseColor("#00C853"))
                }
            } else {
                binding.layoutClarity.visibility = View.GONE
            }

            itemView.setOnLongClickListener {
                onDeleteClick(order)
                true
            }
        }
    }
}
