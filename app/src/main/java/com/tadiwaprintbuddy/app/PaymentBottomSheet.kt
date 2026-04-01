package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tadiwaprintbuddy.app.databinding.BottomSheetPaymentBinding

class PaymentBottomSheet(private val onConfirm: (String, String) -> Unit) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPaymentBinding? = null
    private val binding get() = _binding!!

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

        binding.toggleGroupPaymentStatus.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnCredit) {
                    binding.layoutPaymentMethod.visibility = View.GONE
                    binding.textCreditHint.visibility = View.VISIBLE
                } else {
                    binding.layoutPaymentMethod.visibility = View.VISIBLE
                    binding.textCreditHint.visibility = View.GONE
                }
            }
        }

        binding.btnConfirmPayment.setOnClickListener {
            val status = if (binding.toggleGroupPaymentStatus.checkedButtonId == R.id.btnPaid) "Paid" else "Owes Me"
            val method = if (status == "Paid") {
                if (binding.toggleGroupPaymentMethod.checkedButtonId == R.id.btnCash) "CASH" else "UPI"
            } else {
                "CASH" // Default for credit
            }
            onConfirm(status, method)
            dismiss()
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
