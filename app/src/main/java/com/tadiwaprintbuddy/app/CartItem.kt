// In a file like app/src/main/java/com/tadiwaprintbuddy/app/CartItem.kt

package com.tadiwaprintbuddy.app

import java.io.Serializable

data class CartItem(
    val serviceName: String,
    val price: Double,
    var quantity: Int
) : Serializable {
    fun getSubtotal(): Double {
        return price * quantity
    }
}
