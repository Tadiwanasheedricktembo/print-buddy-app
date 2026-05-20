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
    private val onUpdateClicked: (DebtorCredit) -> Unit,
    private val onItemClicked: (DebtorCredit) -> Unit,
    private val onItemLongClicked: (DebtorCredit) -> Unit
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
            val context = binding.root.context
            val locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
            val format = NumberFormat.getCurrencyInstance(locale)

            binding.textCustomerName.text = debtorCredit.customerName
            
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            binding.textLastUpdated.text = "Updated: ${sdf.format(java.util.Date(debtorCredit.lastUpdated))}"

            if (debtorCredit.amount > 0) {
                // Positive Balance = Customer owes shop
                binding.textAmount.text = format.format(debtorCredit.amount)
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_error))
                binding.buttonUpdateAmount.text = "SETTLE"
            } else if (debtorCredit.amount < 0) {
                // Negative Balance = Shop owes customer (Credit)
                binding.textAmount.text = format.format(java.lang.Math.abs(debtorCredit.amount))
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_primary))
                binding.buttonUpdateAmount.text = "ADD CREDIT"
            } else {
                // Zero Balance = Settled
                binding.textAmount.text = "Settled"
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_success))
                binding.buttonUpdateAmount.text = "NEW ENTRY"
            }

            binding.buttonUpdateAmount.setOnClickListener {
                onUpdateClicked(debtorCredit)
            }

            binding.root.setOnClickListener {
                onItemClicked(debtorCredit)
            }

            binding.root.setOnLongClickListener {
                onItemLongClicked(debtorCredit)
                true
            }
        }
    }
}
