package com.tadiwaprintbuddy.app.data

import java.util.Calendar

class PrintRepository(private val printDao: PrintDao) {

    suspend fun getTotalRevenue(): Double? = printDao.getTotalRevenue()

    suspend fun getTotalOrders(): Int = printDao.getTotalOrders()

    suspend fun getTodaysRevenue(): Double? {
        val calendar = Calendar.getInstance()
        // Set to start of today
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis

        // Set to end of today
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val end = calendar.timeInMillis

        return printDao.getRevenueBetween(start, end)
    }

    suspend fun saveOrder(order: Order, items: List<OrderItem>) {
        val orderId = printDao.insertOrder(order)
        val itemsWithOrderId = items.map { it.copy(orderId = orderId.toInt()) }
        printDao.insertOrderItems(itemsWithOrderId)
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()
}
