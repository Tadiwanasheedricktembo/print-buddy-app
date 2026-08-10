package com.tadiwaprintbuddy.app

import java.text.NumberFormat
import java.util.Locale

object PaymentReminderUtils {
    
    fun generateReminderMessage(customerName: String, outstandingAmount: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())
        val amountStr = format.format(outstandingAmount)
        
        return """
            Hi $customerName,

            This is a friendly reminder that you have an outstanding balance of $amountStr with Tadiwa Print Buddy.

            Kindly arrange for the payment at your earliest convenience. If you have already paid, please ignore this message.

            Thank you!
        """.trimIndent()
    }
}
