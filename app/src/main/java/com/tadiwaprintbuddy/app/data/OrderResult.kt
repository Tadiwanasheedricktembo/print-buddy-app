package com.tadiwaprintbuddy.app.data

sealed class OrderResult {
    data class Success(val orderId: Int) : OrderResult()
    data class InsufficientStock(
        val itemName: String,
        val available: Int,
        val requested: Int
    ) : OrderResult()
    data class ValidationError(val message: String) : OrderResult()
    data class Error(val message: String) : OrderResult()
}
