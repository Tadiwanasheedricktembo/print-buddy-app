package com.tadiwaprintbuddy.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.Expense
import com.tadiwaprintbuddy.app.databinding.ItemExpenseBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExpenseAdapter(
    private var expenses: List<Expense>,
    private val onLongClick: (Expense) -> Unit
) : RecyclerView.Adapter<ExpenseAdapter.ViewHolder>() {

    fun updateExpenses(newExpenses: List<Expense>) {
        expenses = newExpenses
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExpenseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(expenses[position])
    }

    override fun getItemCount(): Int = expenses.size

    inner class ViewHolder(private val binding: ItemExpenseBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(expense: Expense) {
            val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
            val format = NumberFormat.getCurrencyInstance(locale)
            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

            binding.textCategory.text = expense.category.name
            binding.textNote.text = expense.title // Use title as primary info
            binding.textAmount.text = format.format(expense.amount.toDouble())
            binding.textDate.text = dateFormat.format(Date(expense.timestamp))
            binding.textPaymentMethod.text = expense.paymentMethod

            binding.root.setOnLongClickListener {
                onLongClick(expense)
                true
            }
        }
    }
}
