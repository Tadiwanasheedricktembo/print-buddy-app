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
        
        printDao.recordCommercialOrder(orderWithSnapshots, items)
        
        if (orderWithSnapshots.paymentMethod == "UPI" && orderWithSnapshots.paidAmount > 0) {
            insertBeautyTransaction(orderWithSnapshots.paidAmount, "ADD", "Order - ${customer.displayName}")
        }
    }

    suspend fun confirmOrder(
        customerName: String, 
        cartItems: List<CartItem>, 
        paymentMethod: String = "CASH",
        appliedCredit: Double = 0.0
    ): Int {
        if (cartItems.isEmpty()) return -1

        val customer = getOrCreateCustomer(customerName)
        val total = cartItems.sumOf { it.price * it.quantity }
        
        val amountAfterCredit = total - appliedCredit
        
        val finalPaidAmount = if (paymentMethod == "OWES_ME") 0.0 else amountAfterCredit
        val finalPaymentMethod = if (paymentMethod == "OWES_ME") "CASH" else paymentMethod

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

        val orderItems = cartItems.map { cartItem ->
            OrderItem(
                orderId = 0,
                serviceName = cartItem.serviceName,
                price = cartItem.price,
                quantity = cartItem.quantity
            )
        }
        
        val orderId = printDao.recordCommercialOrder(order, orderItems)
        
        if (finalPaymentMethod == "UPI" && finalPaidAmount > 0) {
            insertBeautyTransaction(finalPaidAmount, "ADD", "Direct Pay - ${customer.displayName}")
        }

        for (item in cartItems) {
            val stockItem = printDao.getStockItemByName(item.serviceName)
            if (stockItem != null) {
                printDao.deductStockByName(item.serviceName, item.quantity)
            }
        }

        return orderId
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()

    suspend fun getUnpaidOrders(): List<Order> = printDao.getUnpaidOrders()

    suspend fun updatePayment(orderId: Int, newPaidAmount: Double, paymentMethod: String = "CASH") {
        val order = printDao.getOrderById(orderId) ?: return
        val delta = newPaidAmount - order.paidAmount
        if (delta == 0.0) return

        // Update the order itself
        printDao.updatePayment(orderId, newPaidAmount)
        
        // Rebuild projection for the customer who owns this order
        val customerId = order.customerId
        val currentBalance = getCustomerBalanceById(customerId)
        val newBalance = currentBalance - delta

        printDao.insertSettlement(
            SettlementHistory(
                customerName = order.customerName,
                customerId = customerId,
                balanceBefore = currentBalance,
                amountPaid = delta,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = "PAYMENT",
                ledgerEntryType = "PAYMENT",
                note = "Additional payment for Order #${order.id} via $paymentMethod",
                transactionAmount = -delta,
                newBalance = newBalance,
                originId = orderId
            )
        )

        // If UPI, record in Beauty Account
        if (paymentMethod == "UPI") {
            insertBeautyTransaction(delta, "ADD", "Payment Order #${order.id} - ${order.customerName}")
        }

        printDao.rebuildCustomerProjection(customerId)
    }

    suspend fun getOrdersBetween(start: Long, end: Long): List<Order> = printDao.getOrdersBetween(start, end)

    suspend fun getRevenueBetween(start: Long, end: Long): Double? = printDao.getRevenueBetween(start, end)

    suspend fun getOrdersCountBetween(start: Long, end: Long): Int = printDao.getOrdersCountBetween(start, end)

    suspend fun getExpensesBetween(start: Long, end: Long): Double? = printDao.getExpensesBetween(start, end)

    suspend fun getRevenueTrend(start: Long, end: Long): List<TrendPoint> = printDao.getRevenueTrend(start, end)

    suspend fun getPaymentBreakdownBetween(start: Long, end: Long): List<PaymentBreakdown> = printDao.getPaymentBreakdownBetween(start, end)

    suspend fun getServiceBreakdownBetween(start: Long, end: Long): List<CategoryRevenue> = printDao.getServiceBreakdownBetween(start, end)

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
                ledgerEntryType = "PAYMENT",
                note = "Payment via $paymentMethod",
                transactionAmount = -actualSettled,
                newBalance = newBalance
            )
        )
        
        // Update projection
        printDao.rebuildCustomerProjection(customer.id)

        if (paymentMethod == "UPI") {
            insertBeautyTransaction(actualSettled, "ADD", "Debt Settlement - ${customer.displayName}")
        }
    }

    suspend fun getCustomerBalanceById(customerId: Long): Double {
        // Prefer the latest settlement balance if available; otherwise derive
        // balance from unpaid orders so new customers without settlement
        // records still have correct balances.
        val latest = printDao.getLatestBalanceForCustomer(customerId)
        return if (latest != null) latest else printDao.getUnpaidTotalForCustomer(customerId)
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
                ledgerEntryType = if (isPayment) "PAYMENT" else "ADJUSTMENT",
                note = finalNote,
                transactionAmount = amountDelta,
                newBalance = newBalance
            )
        )

        // Sync with the summary table (rebuild projection)
        printDao.rebuildCustomerProjection(customer.id)
    }

    suspend fun deleteDebtorCredit(customerId: Long) {
        printDao.deleteDebtorCredit(customerId)
    }

    suspend fun deleteCustomerCompletely(customerId: Long) {
        printDao.deleteCustomerCompletely(customerId)
    }

    suspend fun rebuildCustomerProjection(customerId: Long) {
        printDao.rebuildCustomerProjection(customerId)
    }

    suspend fun verifyCustomerBalance(customerId: Long): Boolean {
        return printDao.verifyCustomerBalance(customerId)
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

    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction) {
        // 1. Delete the transaction
        printDao.deleteBeautyTransaction(transaction)

        // 2. Rebuild subsequent transactions to keep balance correct
        val after = printDao.getBeautyTransactionsAfter(transaction.timestamp, transaction.id)
        var runningBalance = printDao.getBeautyBalanceBefore(transaction.timestamp, transaction.id) ?: 0.0

        for (item in after) {
            val previousBalance = runningBalance
            val newBalance = if (item.type == "RESET") item.amount else previousBalance + item.transactionAmount
            
            val updated = item.copy(
                previousBalance = previousBalance,
                newBalance = newBalance
            )
            printDao.updateBeautyTransaction(updated)
            runningBalance = newBalance
        }
    }

    // Compatibility methods for External Ledger
    suspend fun getExternalBalance(): Double? = getBeautyBalance()
    suspend fun getAllExternalLedgerEntries(): List<BeautyTransaction> = getAllBeautyTransactions()

    // --- Expenses ---

    suspend fun insertExpense(expense: Expense) {
        printDao.insertExpense(expense)
        
        // If paid via UPI, record it in Beauty account as a RETURN (money leaving the UPI account)
        if (expense.paymentMethod == "UPI") {
            insertBeautyTransaction(expense.amount, "RETURN", "Expense: ${expense.category}")
        }
    }

    suspend fun addExpense(amount: Double, category: String, note: String?, paymentMethod: String = "CASH") {
        val cat = when(category) {
            "Paper" -> ExpenseCategory.PAPER
            "Ink" -> ExpenseCategory.INK
            "Electricity" -> ExpenseCategory.ELECTRICITY
            "Maintenance" -> ExpenseCategory.MAINTENANCE
            else -> ExpenseCategory.MISCELLANEOUS
        }
        insertExpense(Expense(amount = amount, category = cat, title = category, note = note, paymentMethod = paymentMethod))
    }

    fun getAllExpensesFlow(): Flow<List<Expense>> = printDao.getAllExpensesFlow()

    suspend fun getTotalExpenses(): Double = printDao.getTotalExpenses() ?: 0.0

    suspend fun deleteExpense(expenseId: Int) = printDao.deleteExpense(expenseId)

    suspend fun restoreExpenses(expenses: List<Expense>, fullReplace: Boolean) {
        if (fullReplace) {
            printDao.clearExpenses()
        }
        printDao.insertAllExpenses(expenses)
    }

    // --- Stock Management ---

    fun getAllStockItemsFlow(): Flow<List<StockItem>> = printDao.getAllStockItemsFlow()

    fun getLowStockItemsFlow(): Flow<List<StockItem>> = printDao.getLowStockItemsFlow()

    suspend fun addOrUpdateStockItem(item: StockItem) = printDao.insertStockItem(item)

    suspend fun deleteStockItem(item: StockItem) = printDao.deleteStockItem(item)

    suspend fun deductStockByName(name: String, amount: Int) = printDao.deductStockByName(name, amount)

    suspend fun getStockItemByName(name: String) = printDao.getStockItemByName(name)

    // --- Profit Analysis ---

    suspend fun getNetProfit(): Double {
        val totalRevenue = printDao.getRevenueBetween(0, Long.MAX_VALUE) ?: 0.0
        val totalExpenses = printDao.getTotalExpenses() ?: 0.0
        return totalRevenue - totalExpenses
    }
}
