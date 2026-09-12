package com.tadiwaprintbuddy.app.data

import com.tadiwaprintbuddy.app.CartItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.util.Calendar

class PrintRepository(private val printDao: PrintDao) {

    // --- Customer Management ---

    private suspend fun getOrCreateCustomer(name: String): CustomerEntity {
        val trimmedName = name.trim()
        val normalized = trimmedName.lowercase()
        return printDao.getCustomerByNormalizedName(normalized) ?: run {
            val newCustomer = CustomerEntity(displayName = trimmedName, normalizedName = normalized)
            val id = printDao.insertCustomer(newCustomer)
            newCustomer.copy(id = id)
        }
    }

    suspend fun getCustomerById(id: Long) = printDao.getCustomerById(id)

    fun getAllCustomersFlow(): Flow<List<CustomerEntity>> = printDao.getAllCustomersFlow()

    suspend fun getAllCustomers(): List<CustomerEntity> = printDao.getAllCustomers()

    // --- Orders ---

    fun getTotalRevenueFlow(): Flow<BigDecimal> = 
        printDao.getAllActivePaidAmountsFlow().map { list ->
            list.fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
        }

    fun getTotalOrdersFlow(): Flow<Int> = printDao.getTotalOrdersFlow()

    suspend fun getTodaysRevenue(): BigDecimal {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val end = calendar.timeInMillis
        return printDao.getPaidAmountsBetween(start, end).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
    }

