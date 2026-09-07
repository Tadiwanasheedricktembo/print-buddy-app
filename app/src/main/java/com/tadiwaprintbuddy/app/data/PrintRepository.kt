package com.tadiwaprintbuddy.app.data

import android.util.Log
import com.tadiwaprintbuddy.app.CartItem
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.util.Calendar

class PrintRepository(private val printDao: PrintDao) {

    // --- Customer Management ---

    private suspend fun getOrCreateCustomer(name: String): CustomerEntity {
        val trimmedName = name.trim()
        val normalized = trimmedName.lowercase()
        Log.d(DebugTags.CUSTOMER_LOOKUP, "getOrCreateCustomer: name='$name' normalized='$normalized'")
        return printDao.getCustomerByNormalizedName(normalized) ?: run {
            Log.i(DebugTags.CUSTOMER_CREATION, "Creating new customer: '$trimmedName'")
            val newCustomer = CustomerEntity(
                displayName = trimmedName,
                normalizedName = normalized
            )
            val id = printDao.insertCustomer(newCustomer)
            Log.i(DebugTags.CUSTOMER_CREATION, "Created customer with ID: $id")
            newCustomer.copy(id = id)
        }
    }

    suspend fun getCustomerById(id: Long) = printDao.getCustomerById(id)

    fun getAllCustomersFlow(): Flow<List<CustomerEntity>> = printDao.getAllCustomersFlow()

    suspend fun getAllCustomers(): List<CustomerEntity> = printDao.getAllCustomers()

    // --- Orders ---

    fun getTotalRevenueFlow(): Flow<BigDecimal?> = printDao.getTotalRevenueFlow()

    fun getTotalOrdersFlow(): Flow<Int> = printDao.getTotalOrdersFlow()

