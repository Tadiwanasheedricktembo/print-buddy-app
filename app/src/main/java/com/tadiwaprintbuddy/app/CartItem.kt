package com.tadiwaprintbuddy.app

import java.io.Serializable
import java.math.BigDecimal

data class CartItem(
    val serviceName: String,
    val price: BigDecimal,
    var quantity: Int
) : Serializable {
    fun getSubtotal(): BigDecimal {
        return price.multiply(BigDecimal(quantity))
    }
}
