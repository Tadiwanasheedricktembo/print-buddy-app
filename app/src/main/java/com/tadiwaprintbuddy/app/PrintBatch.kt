package com.tadiwaprintbuddy.app

import java.math.BigDecimal

data class PrintBatch(
    val id: String,
    val description: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val totalPrice: BigDecimal
)
