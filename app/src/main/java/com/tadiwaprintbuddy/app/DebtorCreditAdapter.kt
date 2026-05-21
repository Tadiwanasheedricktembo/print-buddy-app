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

            if (debtorCredit.amount > 0) {
                // Customer owes shop
                binding.textAmount.text = format.format(debtorCredit.amount)
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_error))
                binding.textLabelStatus.text = "AMOUNT OWED"
                
                val iconBg = if (bindingAdapterPosition % 2 == 0) R.color.icon_bg_purple else R.color.icon_bg_blue
                binding.cardIcon.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, iconBg))
                binding.imageIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } else if (debtorCredit.amount < 0) {
                // Shop owes customer
                binding.textAmount.text = "-${format.format(java.lang.Math.abs(debtorCredit.amount))}"
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.change_green))
                binding.textLabelStatus.text = "CHANGE DUE"
                
                binding.cardIcon.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.icon_bg_blue))
                binding.imageIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } else {
                // Settled
                binding.textAmount.text = "Settled"
                binding.textAmount.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_success))
                binding.textLabelStatus.text = "BALANCE CLEAR"
                
                binding.cardIcon.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_surface_variant))
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
