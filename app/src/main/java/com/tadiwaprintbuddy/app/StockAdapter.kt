package com.tadiwaprintbuddy.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.StockItem
import com.tadiwaprintbuddy.app.databinding.ItemStockBinding

class StockAdapter(private val onItemClick: (StockItem) -> Unit) :
    ListAdapter<StockItem, StockAdapter.StockViewHolder>(StockDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StockViewHolder {
        val binding = ItemStockBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StockViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StockViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StockViewHolder(private val binding: ItemStockBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StockItem) {
            binding.textItemName.text = item.name
            binding.textQuantity.text = item.currentQuantity.toString()
            binding.textStockStatus.text = binding.root.context.getString(R.string.remaining_format, item.currentQuantity, item.unit)

            if (item.currentQuantity <= item.lowStockThreshold) {
                binding.textQuantity.setTextColor(ContextCompat.getColor(binding.root.context, R.color.brand_error))
                binding.textStockStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.brand_error))
            } else {
                binding.textQuantity.setTextColor(ContextCompat.getColor(binding.root.context, R.color.brand_primary))
                binding.textStockStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_secondary))
            }

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    class StockDiffCallback : DiffUtil.ItemCallback<StockItem>() {
        override fun areItemsTheSame(oldItem: StockItem, newItem: StockItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: StockItem, newItem: StockItem): Boolean {
            return oldItem == newItem
        }
    }
}
