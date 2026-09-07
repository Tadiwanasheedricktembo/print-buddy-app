package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Expense
import com.tadiwaprintbuddy.app.data.ExpenseCategory
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.BottomSheetAddExpenseBinding
import kotlinx.coroutines.launch
import java.math.BigDecimal

class AddExpenseBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddExpenseBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAddExpenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repository = PrintRepository(AppDatabase.getDatabase(requireContext()).printDao())

        binding.btnSaveExpense.setOnClickListener {
            val title = binding.editExpenseTitle.text.toString()
            val amountStr = binding.editExpenseAmount.text.toString()
            val amount = try { BigDecimal(amountStr) } catch (e: Exception) { null }
            
            if (title.isBlank() || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                Toast.makeText(requireContext(), "Please enter valid details", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val checkedChipId = binding.chipGroupCategory.checkedChipId
            val categoryStr = if (checkedChipId != View.NO_ID) {
                val chip = binding.chipGroupCategory.findViewById<Chip>(checkedChipId)
                chip.text.toString()
            } else "Miscellaneous"

            val finalCategory = when(categoryStr) {
                "Paper" -> ExpenseCategory.PAPER
                "Ink" -> ExpenseCategory.INK
                "Electricity" -> ExpenseCategory.ELECTRICITY
                "Maintenance" -> ExpenseCategory.MAINTENANCE
                else -> ExpenseCategory.MISCELLANEOUS
            }

            lifecycleScope.launch {
                repository.insertExpense(
                    Expense(
                        title = title,
                        category = finalCategory,
                        amount = amount
                    )
                )
                Toast.makeText(requireContext(), "Expense saved", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
