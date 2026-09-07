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
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

class OrdersAdapter(
    private var orders: List<Order>,
    private val onDeleteClick: (Order) -> Unit
) : RecyclerView.Adapter<OrdersAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    private val currencyFormat: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    }

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
        holder.bind(order)
    }

    override fun getItemCount(): Int = orders.size

    inner class ViewHolder(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            val context = itemView.context
            
            binding.textDeletedIndicator.visibility = View.GONE

            binding.textOrderId.text = "Order #${order.id}"
            val amountStr = currencyFormat.format(order.totalAmount.toDouble()).replace(" ", "")
            binding.textAmount.text = amountStr
            binding.textDate.text = dateFormat.format(order.date)

            // Order Status Overlay
            if (order.orderStatus == "CANCELLED") {
                binding.root.alpha = 0.5f
            } else {
                binding.root.alpha = 1.0f
            }

            // Dynamic Icons and Badges based on restored payment semantics
            val methodDisplay = when (order.paymentMethod) {
                "CASH", "CASH_MIXED" -> "CASH"
                "UPI", "UPI_MIXED" -> "UPI"
                "MIXED" -> "MIXED"
                "NONE", "CREDIT" -> "CREDIT"
                else -> order.paymentMethod
            }

            when (order.paymentMethod) {
                "CASH", "CASH_MIXED", "MIXED" -> {
                    binding.imagePaymentIcon.setImageResource(R.drawable.ic_wallet)
                    binding.cardIcon.setCardBackgroundColor(Color.parseColor("#1B5E20"))
                    binding.badgePaymentMethod.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1B5E20"))
                    binding.badgePaymentMethod.setTextColor(Color.parseColor("#00C853"))
                    binding.tagCreditHeader.visibility = View.GONE
                }
                "UPI", "UPI_MIXED" -> {
                    binding.imagePaymentIcon.setImageResource(R.drawable.ic_qr_code)
                    binding.cardIcon.setCardBackgroundColor(Color.parseColor("#0D47A1"))
                    binding.badgePaymentMethod.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0D47A1"))
                    binding.badgePaymentMethod.setTextColor(Color.parseColor("#448AFF"))
                    binding.tagCreditHeader.visibility = View.GONE
                }
                else -> { // NONE / CREDIT / OTHERS
                    binding.imagePaymentIcon.setImageResource(R.drawable.ic_history)
                    binding.cardIcon.setCardBackgroundColor(Color.parseColor("#4A1500"))
                    binding.badgePaymentMethod.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A1500"))
                    binding.badgePaymentMethod.setTextColor(Color.parseColor("#FF5252"))
                    binding.tagCreditHeader.visibility = View.VISIBLE
                }
            }

            if (order.orderStatus == "CANCELLED") {
                binding.badgePaymentMethod.text = "CANCELLED"
                binding.badgePaymentMethod.backgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
                binding.badgePaymentMethod.setTextColor(Color.WHITE)
            } else {
                binding.badgePaymentMethod.text = methodDisplay
            }

            // Balance Clarity Logic
            if (order.newBalance.compareTo(BigDecimal.ZERO) != 0 || order.previousBalance.compareTo(BigDecimal.ZERO) != 0) {
                binding.layoutClarity.visibility = View.VISIBLE
                binding.textPrevBalance.text = currencyFormat.format(order.previousBalance.toDouble()).replace(" ", "")
                binding.textNewBalance.text = currencyFormat.format(order.newBalance.toDouble()).replace(" ", "")
                
                val delta = order.transactionAmount
                val deltaStr = currencyFormat.format(delta.abs().toDouble()).replace(" ", "")
                if (delta.compareTo(BigDecimal.ZERO) > 0) {
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
