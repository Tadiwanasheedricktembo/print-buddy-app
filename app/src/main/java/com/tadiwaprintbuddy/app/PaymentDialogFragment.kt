package com.tadiwaprintbuddy.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tadiwaprintbuddy.app.databinding.DialogPaymentQrBinding
import java.text.NumberFormat
import java.util.Locale

class PaymentDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogPaymentQrBinding? = null
    private val binding get() = _binding!!

    private var amount: Double? = null
    private var orderId: Int? = null
    private var isGeneralQr: Boolean = false
    private var onPaymentConfirmed: (() -> Unit)? = null

    companion object {
        private const val ARG_AMOUNT = "amount"
        private const val ARG_ORDER_ID = "order_id"
        private const val ARG_IS_GENERAL = "is_general"

        fun newInstance(amount: Double? = null, orderId: Int? = null, isGeneral: Boolean = false): PaymentDialogFragment {
            val fragment = PaymentDialogFragment()
            val args = Bundle()
            amount?.let { args.putDouble(ARG_AMOUNT, it) }
            orderId?.let { args.putInt(ARG_ORDER_ID, it) }
            args.putBoolean(ARG_IS_GENERAL, isGeneral)
            fragment.arguments = args
            return fragment
        }
    }

    fun setOnPaymentConfirmedListener(listener: () -> Unit) {
        onPaymentConfirmed = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments?.containsKey(ARG_AMOUNT) == true) {
            amount = arguments?.getDouble(ARG_AMOUNT)
        }
        if (arguments?.containsKey(ARG_ORDER_ID) == true) {
            orderId = arguments?.getInt(ARG_ORDER_ID)
        }
        isGeneralQr = arguments?.getBoolean(ARG_IS_GENERAL) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPaymentQrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textMerchantName.text = PaymentUtils.MERCHANT_NAME
        binding.textUpiId.text = PaymentUtils.MERCHANT_UPI_ID

        val currentAmount = amount ?: 0.0
        if (currentAmount > 0) {
            val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            binding.textAmount.text = format.format(currentAmount)
            binding.textAmount.visibility = View.VISIBLE
        } else {
            binding.textAmount.visibility = View.GONE
        }

        // If it's a general QR from home bar, we don't need "Mark as Paid"
        if (isGeneralQr) {
            binding.buttonMarkAsPaid.visibility = View.GONE
        } else {
            binding.buttonMarkAsPaid.visibility = View.VISIBLE
        }

        val upiUri = PaymentUtils.generateUpiUri(amount, orderId?.toString())
        val qrBitmap = PaymentUtils.generateQrCode(upiUri)
        
        if (qrBitmap != null) {
            binding.imageQrCode.setImageBitmap(qrBitmap)
        }

        binding.buttonOpenUpi.setOnClickListener {
            openUpiApp(upiUri)
        }

        binding.buttonMarkAsPaid.setOnClickListener {
            onPaymentConfirmed?.invoke()
            dismiss()
        }

        binding.buttonClose.setOnClickListener {
            dismiss()
        }
    }

    private fun openUpiApp(uriString: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(uriString)
        try {
            val chooser = Intent.createChooser(intent, "Pay with...")
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No UPI app found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