    suspend fun confirmOrder(
        customerName: String, 
        cartItems: List<CartItem>, 
        paymentMethod: String = "CASH",
        appliedCredit: BigDecimal = BigDecimal.ZERO,
        receivedAmount: BigDecimal? = null
    ): OrderResult {
        if (cartItems.isEmpty()) return OrderResult.ValidationError("Add at least one item")
        val total = cartItems.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.getSubtotal()) }
        if (total <= BigDecimal.ZERO) return OrderResult.ValidationError("Enter a valid amount greater than ₹0")

        val customer = getOrCreateCustomer(customerName)
        val currentTime = System.currentTimeMillis()
        val orderItems = cartItems.map { OrderItem(orderId = 0, serviceName = it.serviceName, price = it.price, quantity = it.quantity) }
        
        return try {
            val orderId = printDao.recordOrderWithWalletAtomic(customer, orderItems, total, paymentMethod, appliedCredit, currentTime, receivedAmount)
            OrderResult.Success(orderId)
        } catch (e: Exception) {
            OrderResult.Error(e.message ?: "Failed to save order")
        }
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()

    suspend fun getUnpaidOrders(): List<Order> = printDao.getUnpaidOrders()

    suspend fun updatePayment(orderId: Int, newPaidAmount: BigDecimal, paymentMethod: String = "CASH", receivedAmount: BigDecimal? = null) {
        val order = printDao.getOrderById(orderId) ?: return
        val delta = newPaidAmount.subtract(order.paidAmount)
        if (delta <= BigDecimal.ZERO) return

        val status = when {
            newPaidAmount >= order.totalAmount -> "PAID"
            newPaidAmount > BigDecimal.ZERO -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }

        val method = if (order.paymentMethod == "NONE" || order.paymentMethod == "" || order.paymentMethod == paymentMethod) paymentMethod else "MIXED"
        val customerId = order.customerId
        val currentBalance = getCustomerBalanceById(customerId)
        val newBalance = currentBalance.subtract(delta)

        val settlement = SettlementHistory(
            customerName = order.customerName, customerId = customerId,
            balanceBefore = currentBalance, amountPaid = delta, balanceAfter = newBalance,
            timestamp = System.currentTimeMillis(), type = "PAYMENT", ledgerEntryType = "PAYMENT",
            note = "Additional payment Order #${order.id}", transactionAmount = delta.negate(),
            newBalance = newBalance, originId = orderId, receivedAmount = receivedAmount
        )

        printDao.recordPaymentWithWalletAtomic(orderId, newPaidAmount, status, method, settlement, delta)
    }

    suspend fun cancelOrder(orderId: Int) {
        val order = printDao.getOrderById(orderId) ?: return
        if (order.orderStatus == "CANCELLED") return
        val customerId = order.customerId
        val currentBalance = getCustomerBalanceById(customerId)
        val amountToReverse = order.totalAmount.subtract(order.paidAmount)
        val newBalance = currentBalance.subtract(amountToReverse)

        val settlement = SettlementHistory(
            customerName = order.customerName, customerId = customerId,
            balanceBefore = currentBalance, amountPaid = BigDecimal.ZERO, balanceAfter = newBalance,
            timestamp = System.currentTimeMillis(), type = "CANCEL", ledgerEntryType = "ORDER_CANCEL",
            note = "Cancelled Order #$orderId", transactionAmount = amountToReverse.negate(),
            newBalance = newBalance, originId = orderId
        )

        val walletReturn = if (order.paymentMethod == "UPI") order.paidAmount else BigDecimal.ZERO
        printDao.cancelOrderWithWalletAtomic(orderId, "CANCELLED", settlement, walletReturn)
    }

    suspend fun deleteOrder(orderId: Int) {
        cancelOrder(orderId)
        printDao.getOrderById(orderId)?.let { printDao.deleteOrderAndItems(it) }
    }

    suspend fun getOrdersBetween(start: Long, end: Long): List<Order> = printDao.getOrdersBetween(start, end)

    suspend fun getExpensesBetween(start: Long, end: Long): BigDecimal = 
        printDao.getExpenseAmountsBetween(start, end).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getExpensesByMethodBetween(start: Long, end: Long, method: String): BigDecimal = 
        printDao.getExpenseAmountsByMethodBetween(start, end, method).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getSalesRevenueBetween(start: Long, end: Long): BigDecimal = 
        printDao.getPaidAmountsBetween(start, end).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getSalesVolumeBetween(start: Long, end: Long): BigDecimal = 
        printDao.getTotalAmountsBetween(start, end).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getSettledRevenueByMethodBetween(start: Long, end: Long, method: String): BigDecimal = 
        printDao.getFilteredSettledAmounts(start, end, method).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getRevenueByMethodBetween(start: Long, end: Long, method: String): BigDecimal =
        printDao.getPaidAmountsByMethodBetween(start, end, method).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getOrdersCountBetween(start: Long, end: Long): Int = 
        printDao.getOrdersCountBetween(start, end)

    suspend fun getOrdersCountByMethodBetween(start: Long, end: Long, method: String): Int = 
        printDao.getOrdersCountByMethodBetween(start, end, method)

    suspend fun getTotalReceivables(): BigDecimal = 
        printDao.getAllReceivableDiffs().fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getDebtorsCount(): Int = printDao.getDebtorsCount()

    suspend fun getRevenueTrendByMethod(start: Long, end: Long, method: String): List<TrendPoint> = 
        printDao.getSettledRevenueTrendByMethod(start, end, method)

    suspend fun getPaymentBreakdownBetween(start: Long, end: Long): List<PaymentBreakdown> = 
        printDao.getSettledPaymentBreakdownBetween(start, end)

    suspend fun getServiceBreakdownBetween(start: Long, end: Long): List<CategoryRevenue> = 
        printDao.getServiceBreakdownBetween(start, end)

    suspend fun getExpenseBreakdownBetween(start: Long, end: Long): List<CategoryRevenue> = 
        printDao.getExpenseBreakdownBetween(start, end)

    fun getFilteredBeautyTransactions(start: Long, end: Long) = 
        printDao.getFilteredBeautyTransactions(start, end)

    suspend fun getBeautyReceivedBetween(start: Long, end: Long): BigDecimal = 
        printDao.getBeautyReceivedAmounts(start, end).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getBeautyReturnedBetween(start: Long, end: Long): BigDecimal = 
        printDao.getBeautyReturnedAmounts(start, end).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getBeautyNetFlowBetween(start: Long, end: Long): BigDecimal =
        printDao.getBeautyTransactionAmountsBetween(start, end).fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun getBeautyTransactionCountBetween(start: Long, end: Long): Int = 
        printDao.getBeautyTransactionCountBetween(start, end)

    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>> = printDao.getRevenueByCategoryFlow()

    // --- Debt & Settlements ---

    suspend fun applyPaymentToCustomer(customerName: String, paymentAmount: BigDecimal, paymentMethod: String = "CASH", receivedAmount: BigDecimal? = null) {
        val customer = getOrCreateCustomer(customerName)
        applyPaymentToCustomerId(customer.id, paymentAmount, paymentMethod, receivedAmount)
    }

    suspend fun applyPaymentToCustomerId(customerId: Long, paymentAmount: BigDecimal, paymentMethod: String = "CASH", receivedAmount: BigDecimal? = null) {
        printDao.applyPaymentToCustomerIdWithWalletAtomic(customerId, paymentAmount, paymentMethod, receivedAmount)
    }

    suspend fun getCustomerBalanceById(customerId: Long): BigDecimal = printDao.getAuthoritativeCustomerBalance(customerId)

    suspend fun getCustomerBalance(customerName: String): BigDecimal {
        val customer = printDao.getCustomerByNormalizedName(customerName.trim().lowercase())
        return if (customer != null) getCustomerBalanceById(customer.id) else BigDecimal.ZERO
    }

    suspend fun getCustomerSummaries(): List<DebtorSummary> {
        val groups = printDao.getDebtorGroups()
        return groups.map { it.copy(totalBalance = getCustomerBalanceById(it.customerId)) }
    }

    suspend fun addOrUpdateDebtorCredit(customerName: String, amountDelta: BigDecimal, note: String? = null, receivedAmount: BigDecimal? = null) {
        val customer = getOrCreateCustomer(customerName)
        val previousBalance = getCustomerBalanceById(customer.id)
        val newBalance = previousBalance.add(amountDelta)
        val isPayment = amountDelta < BigDecimal.ZERO
        
        printDao.insertSettlement(
            SettlementHistory(
                customerName = customer.displayName, customerId = customer.id,
                balanceBefore = previousBalance, amountPaid = if (isPayment) amountDelta.negate() else BigDecimal.ZERO,
                balanceAfter = newBalance, timestamp = System.currentTimeMillis(),
                type = if (isPayment) "PAYMENT" else "ADJUSTMENT", ledgerEntryType = if (isPayment) "PAYMENT" else "ADJUSTMENT",
                note = note ?: (if (isPayment) "Settlement Payment" else "Adjustment"), transactionAmount = amountDelta,
                newBalance = newBalance, receivedAmount = receivedAmount, customerSyncId = customer.syncId
            )
        )
        rebuildCustomerProjection(customer.id)
    }

    suspend fun deleteDebtorCredit(customerId: Long) = printDao.deleteDebtorCredit(customerId)

    suspend fun deleteCustomerCompletely(customerId: Long) = printDao.deleteCustomerCompletely(customerId)

    suspend fun rebuildCustomerProjection(customerId: Long) = printDao.rebuildCustomerProjection(customerId)

    suspend fun verifyCustomerBalance(customerId: Long): Boolean {
         val calculated = getCustomerBalanceById(customerId)
         val cached = printDao.getDebtorCreditById(customerId)?.amount ?: BigDecimal.ZERO
         return calculated == cached
    }

    suspend fun adjustCustomerBalance(customerId: Long, newAmountOwing: BigDecimal, reason: String? = null) {
        val customer = printDao.getCustomerById(customerId) ?: return
        val currentBalance = getCustomerBalanceById(customerId)
        val delta = newAmountOwing.subtract(currentBalance)
        if (delta.abs() < BigDecimal("0.001")) return
        
        printDao.adjustBalanceAtomic(SettlementHistory(
            customerName = customer.displayName, customerId = customer.id,
            balanceBefore = currentBalance, amountPaid = BigDecimal.ZERO, balanceAfter = newAmountOwing,
            timestamp = System.currentTimeMillis(), type = "ADJUSTMENT", ledgerEntryType = "ADJUSTMENT",
            note = reason ?: "Manual Adjustment", transactionAmount = delta, newBalance = newAmountOwing
        ))
    }

    // --- Other ---

    suspend fun addPrinterReference(reference: PrinterReference) = printDao.addPrinterReference(reference)

    suspend fun getAllPrinterReferences(): List<PrinterReference> = printDao.getAllPrinterReferences()

    suspend fun deletePrinterReference(reference: PrinterReference) = printDao.deletePrinterReference(reference)

    suspend fun getDebtorCreditList(): List<DebtorCredit> = printDao.getDebtorCreditList()

    suspend fun deleteOrdersBetween(start: Long, end: Long) = printDao.deleteOrdersBetween(start, end)

    suspend fun deleteAllOrders() = printDao.deleteAllOrders()

    suspend fun getAllSettlements(): List<SettlementHistory> = printDao.getAllSettlements()

    suspend fun getAllSettlementHistoryOnce(): List<SettlementHistory> = printDao.getAllSettlementHistoryOnce()

    suspend fun restoreSettlements(data: List<SettlementHistory>, fullReplace: Boolean) {
        if (fullReplace) printDao.clearSettlementHistory()
        printDao.insertAllSettlements(data)
    }

    fun getCashInHandFlow(): Flow<BigDecimal> {
        return printDao.getAllActivePaidAmountsFlow().map { list ->
             list.fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
        }
    }

    fun getTotalReceivablesFlow(): Flow<BigDecimal> {
         return printDao.getAllCustomersFlow().map { customers ->
             customers.fold(BigDecimal.ZERO) { acc, c -> acc.add(getCustomerBalanceById(c.id)) }
         }
    }

    suspend fun insertBeautyTransaction(amount: BigDecimal, type: String, note: String? = null) {
        printDao.insertBeautyTransactionAtomic(amount, type, note)
    }

    fun getAllBeautyTransactionsFlow(): Flow<List<BeautyTransaction>> = printDao.getAllBeautyTransactionsFlow()
    
    suspend fun getAllBeautyTransactions(): List<BeautyTransaction> = printDao.getAllBeautyTransactions()

    fun getBeautyBalanceFlow(): Flow<BigDecimal> = 
        printDao.getBeautyTransactionAmountsFlow().map { list ->
            list.fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
        }

    suspend fun getCurrentBeautyBalance(): BigDecimal = printDao.getAuthoritativeWalletBalance()

    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction) {
        printDao.deleteBeautyTransaction(transaction)
    }

    suspend fun insertExpense(expense: Expense) = printDao.insertExpense(expense)

    suspend fun addExpense(amount: BigDecimal, category: String, note: String?, paymentMethod: String = "CASH") {
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

    suspend fun getTotalExpenses(): BigDecimal = 
        printDao.getAllExpenseAmounts().fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }

    suspend fun deleteExpense(expenseId: Int) = printDao.deleteExpense(expenseId)

    suspend fun restoreExpenses(expenses: List<Expense>, fullReplace: Boolean) {
        if (fullReplace) printDao.clearExpenses()
        printDao.insertAllExpenses(expenses)
    }

    fun getAllStockItemsFlow(): Flow<List<StockItem>> = printDao.getAllStockItemsFlow()

    fun getLowStockItemsFlow(): Flow<List<StockItem>> = printDao.getLowStockItemsFlow()

    suspend fun addOrUpdateStockItem(item: StockItem) = printDao.insertStockItem(item)

    suspend fun deleteStockItem(item: StockItem) = printDao.deleteStockItem(item)

    suspend fun getNetProfit(): BigDecimal = getSalesRevenueBetween(0, Long.MAX_VALUE).subtract(getTotalExpenses())
}
