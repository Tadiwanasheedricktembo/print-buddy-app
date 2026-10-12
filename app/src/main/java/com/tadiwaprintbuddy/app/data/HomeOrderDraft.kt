package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "home_order_draft")
data class HomeOrderDraft(
    @PrimaryKey
    val id: Int = CURRENT_ID,
    val customerName: String = "",
    val quantityInput: String = "1",
    val priceInput: String = "",
    val receivedAmount: String? = null,
    val paymentMethod: String = "CASH",
    val calculatorItemsJson: String = "[]",
    val calculatorTotalPages: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CURRENT_ID = 1
    }
}