package com.tadiwaprintbuddy.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.DebtorCredit
import com.tadiwaprintbuddy.app.databinding.ItemDebtorCreditBinding
import java.text.NumberFormat
import java.util.Locale

class DebtorCreditAdapter(
    private var debtorCredits: List<DebtorCredit>,
    private val onUpdateClicked: (DebtorCredit) -> Unit
) : RecyclerView.Adapter<DebtorCreditAdapter.ViewHolder>() {

    fun updateDebtorCredits(newDebtorCredits: List<DebtorCredit>) {
        debtorCredits = newDebtorCredits
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDebtorCreditBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(debtorCredits[position])
    }

    override fun getItemCount(): Int = debtorCredits.size

    inner class ViewHolder(private val binding: ItemDebtorCreditBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(debtorCredit: DebtorCredit) {
            val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

            binding.textCustomerName.text = debtorCredit.customerName

            if (debtorCredit.amount > 0) {
                binding.textAmount.text = "Owes: ${format.format(debtorCredit.amount)}"
                binding.textAmount.setTextColor(Color.RED)
            } else {
                binding.textAmount.text = "Change Due: ${format.format(-debtorCredit.amount)}"
                binding.textAmount.setTextColor(Color.GREEN)
            }

            binding.buttonUpdateAmount.setOnClickListener {
                onUpdateClicked(debtorCredit)
            }
        }
    }
}
