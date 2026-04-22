package com.tadiwaprintbuddy.app.data

import com.tadiwaprintbuddy.app.CartItem
import com.tadiwaprintbuddy.app.data.DebtorCredit
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class PrintRepository(private val printDao: PrintDao) {

    fun getTotalRevenueFlow(): Flow<Double?> = printDao.getTotalRevenueFlow()

    fun getTotalOrdersFlow(): Flow<Int> = printDao.getTotalOrdersFlow()

    suspend fun getTodaysRevenue(): Double? {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val end = calendar.timeInMillis

        return printDao.getRevenueBetween(start, end)
    }

    suspend fun saveOrder(order: Order, items: List<OrderItem>) {
        val orderId = printDao.insertOrder(order)
        val itemsWithOrderId = items.map { it.copy(orderId = orderId.toInt()) }
        printDao.insertOrderItems(itemsWithOrderId)
        
        if (order.paymentMethod == "UPI" && order.paidAmount > 0) {
            insertBeautyTransaction(order.paidAmount, "ADD", "Order #${orderId} - ${order.customerName}")
        }
    }

    suspend fun confirmOrder(customerName: String, cartItems: List<CartItem>, paymentMethod: String = "CASH"): Int {
        if (cartItems.isEmpty()) return -1

        val total = cartItems.sumOf { it.price * it.quantity }
        val order = Order(
            totalAmount = total,
            date = System.currentTimeMillis(),
            customerName = customerName,
            paidAmount = total,
            paymentMethod = paymentMethod
        )

        val orderId = printDao.insertOrder(order).toInt()

        val orderItems = cartItems.map { cartItem ->
            OrderItem(
                orderId = orderId,
                serviceName = cartItem.serviceName,
                price = cartItem.price,
                quantity = cartItem.quantity
            )
        }
        printDao.insertOrderItems(orderItems)

        if (paymentMethod == "UPI") {
            insertBeautyTransaction(total, "ADD", "Direct Pay - $customerName")
        }

        return orderId
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()

    suspend fun getUnpaidOrders(): List<Order> = printDao.getUnpaidOrders()

    suspend fun updatePayment(orderId: Int, amount: Double) {
        printDao.updatePayment(orderId, amount)
    }

    suspend fun getOrdersBetween(start: Long, end: Long): List<Order> = printDao.getOrdersBetween(start, end)

    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>> = printDao.getRevenueByCategoryFlow()

    suspend fun applyPaymentToCustomer(customerName: String, paymentAmount: Double, paymentMethod: String = "CASH") {
        val currentBalance = getCustomerBalance(customerName)
        
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

        val actualSettled = paymentAmount // Total money handed over
        val newBalance = currentBalance - actualSettled

        printDao.insertSettlement(
            SettlementHistory(
                customerName = customerName,
                balanceBefore = currentBalance,
                amountPaid = actualSettled,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = "PAYMENT",
                note = "Payment via $paymentMethod"
            )
        )

        if (paymentMethod == "UPI") {
            insertBeautyTransaction(actualSettled, "ADD", "Debt Settlement - $customerName")
        }
    }

    suspend fun getCustomerBalance(customerName: String): Double {
        val allSettlements = printDao.getAllSettlementHistoryOnce()
        return allSettlements
            .filter { it.customerName.trim().equals(customerName.trim(), ignoreCase = true) }
            .maxByOrNull { it.timestamp }
            ?.balanceAfter ?: 0.0
    }

    suspend fun getCustomerSummaries(): List<DebtorSummary> {
        val all = printDao.getAllSettlementHistoryOnce()
        return all
            .groupBy { it.customerName.trim().lowercase() }
            .map { (_, transactions) ->
                val latest = transactions.maxByOrNull { it.timestamp }!!
                val balance = latest.balanceAfter
                
                DebtorSummary(
                    customerName = latest.customerName,
                    totalBalance = kotlin.math.abs(balance),
                    type = if (balance > 0) "OWES" else "CHANGE"
                )
            }
            .filter { it.totalBalance > 0.1 } // Ignore near-zero balances
    }

    suspend fun addOrUpdateDebtorCredit(customerName: String, amountDelta: Double, note: String? = null) {
        val previousBalance = getCustomerBalance(customerName)
        val newBalance = previousBalance + amountDelta

        val isPayment = amountDelta < 0
        val amount = if (isPayment) -amountDelta else amountDelta

        val finalNote = note ?: when {
            previousBalance == 0.0 && amountDelta > 0 -> "New Debt Added"
            previousBalance == 0.0 && amountDelta < 0 -> "Initial Change Recorded"
            amountDelta > 0 -> "Added to Balance"
            else -> "Settlement Payment"
        }
        
        printDao.insertSettlement(
            SettlementHistory(
                customerName = customerName,
                balanceBefore = previousBalance,
                amountPaid = if (isPayment) amount else 0.0,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = if (isPayment) "PAYMENT" else "ADJUSTMENT",
                note = finalNote
            )
        )

        // Sync with the summary table
        printDao.insertOrUpdateDebtorCredit(DebtorCredit(customerName, newBalance, System.currentTimeMillis()))
    }

    suspend fun addPrinterReference(reference: PrinterReference) = printDao.addPrinterReference(reference)

    suspend fun getAllPrinterReferences(): List<PrinterReference> = printDao.getAllPrinterReferences()

    suspend fun deletePrinterReference(reference: PrinterReference) = printDao.deletePrinterReference(reference)

    suspend fun getDebtorCreditList(): List<DebtorCredit> = printDao.getDebtorCreditList()

    suspend fun deleteOrder(orderId: Int) {
        val order = printDao.getOrderById(orderId)
        if (order != null) {
            printDao.deleteOrderAndItems(order)
        }
    }

    suspend fun deleteOrdersBetween(start: Long, end: Long) {
        printDao.deleteOrdersBetween(start, end)
    }

    suspend fun deleteAllOrders() {
        printDao.deleteAllOrders()
    }

    suspend fun getAllSettlements(): List<SettlementHistory> = printDao.getAllSettlements()

    suspend fun getAllSettlementHistoryOnce(): List<SettlementHistory> = printDao.getAllSettlementHistoryOnce()

    suspend fun restoreSettlements(data: List<SettlementHistory>, fullReplace: Boolean) {
        if (fullReplace) {
            printDao.clearSettlementHistory()
        }
        printDao.insertAllSettlements(data)
    }

    // Money Tracking Methods
    fun getCashInHandFlow(): Flow<Double?> = printDao.getCashInHandFlow()
    
    fun getTotalReceivablesFlow(): Flow<Double?> = printDao.getTotalReceivablesFlow()

    suspend fun recordMoneyReturnedFromExternal(amount: Double, note: String? = null) {
        insertBeautyTransaction(amount, "RETURN", note ?: "Money returned by Beauty Rani")
    }

    suspend fun addManualExternalCredit(amount: Double, note: String? = null) {
        insertBeautyTransaction(amount, "ADD", note ?: "Manual Entry")
    }

    // Beauty Account Logic
    suspend fun insertBeautyTransaction(amount: Double, type: String, note: String? = null) {
        printDao.insertBeautyTransaction(BeautyTransaction(amount = amount, type = type, note = note))
    }

    fun getAllBeautyTransactionsFlow(): Flow<List<BeautyTransaction>> = printDao.getAllBeautyTransactionsFlow()
    
    suspend fun getAllBeautyTransactions(): List<BeautyTransaction> = printDao.getAllBeautyTransactions()

    fun getBeautyBalanceFlow(): Flow<Double?> = printDao.getBeautyBalanceFlow()
    
    suspend fun getBeautyBalance(): Double? = printDao.getBeautyBalance()

    suspend fun getCurrentBeautyBalance(): Double = printDao.getCurrentBeautyBalance()

    // Compatibility methods for External Ledger
    suspend fun getExternalBalance(): Double? = getBeautyBalance()
    suspend fun getAllExternalLedgerEntries(): List<BeautyTransaction> = getAllBeautyTransactions()
}
