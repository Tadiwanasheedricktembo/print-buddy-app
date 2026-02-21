package com.tadiwaprintbuddy.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PrintDao {

    @Insert
    suspend fun insertOrder(order: Order): Long

    @Insert
    suspend fun insertOrderItems(items: List<OrderItem>)

    @Query("SELECT * FROM orders ORDER BY date DESC")
    suspend fun getAllOrders(): List<Order>

    @Query("SELECT * FROM OrderItem WHERE orderId = :orderId")
    suspend fun getItemsForOrder(orderId: Int): List<OrderItem>

    @Query("SELECT SUM(totalAmount) FROM `orders`")
    suspend fun getTotalRevenue(): Double?

    @Query("SELECT COUNT(*) FROM `orders`")
    suspend fun getTotalOrders(): Int

    @Query("""
SELECT SUM(totalAmount)
FROM `orders`
WHERE date BETWEEN :start AND :end
""")
    suspend fun getRevenueBetween(start: Long, end: Long): Double?
}
