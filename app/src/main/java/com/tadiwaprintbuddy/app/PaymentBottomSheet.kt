package com.tadiwaprintbuddy.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tadiwaprintbuddy.app.databinding.BottomSheetPaymentBinding

class PaymentBottomSheet(private val onConfirm: (String, String) -> Unit) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPaymentBinding? = null
    private val binding get() = _binding!!
    private var isPaidSelected = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initial State
        updateSelectionUi()
        binding.toggleGroupPaymentMethod.check(R.id.btnCash)

        binding.cardStatusPaid.setOnClickListener {
            isPaidSelected = true
            updateSelectionUi()
        }

        binding.cardStatusCredit.setOnClickListener {
            isPaidSelected = false
            updateSelectionUi()
        }

        binding.btnConfirmPayment.setOnClickListener {
            val status = if (isPaidSelected) "Paid" else "Owes Me"
            val method = if (isPaidSelected) {
                if (binding.toggleGroupPaymentMethod.checkedButtonId == R.id.btnCash) "CASH" else "UPI"
            } else {
                "OWES_ME"
            }
            onConfirm(status, method)
            dismiss()
        }
    }

    private fun updateSelectionUi() {
        val primary = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        val divider = ContextCompat.getColor(requireContext(), R.color.brand_divider)
        val surface = ContextCompat.getColor(requireContext(), R.color.brand_surface)
        val container = ContextCompat.getColor(requireContext(), R.color.primary_container)
        val textSecondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        if (isPaidSelected) {
            binding.cardStatusPaid.strokeColor = primary
            binding.cardStatusPaid.strokeWidth = 6
            binding.cardStatusPaid.setCardBackgroundColor(container)
            
            binding.cardStatusCredit.strokeColor = divider
            binding.cardStatusCredit.strokeWidth = 2
            binding.cardStatusCredit.setCardBackgroundColor(surface)
            
            binding.layoutPaymentMethod.visibility = View.VISIBLE
        } else {
            binding.cardStatusCredit.strokeColor = primary
            binding.cardStatusCredit.strokeWidth = 6
            binding.cardStatusCredit.setCardBackgroundColor(container)
            
            binding.cardStatusPaid.strokeColor = divider
            binding.cardStatusPaid.strokeWidth = 2
            binding.cardStatusPaid.setCardBackgroundColor(surface)
            
            binding.layoutPaymentMethod.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PaymentBottomSheet"
    }
}
