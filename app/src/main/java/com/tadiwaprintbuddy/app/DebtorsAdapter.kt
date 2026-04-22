package com.tadiwaprintbuddy.app

import android.view.LayoutInflater
import android.view.ViewGroup
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
            val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

            binding.textCustomerName.text = debtor.customerName
            binding.textAmountOwed.text = format.format(debtor.totalBalance)

            if (debtor.type == "CHANGE") {
                binding.textAmountOwed.setTextColor(0xFF2E7D32.toInt()) // Green
                binding.textLabelOwed.text = "Change Due"
            } else {
                binding.textAmountOwed.setTextColor(0xFFC62828.toInt()) // Red
                binding.textLabelOwed.text = "Amount Owed"
            }

            binding.buttonReceivePayment.setOnClickListener {
                onReceivePaymentClicked(debtor)
            }
        }
    }
}
