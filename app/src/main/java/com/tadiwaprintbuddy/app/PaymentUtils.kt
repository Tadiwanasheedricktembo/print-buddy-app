package com.tadiwaprintbuddy.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder

object PaymentUtils {

    const val MERCHANT_UPI_ID = "9217011636@ptaxis"
    const val MERCHANT_NAME = "Beauty Rani"

    fun generateUpiUri(amount: Double? = null, orderId: String? = null): String {
        val encodedName = URLEncoder.encode(MERCHANT_NAME, "UTF-8")
        val baseUri = "upi://pay?pa=$MERCHANT_UPI_ID&pn=$encodedName&cu=INR"
        
        val amountPart = if (amount != null && amount > 0) {
            "&am=${String.format("%.2f", amount)}"
        } else {
            ""
        }
        
        val notePart = if (!orderId.isNullOrEmpty()) {
            "&tn=${URLEncoder.encode("Order #$orderId", "UTF-8")}"
        } else {
            ""
        }
        
        return "$baseUri$amountPart$notePart"
    }

    fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
