package com.tadiwaprintbuddy.app.data

import com.tadiwaprintbuddy.app.CartItem
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class PrintRepository(private val printDao: PrintDao) {

    // --- Customer Management ---

    private suspend fun getOrCreateCustomer(name: String): CustomerEntity {
        val trimmedName = name.trim()
        val normalized = trimmedName.lowercase()
        return printDao.getCustomerByNormalizedName(normalized) ?: run {
            val newCustomer = CustomerEntity(
                displayName = trimmedName,
                normalizedName = normalized
            )
            val id = printDao.insertCustomer(newCustomer)
            newCustomer.copy(id = id)
        }
    }

    suspend fun getCustomerById(id: Long) = printDao.getCustomerById(id)

    fun getAllCustomersFlow(): Flow<List<CustomerEntity>> = printDao.getAllCustomersFlow()

    // --- Orders ---

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
        val customer = getOrCreateCustomer(order.customerName)
        
        // Capture balance snapshots
        val previousBalance = getCustomerBalanceById(customer.id)
        val transactionAmount = order.totalAmount - order.paidAmount
        val newBalance = previousBalance + transactionAmount

        val orderWithSnapshots = order.copy(
            customerId = customer.id,
            previousBalance = previousBalance,
            transactionAmount = transactionAmount,
            newBalance = newBalance
        )
        
        val orderId = printDao.insertOrder(orderWithSnapshots)
        val itemsWithOrderId = items.map { it.copy(orderId = orderId.toInt()) }
        printDao.insertOrderItems(itemsWithOrderId)
        
        if (orderWithSnapshots.paymentMethod == "UPI" && orderWithSnapshots.paidAmount > 0) {
            insertBeautyTransaction(orderWithSnapshots.paidAmount, "ADD", "Order #${orderId} - ${customer.displayName}")
        }
    }

    suspend fun confirmOrder(customerName: String, cartItems: List<CartItem>, paymentMethod: String = "CASH"): Int {
        if (cartItems.isEmpty()) return -1

        val customer = getOrCreateCustomer(customerName)
        val total = cartItems.sumOf { it.price * it.quantity }
        
        val finalPaidAmount = if (paymentMethod == "OWES_ME") 0.0 else total
        val finalPaymentMethod = if (paymentMethod == "OWES_ME") "CASH" else paymentMethod

        // Capture balance snapshots
        val previousBalance = getCustomerBalanceById(customer.id)
        val transactionAmount = total - finalPaidAmount
        val newBalance = previousBalance + transactionAmount

        val order = Order(
            totalAmount = total,
            date = System.currentTimeMillis(),
            customerName = customer.displayName,
            customerId = customer.id,
            paidAmount = finalPaidAmount,
            paymentMethod = finalPaymentMethod,
            previousBalance = previousBalance,
            transactionAmount = transactionAmount,
            newBalance = newBalance
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

        if (finalPaymentMethod == "UPI" && finalPaidAmount > 0) {
            insertBeautyTransaction(finalPaidAmount, "ADD", "Direct Pay - ${customer.displayName}")
        }

        return orderId
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()

    suspend fun getUnpaidOrders(): List<Order> = printDao.getUnpaidOrders()

    suspend fun updatePayment(orderId: Int, amount: Double) {
        // Note: this method updates an existing order. 
        // For full transaction clarity, we might want to update the order's snapshots too,
        // but adding payment is usually reflected in SettlementHistory separately if via applyPayment.
        // If it's a direct updatePayment, we just update the paidAmount.
        printDao.updatePayment(orderId, amount)
    }

    suspend fun getOrdersBetween(start: Long, end: Long): List<Order> = printDao.getOrdersBetween(start, end)

    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>> = printDao.getRevenueByCategoryFlow()

    // --- Debt & Settlements ---

    suspend fun applyPaymentToCustomer(customerName: String, paymentAmount: Double, paymentMethod: String = "CASH") {
        val customer = getOrCreateCustomer(customerName)
        applyPaymentToCustomerId(customer.id, paymentAmount, paymentMethod)
    }

    suspend fun applyPaymentToCustomerId(customerId: Long, paymentAmount: Double, paymentMethod: String = "CASH") {
        val customer = printDao.getCustomerById(customerId) ?: return
        val currentBalance = getCustomerBalanceById(customer.id)
        
        val unpaidOrders = printDao.getUnpaidOrdersForCustomer(customer.id)
        var remainingPayment = paymentAmount

        for (order in unpaidOrders) {
            if (remainingPayment <= 0) break

            val amountOwed = order.totalAmount - order.paidAmount
            val paymentForThisOrder = if (remainingPayment >= amountOwed) amountOwed else remainingPayment

            val newPaidAmount = order.paidAmount + paymentForThisOrder
            printDao.updatePayment(order.id, newPaidAmount)

            remainingPayment -= paymentForThisOrder
        }

        val actualSettled = paymentAmount
        val newBalance = currentBalance - actualSettled

        printDao.insertSettlement(
            SettlementHistory(
                customerName = customer.displayName,
                customerId = customer.id,
                balanceBefore = currentBalance,
                amountPaid = actualSettled,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = "PAYMENT",
                note = "Payment via $paymentMethod",
                transactionAmount = -actualSettled,
                newBalance = newBalance
            )
        )

        if (paymentMethod == "UPI") {
            insertBeautyTransaction(actualSettled, "ADD", "Debt Settlement - ${customer.displayName}")
        }
    }

    suspend fun getCustomerBalanceById(customerId: Long): Double {
        return printDao.getLatestBalanceForCustomer(customerId) ?: 0.0
    }

    // Deprecated: use getCustomerBalanceById
    suspend fun getCustomerBalance(customerName: String): Double {
        val customer = printDao.getCustomerByNormalizedName(customerName.trim().lowercase())
        return if (customer != null) getCustomerBalanceById(customer.id) else 0.0
    }

    suspend fun getCustomerSummaries(): List<DebtorSummary> {
        return printDao.getDebtors()
    }

    suspend fun addOrUpdateDebtorCredit(customerName: String, amountDelta: Double, note: String? = null) {
        val customer = getOrCreateCustomer(customerName)
        val previousBalance = getCustomerBalanceById(customer.id)
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
                customerName = customer.displayName,
                customerId = customer.id,
                balanceBefore = previousBalance,
                amountPaid = if (isPayment) amount else 0.0,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = if (isPayment) "PAYMENT" else "ADJUSTMENT",
                note = finalNote,
                transactionAmount = amountDelta,
                newBalance = newBalance
            )
        )

        // Sync with the summary table
        printDao.insertOrUpdateDebtorCredit(DebtorCredit(customer.id, customer.displayName, newBalance, System.currentTimeMillis()))
    }

    suspend fun deleteDebtorCredit(customerId: Long) {
        printDao.deleteDebtorCredit(customerId)
    }

    // --- Other ---

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
        val previousBalance = getCurrentBeautyBalance()
        val transactionAmount = when (type) {
            "ADD", "RESET" -> amount
            "RETURN" -> -amount
            else -> amount
        }
        val newBalance = if (type == "RESET") amount else previousBalance + transactionAmount

        printDao.insertBeautyTransaction(
            BeautyTransaction(
                amount = amount, 
                type = type, 
                note = note,
                previousBalance = previousBalance,
                transactionAmount = transactionAmount,
                newBalance = newBalance
            )
        )
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
