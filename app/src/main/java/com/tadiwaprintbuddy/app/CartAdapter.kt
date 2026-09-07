package com.tadiwaprintbuddy.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.databinding.ItemCartBinding
import java.math.BigDecimal

class CartAdapter(
    private val items: MutableList<CartItem>,
    private val listener: OnCartChangedListener
) : RecyclerView.Adapter<CartAdapter.ViewHolder>() {

    interface OnCartChangedListener {
        fun onCartUpdated(total: BigDecimal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCartBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun getTotal(): BigDecimal {
        var total = BigDecimal.ZERO
        for (item in items) {
            total = total.add(item.getSubtotal())
        }
        return total
    }

    private fun notifyTotalChanged() {
        listener.onCartUpdated(getTotal())
    }

    inner class ViewHolder(private val binding: ItemCartBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CartItem) {
            binding.textCartServiceName.text = item.serviceName
            binding.textCartPrice.text = "₹ %.2f".format(item.price)
            binding.textCartQuantity.text = item.quantity.toString()
            binding.textCartSubtotal.text = "Subtotal: ₹ %.2f".format(item.getSubtotal())

            binding.buttonPlus.setOnClickListener {
                item.quantity++
                binding.textCartQuantity.text = item.quantity.toString()
                binding.textCartSubtotal.text = "Subtotal: ₹ %.2f".format(item.getSubtotal())
                notifyTotalChanged()
            }

            binding.buttonMinus.setOnClickListener {
                if (item.quantity > 1) {
                    item.quantity--
                    binding.textCartQuantity.text = item.quantity.toString()
                    binding.textCartSubtotal.text = "Subtotal: ₹ %.2f".format(item.getSubtotal())
                    notifyTotalChanged()
                }
            }
        }
    }
}
