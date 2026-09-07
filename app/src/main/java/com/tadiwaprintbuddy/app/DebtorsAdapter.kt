package com.tadiwaprintbuddy.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.DebtorSummary
import com.tadiwaprintbuddy.app.databinding.ItemDebtorBinding
import java.text.NumberFormat
import java.util.Locale

class DebtorsAdapter(
    private var debtors: List<DebtorSummary>,
    private val onReceivePaymentClicked: (DebtorSummary) -> Unit
) : RecyclerView.Adapter<DebtorsAdapter.ViewHolder>() {

    fun updateDebtors(newDebtors: List<DebtorSummary>) {
        debtors = newDebtors
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDebtorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(debtors[position])
    }

    override fun getItemCount(): Int = debtors.size

    inner class ViewHolder(private val binding: ItemDebtorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(debtor: DebtorSummary) {
            val context = binding.root.context
            val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
            val format = NumberFormat.getCurrencyInstance(locale)

            binding.textCustomerName.text = debtor.customerName.uppercase(Locale.getDefault())

            if (debtor.type == "CHANGE") {
                val absBalance = debtor.totalBalance.abs()
                val amountText = "-${format.format(absBalance.toDouble())}"
                binding.textAmountOwed.text = amountText
                binding.textAmountOwed.setTextColor(ContextCompat.getColor(context, R.color.primary_accent))
                binding.textLabelOwed.text = "CHANGE DUE"
                binding.cardIcon.setCardBackgroundColor(ContextCompat.getColor(context, R.color.icon_bg_blue))
            } else {
                binding.textAmountOwed.text = format.format(debtor.totalBalance.toDouble())
                binding.textAmountOwed.setTextColor(ContextCompat.getColor(context, R.color.destructive))
                binding.textLabelOwed.text = "AMOUNT OWED"
                
                val iconBg = if (bindingAdapterPosition % 2 == 0) R.color.icon_bg_purple else R.color.icon_bg_blue
                binding.cardIcon.setCardBackgroundColor(ContextCompat.getColor(context, iconBg))
            }

            binding.buttonReceivePayment.setOnClickListener {
                onReceivePaymentClicked(debtor)
            }
        }
    }
}