    suspend fun getTodaysRevenue(): BigDecimal? {
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

    suspend fun confirmOrder(
        customerName: String, 
        cartItems: List<CartItem>, 
        paymentMethod: String = "CASH",
        appliedCredit: BigDecimal = BigDecimal.ZERO,
        receivedAmount: BigDecimal? = null
    ): OrderResult {
        Log.i(DebugTags.ORDER_CREATION, "confirmOrder: customer='$customerName', items=${cartItems.size}, method=$paymentMethod")
        // Authoritative Repository Validation
        if (cartItems.isEmpty()) return OrderResult.ValidationError("Add at least one item")
        
        var total = BigDecimal.ZERO
        for (item in cartItems) {
            total = total.add(item.getSubtotal())
        }

        if (total <= BigDecimal.ZERO) return OrderResult.ValidationError("Enter a valid amount greater than ₹0")

        for (item in cartItems) {
            if (item.quantity <= 0) return OrderResult.ValidationError("Invalid quantity for ${item.serviceName}")
            if (item.price < BigDecimal.ZERO) return OrderResult.ValidationError("Invalid price for ${item.serviceName}")
        }

        // 1. Stock Pre-Check
        for (item in cartItems) {
            val stockItem = printDao.getStockItemByName(item.serviceName)
            if (stockItem != null && stockItem.currentQuantity < item.quantity) {
                return OrderResult.InsufficientStock(item.serviceName, stockItem.currentQuantity, item.quantity)
            }
        }

        val customer = getOrCreateCustomer(customerName)
        val currentTime = System.currentTimeMillis()

        val orderItems = cartItems.map { cartItem ->
            OrderItem(
                orderId = 0,
                serviceName = cartItem.serviceName,
                price = cartItem.price,
                quantity = cartItem.quantity
            )
        }
        
        return try {
            val orderId = printDao.recordOrderWithWalletAtomic(
                customer = customer,
                items = orderItems,
                total = total,
                requestedPaymentMethod = paymentMethod,
                appliedCredit = appliedCredit,
                currentTime = currentTime,
                receivedAmount = receivedAmount
            )
            OrderResult.Success(orderId)
        } catch (e: Exception) {
            Log.e(DebugTags.ORDER_CREATION, "Failed to record order", e)
            OrderResult.Error(e.message ?: "Failed to save order")
        }
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()

    suspend fun getUnpaidOrders(): List<Order> = printDao.getUnpaidOrders()

    suspend fun updatePayment(orderId: Int, newPaidAmount: BigDecimal, paymentMethod: String = "CASH", receivedAmount: BigDecimal? = null) {
        Log.i(DebugTags.PAYMENT_PROCESS, "updatePayment: orderId=$orderId, newPaidAmount=$newPaidAmount, method=$paymentMethod")
        val order = printDao.getOrderById(orderId) ?: run {
            Log.w(DebugTags.PAYMENT_PROCESS, "updatePayment: Order #$orderId not found")
            return
        }
        val delta = newPaidAmount.subtract(order.paidAmount)
        if (delta <= BigDecimal.ZERO) return

        val status = when {
            newPaidAmount >= order.totalAmount -> "PAID"
            newPaidAmount > BigDecimal.ZERO -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }

        val method = when {
            order.paymentMethod == "NONE" || order.paymentMethod == "" -> paymentMethod
            order.paymentMethod == paymentMethod -> paymentMethod
            else -> "MIXED"
        }

        val customerId = order.customerId
        val currentBalance = getCustomerBalanceById(customerId)
        val newBalance = currentBalance.subtract(delta)

        val settlement = SettlementHistory(
            customerName = order.customerName,
            customerId = customerId,
            balanceBefore = currentBalance,
            amountPaid = delta,
            balanceAfter = newBalance,
            timestamp = System.currentTimeMillis(),
            type = "PAYMENT",
            ledgerEntryType = "PAYMENT",
            note = "Additional payment for Order #${order.id} via $paymentMethod",
            transactionAmount = delta.negate(),
            newBalance = newBalance,
            originId = orderId,
            receivedAmount = receivedAmount
        )

        printDao.recordPaymentWithWalletAtomic(orderId, newPaidAmount, status, method, settlement, delta)
    }

    suspend fun cancelOrder(orderId: Int) {
        val order = printDao.getOrderById(orderId) ?: return
        if (order.orderStatus == "CANCELLED") return

        val customerId = order.customerId
        val currentBalance = getCustomerBalanceById(customerId)
        
        // Reverse debt = total - paid
        val amountToReverse = order.totalAmount.subtract(order.paidAmount)
        val newBalance = currentBalance.subtract(amountToReverse)

        val settlement = SettlementHistory(
            customerName = order.customerName,
            customerId = customerId,
            balanceBefore = currentBalance,
            amountPaid = BigDecimal.ZERO,
            balanceAfter = newBalance,
            timestamp = System.currentTimeMillis(),
            type = "CANCEL",
            ledgerEntryType = "ORDER_CANCEL",
            note = "Cancelled Order #$orderId",
            transactionAmount = amountToReverse.negate(),
            newBalance = newBalance,
            originId = orderId
        )

        val walletReturn = if (order.paymentMethod == "UPI") order.paidAmount else BigDecimal.ZERO
        printDao.cancelOrderWithWalletAtomic(orderId, "CANCELLED", settlement, walletReturn)
    }

    suspend fun deleteOrder(orderId: Int) {
        cancelOrder(orderId) // Financial reversal
        val order = printDao.getOrderById(orderId)
        if (order != null) {
            printDao.deleteOrderAndItems(order)
        }
    }

    suspend fun getOrdersBetween(start: Long, end: Long): List<Order> = printDao.getOrdersBetween(start, end)

    suspend fun getRevenueBetween(start: Long, end: Long): BigDecimal? = printDao.getRevenueBetween(start, end)

    suspend fun getExpensesBetween(start: Long, end: Long): BigDecimal = printDao.getExpensesBetween(start, end)

    suspend fun getExpensesByMethodBetween(start: Long, end: Long, method: String): BigDecimal = 
        printDao.getExpensesByMethodBetween(start, end, method)

    suspend fun getSalesRevenueBetween(start: Long, end: Long): BigDecimal = 
        printDao.getSalesRevenueBetween(start, end)

    suspend fun getSalesVolumeBetween(start: Long, end: Long): BigDecimal = 
        printDao.getSalesVolumeBetween(start, end)

    suspend fun getSettledRevenueByMethodBetween(start: Long, end: Long, method: String): BigDecimal = 
        printDao.getSettledRevenueByMethodBetween(start, end, method)

    suspend fun getRevenueByMethodBetween(start: Long, end: Long, method: String): BigDecimal = 
        printDao.getRevenueByMethodBetween(start, end, method)

    suspend fun getOrdersCountBetween(start: Long, end: Long): Int = 
        printDao.getOrdersCountBetween(start, end)

    suspend fun getOrdersCountByMethodBetween(start: Long, end: Long, method: String): Int = 
        printDao.getOrdersCountByMethodBetween(start, end, method)

    suspend fun getTotalReceivables(): BigDecimal = printDao.getTotalReceivables()

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
        printDao.getBeautyReceivedBetween(start, end)

    suspend fun getBeautyReturnedBetween(start: Long, end: Long): BigDecimal = 
        printDao.getBeautyReturnedBetween(start, end)

    suspend fun getBeautyNetFlowBetween(start: Long, end: Long): BigDecimal =
        printDao.getBeautyNetFlowBetween(start, end)

    suspend fun getBeautyTransactionCountBetween(start: Long, end: Long): Int = 
        printDao.getBeautyTransactionCountBetween(start, end)

    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>> = printDao.getRevenueByCategoryFlow()

    // --- Debt & Settlements ---

    suspend fun applyPaymentToCustomer(customerName: String, paymentAmount: BigDecimal, paymentMethod: String = "CASH", receivedAmount: BigDecimal? = null) {
        val customer = getOrCreateCustomer(customerName)
        applyPaymentToCustomerId(customer.id, paymentAmount, paymentMethod, receivedAmount)
    }

    suspend fun applyPaymentToCustomerId(customerId: Long, paymentAmount: BigDecimal, paymentMethod: String = "CASH", receivedAmount: BigDecimal? = null) {
        Log.i(DebugTags.PAYMENT_PROCESS, "applyPaymentToCustomerId: customerId=$customerId, amount=$paymentAmount, method=$paymentMethod")
        printDao.applyPaymentToCustomerIdWithWalletAtomic(customerId, paymentAmount, paymentMethod, receivedAmount)
    }

    suspend fun getCustomerBalanceById(customerId: Long): BigDecimal {
        val balance = printDao.getAuthoritativeCustomerBalance(customerId)
        Log.d(DebugTags.DEBT_CALC, "getCustomerBalanceById: ID=$customerId, balance=$balance")
        return balance
    }

    suspend fun getCustomerBalance(customerName: String): BigDecimal {
        val customer = printDao.getCustomerByNormalizedName(customerName.trim().lowercase())
        return if (customer != null) getCustomerBalanceById(customer.id) else BigDecimal.ZERO
    }

    suspend fun getCustomerSummaries(): List<DebtorSummary> = printDao.getDebtors()

    suspend fun addOrUpdateDebtorCredit(customerName: String, amountDelta: BigDecimal, note: String? = null, receivedAmount: BigDecimal? = null) {
        val customer = getOrCreateCustomer(customerName)
        val previousBalance = getCustomerBalanceById(customer.id)
        val newBalance = previousBalance.add(amountDelta)

        val isPayment = amountDelta < BigDecimal.ZERO
        val amount = if (isPayment) amountDelta.negate() else amountDelta

        val finalNote = note ?: when {
            previousBalance == BigDecimal.ZERO && amountDelta > BigDecimal.ZERO -> "New Debt Added"
            previousBalance == BigDecimal.ZERO && amountDelta < BigDecimal.ZERO -> "Initial Change Recorded"
            amountDelta > BigDecimal.ZERO -> "Added to Balance"
            else -> "Settlement Payment"
        }
        
        printDao.insertSettlement(
            SettlementHistory(
                customerName = customer.displayName,
                customerId = customer.id,
                balanceBefore = previousBalance,
                amountPaid = if (isPayment) amount else BigDecimal.ZERO,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = if (isPayment) "PAYMENT" else "ADJUSTMENT",
                ledgerEntryType = if (isPayment) "PAYMENT" else "ADJUSTMENT",
                note = finalNote,
                transactionAmount = amountDelta,
                newBalance = newBalance,
                receivedAmount = receivedAmount
            )
        )

        printDao.rebuildCustomerProjection(customer.id)
    }

    suspend fun deleteDebtorCredit(customerId: Long) = printDao.deleteDebtorCredit(customerId)

    suspend fun deleteCustomerCompletely(customerId: Long) = printDao.deleteCustomerCompletely(customerId)

    suspend fun rebuildCustomerProjection(customerId: Long) = printDao.rebuildCustomerProjection(customerId)

    suspend fun verifyCustomerBalance(customerId: Long): Boolean = printDao.verifyCustomerBalance(customerId)

    suspend fun adjustCustomerBalance(customerId: Long, newAmountOwing: BigDecimal, reason: String? = null) {
        val customer = printDao.getCustomerById(customerId) ?: return
        val currentBalance = getCustomerBalanceById(customerId)
        val adjustmentDelta = newAmountOwing.subtract(currentBalance)

        // Skip if no actual change
        if (adjustmentDelta.abs() < BigDecimal("0.001")) return

        val now = System.currentTimeMillis()
        
        val settlement = SettlementHistory(
            customerName = customer.displayName,
            customerId = customer.id,
            balanceBefore = currentBalance,
            amountPaid = BigDecimal.ZERO, 
            balanceAfter = newAmountOwing,
            timestamp = now,
            type = "ADJUSTMENT",
            ledgerEntryType = "ADJUSTMENT",
            note = reason ?: "Manual Balance Adjustment",
            transactionAmount = adjustmentDelta,
            newBalance = newAmountOwing
        )

        printDao.adjustBalanceAtomic(settlement)
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
        if (fullReplace) {
            printDao.clearSettlementHistory()
        }
        printDao.insertAllSettlements(data)
    }

    // Money Tracking
    fun getCashInHandFlow(): Flow<BigDecimal?> = printDao.getAuthoritativeCashInHandFlow()
    
    fun getTotalReceivablesFlow(): Flow<BigDecimal?> = printDao.getAuthoritativeTotalReceivablesFlow()

    suspend fun recordMoneyReturnedFromExternal(amount: BigDecimal, note: String? = null) {
        insertBeautyTransaction(amount, "RETURN", note ?: "Money withdrawn from UPI Account")
    }

    suspend fun addManualExternalCredit(amount: BigDecimal, note: String? = null) {
        insertBeautyTransaction(amount, "ADD", note ?: "Manual Entry")
    }

    // Beauty Account Logic
    suspend fun insertBeautyTransaction(amount: BigDecimal, type: String, note: String? = null) {
        printDao.insertBeautyTransactionAtomic(amount, type, note)
    }

    fun getAllBeautyTransactionsFlow(): Flow<List<BeautyTransaction>> = printDao.getAllBeautyTransactionsFlow()
    
    suspend fun getAllBeautyTransactions(): List<BeautyTransaction> = printDao.getAllBeautyTransactions()

    fun getBeautyBalanceFlow(): Flow<BigDecimal?> = printDao.getBeautyBalanceFlow()
    
    suspend fun getBeautyBalance(): BigDecimal? = printDao.getBeautyBalance()

    suspend fun getCurrentBeautyBalance(): BigDecimal {
        return printDao.getAuthoritativeWalletBalance()
    }

    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction) {
        printDao.deleteBeautyTransaction(transaction)
        reconcileBeautyAccount()
    }

    suspend fun reconcileBeautyAccount() {
        printDao.reconcileBeautyAccountAtomic()
    }

    suspend fun getExternalBalance(): BigDecimal? = getBeautyBalance()
    suspend fun getAllExternalLedgerEntries(): List<BeautyTransaction> = getAllBeautyTransactions()

    // --- Expenses ---

    suspend fun insertExpense(expense: Expense) {
        printDao.insertExpense(expense)
        if (expense.paymentMethod == "UPI") {
            insertBeautyTransaction(expense.amount, "RETURN", "Expense: ${expense.category}")
        }
    }

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

    suspend fun getTotalExpenses(): BigDecimal = printDao.getTotalExpenses() ?: BigDecimal.ZERO

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

    suspend fun getNetProfit(): BigDecimal {
        val totalRevenue = printDao.getRevenueBetween(0, Long.MAX_VALUE) ?: BigDecimal.ZERO
        val totalExpenses = printDao.getTotalExpenses() ?: BigDecimal.ZERO
        return totalRevenue.subtract(totalExpenses)
    }

    suspend fun reconcileAll() {
        // 1. Fix order payment statuses & methods
        val orders = printDao.getAllOrders()
        for (order in orders) {
            val status = when {
                order.paidAmount >= order.totalAmount -> "PAID"
                order.paidAmount > BigDecimal.ZERO -> "PARTIALLY_PAID"
                else -> "UNPAID"
            }
            val method = if (order.paymentMethod == "CASH" && order.paidAmount == BigDecimal.ZERO) "NONE" else order.paymentMethod
            if (order.paymentStatus != status || order.paymentMethod != method) {
                printDao.updateOrderPaymentStatus(order.id, order.paidAmount, status, method)
            }
        }

        // 2. Rebuild all customer projections
        val customers = printDao.getAllCustomers()
        for (customer in customers) {
            printDao.rebuildCustomerProjection(customer.id)
        }

        // 3. Reconcile Beauty Account
        reconcileBeautyAccount()
    }
}
