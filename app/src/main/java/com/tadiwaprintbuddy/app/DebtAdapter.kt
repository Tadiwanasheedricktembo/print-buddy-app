package com.tadiwaprintbuddy.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.databinding.ItemDebtBinding
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

class DebtAdapter(
    private var orders: List<Order>,
    private val onUpdatePaymentClicked: (Order) -> Unit
) : RecyclerView.Adapter<DebtAdapter.ViewHolder>() {

    fun updateDebts(newOrders: List<Order>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDebtBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(orders[position])
    }

    override fun getItemCount(): Int = orders.size

    inner class ViewHolder(private val binding: ItemDebtBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: Order) {
            val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
            val format = NumberFormat.getCurrencyInstance(locale)
            val remainingAmount = order.totalAmount.subtract(order.paidAmount)

            binding.textCustomerName.text = order.customerName
            binding.textOrderId.text = "Order #${order.id}"
            binding.textTotalAmount.text = "Total: ${format.format(order.totalAmount.toDouble())}"
            binding.textPaidAmount.text = "Paid: ${format.format(order.paidAmount.toDouble())}"
            binding.textRemainingAmount.text = "Remaining: ${format.format(remainingAmount.toDouble())}"

            binding.buttonUpdatePayment.setOnClickListener {
                onUpdatePaymentClicked(order)
            }

            if (order.newBalance.compareTo(BigDecimal.ZERO) != 0 || order.previousBalance.compareTo(BigDecimal.ZERO) != 0) {
                binding.layoutClarity.visibility = View.VISIBLE
                binding.textBalanceSnapshot.text = "Snapshot: ${format.format(order.previousBalance.toDouble())} → ${format.format(order.newBalance.toDouble())}"
            } else {
                binding.layoutClarity.visibility = View.GONE
            }
        }
    }
}
