package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tadiwaprintbuddy.app.databinding.BottomSheetPaymentBinding
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

class PaymentBottomSheet(private val onConfirm: (String, String, BigDecimal?) -> Unit) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPaymentBinding? = null
    private val binding get() = _binding!!
    private var isPaidSelected = true
    
    private val viewModel: MainViewModel by activityViewModels()
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())

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

        setupUI()
        observeViewModel()
        
        // Pre-fill tender if available from calculator
        val received = viewModel.uiState.value.receivedAmount
        if (received != null && received.compareTo(BigDecimal.ZERO) > 0) {
            binding.editAmountReceived.setText(received.toPlainString())
        }
    }

    private fun setupUI() {
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

        binding.toggleGroupPaymentMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                updateTenderVisibility()
            }
        }

        binding.editAmountReceived.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val amountStr = s?.toString()
                val amount = try { BigDecimal(amountStr) } catch (e: Exception) { null }
                viewModel.onReceivedAmountChanged(amount)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnConfirmPayment.setOnClickListener {
            val status = if (isPaidSelected) "Paid" else "Credit"
            val method = if (isPaidSelected) {
                if (binding.toggleGroupPaymentMethod.checkedButtonId == R.id.btnCash) "CASH" else "UPI"
            } else {
                "OWES_ME"
            }
            
            val receivedAmount = if (isPaidSelected && method == "CASH") {
                val str = binding.editAmountReceived.text.toString()
                try { BigDecimal(str) } catch (e: Exception) { null }
            } else if (isPaidSelected && method == "UPI") {
                viewModel.uiState.value.balanceToPay
            } else null

            onConfirm(status, method, receivedAmount)
            dismiss()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.textChangeAmount.text = currencyFormat.format(state.changeAmount.toDouble())
                }
            }
        }
    }

    private fun updateTenderVisibility() {
        val isCash = binding.toggleGroupPaymentMethod.checkedButtonId == R.id.btnCash
        binding.layoutTender.visibility = if (isPaidSelected && isCash) View.VISIBLE else View.GONE
        
        // Reset tender if hidden
        if (binding.layoutTender.visibility == View.GONE) {
            binding.editAmountReceived.setText("")
            viewModel.onReceivedAmountChanged(null)
        }
    }

    private fun updateSelectionUi() {
        val primary = ContextCompat.getColor(requireContext(), R.color.primary_accent)
        val divider = ContextCompat.getColor(requireContext(), R.color.border_divider)
        val surface = ContextCompat.getColor(requireContext(), R.color.surface_card)
        val container = ContextCompat.getColor(requireContext(), R.color.primary_container)

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
        updateTenderVisibility()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PaymentBottomSheet"
    }
}
