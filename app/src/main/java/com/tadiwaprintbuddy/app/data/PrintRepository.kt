package com.tadiwaprintbuddy.app.data

import com.tadiwaprintbuddy.app.CartItem
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

    suspend fun confirmOrder(customerName: String, cartItems: List<CartItem>) {
        if (cartItems.isEmpty()) return

        val total = cartItems.sumOf { it.price * it.quantity }
        val order = Order(
            totalAmount = total,
            date = System.currentTimeMillis(),
            customerName = customerName
        )

        val orderId = printDao.insertOrder(order)

        val orderItems = cartItems.map { cartItem ->
            OrderItem(
                orderId = orderId.toInt(),
                serviceName = cartItem.serviceName,
                price = cartItem.price,
                quantity = cartItem.quantity
            )
        }
        printDao.insertOrderItems(orderItems)
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()

    suspend fun getRevenueByCategory(): List<CategoryRevenue> = printDao.getRevenueByCategory()

    suspend fun getRevenueLast7Days(sevenDaysAgo: Long): List<DailyRevenue> = printDao.getRevenueLast7Days(sevenDaysAgo)

    suspend fun getDebtors(): List<DebtorSummary> = printDao.getDebtors()

    suspend fun applyPaymentToCustomer(customerName: String, paymentAmount: Double) {
        val unpaidOrders = printDao.getUnpaidOrdersForCustomer(customerName)
        var remainingPayment = paymentAmount

        for (order in unpaidOrders) {
            if (remainingPayment <= 0) break

            val amountOwed = order.totalAmount - order.paidAmount
            val paymentForThisOrder = if (remainingPayment >= amountOwed) amountOwed else remainingPayment

            val newPaidAmount = order.paidAmount + paymentForThisOrder
            printDao.updatePayment(order.id, newPaidAmount)

            remainingPayment -= paymentForThisOrder
        }
    }

    suspend fun getUnpaidOrders(): List<Order> = printDao.getUnpaidOrders()

    suspend fun updatePayment(orderId: Int, newPaidAmount: Double) = printDao.updatePayment(orderId, newPaidAmount)

    suspend fun addPhoto(photo: Photo) = printDao.addPhoto(photo)

    suspend fun getPhotosForOrder(orderId: Int): List<Photo> = printDao.getPhotosForOrder(orderId)

    suspend fun getDebtorCreditList(): List<DebtorCredit> = printDao.getDebtorCreditList()

    suspend fun addOrUpdateDebtorCredit(customerName: String, amountDelta: Double) {
        val existingEntry = printDao.getDebtorCreditByName(customerName)
        if (existingEntry != null) {
            val newAmount = existingEntry.amount + amountDelta
            if (newAmount == 0.0) {
                printDao.deleteDebtorCredit(customerName)
            } else {
                printDao.insertOrUpdateDebtorCredit(existingEntry.copy(amount = newAmount))
            }
        } else {
            if (amountDelta != 0.0) {
                printDao.insertOrUpdateDebtorCredit(DebtorCredit(customerName, amountDelta))
            }
        }
    }

    suspend fun addPrinterReference(reference: PrinterReference) = printDao.addPrinterReference(reference)

    suspend fun getAllPrinterReferences(): List<PrinterReference> = printDao.getAllPrinterReferences()

    suspend fun deletePrinterReference(reference: PrinterReference) = printDao.deletePrinterReference(reference)
}
