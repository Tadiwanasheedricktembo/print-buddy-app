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

            binding.textCustomerName.text = debtorCredit.customerName.uppercase(Locale.getDefault())
            
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            binding.textLastUpdated.text = "Updated: ${sdf.format(java.util.Date(debtorCredit.lastUpdated))}"

            val absAmount = java.lang.Math.abs(debtorCredit.amount)
            binding.textAmount.text = format.format(absAmount)

            if (debtorCredit.amount > 0) {
                // Customer owes shop
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.destructive))
                binding.textLabelStatus.text = "AMOUNT OWED"
                binding.buttonUpdateAmount.text = "Receive Payment"
                binding.buttonUpdateAmount.setIconResource(R.drawable.ic_wallet)
                
                val iconBg = if (bindingAdapterPosition % 2 == 0) R.color.icon_bg_purple else R.color.icon_bg_blue
                binding.cardIcon.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, iconBg))
                binding.imageIcon.setImageResource(R.drawable.ic_person)
                binding.imageIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } else if (debtorCredit.amount < 0) {
                // Shop owes customer
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.warning))
                binding.textLabelStatus.text = "CHANGE DUE"
                binding.buttonUpdateAmount.text = "Settle Change"
                binding.buttonUpdateAmount.setIconResource(R.drawable.ic_history) 
                
                binding.cardIcon.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.header_amber))
                binding.imageIcon.setImageResource(R.drawable.ic_history)
                binding.imageIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } else {
                // Settled
                binding.textAmount.text = "Settled"
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.primary_accent))
                binding.textLabelStatus.text = "BALANCE CLEAR"
                binding.buttonUpdateAmount.text = "New Entry"
                binding.buttonUpdateAmount.setIconResource(R.drawable.ic_check)
                
                binding.cardIcon.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.surface_elevated))
                binding.imageIcon.setImageResource(R.drawable.ic_person)
                binding.imageIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
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
