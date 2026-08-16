package com.tadiwaprintbuddy.app

data class PrintBatch(
    val id: String,
    val description: String,
    val quantity: Int,
    val unitPrice: Double,
    val totalPrice: Double
)
