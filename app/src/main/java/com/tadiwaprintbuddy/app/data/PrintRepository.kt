package com.tadiwaprintbuddy.app.data

import com.tadiwaprintbuddy.app.CartItem
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class PrintRepository(private val printDao: PrintDao) {

    // --- Customer Management ---

    private suspend fun getOrCreateCustomer(name: String): CustomerEntity {
        val trimmedName = name.trim()
        val normalized = trimmedName.lowercase()
        android.util.Log.d(DebugTags.CUSTOMER_LOOKUP, "getOrCreateCustomer: name='$name' normalized='$normalized'")
        return printDao.getCustomerByNormalizedName(normalized) ?: run {
            android.util.Log.i(DebugTags.CUSTOMER_CREATION, "Creating new customer: '$trimmedName'")
            val newCustomer = CustomerEntity(
                displayName = trimmedName,
                normalizedName = normalized
            )
            val id = printDao.insertCustomer(newCustomer)
            android.util.Log.i(DebugTags.CUSTOMER_CREATION, "Created customer with ID: $id")
            newCustomer.copy(id = id)
        }
    }

    suspend fun getCustomerById(id: Long) = printDao.getCustomerById(id)

    fun getAllCustomersFlow(): Flow<List<CustomerEntity>> = printDao.getAllCustomersFlow()

    suspend fun getAllCustomers(): List<CustomerEntity> = printDao.getAllCustomers()

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

    suspend fun confirmOrder(
        customerName: String, 
        cartItems: List<CartItem>, 
        paymentMethod: String = "CASH",
        appliedCredit: Double = 0.0,
        receivedAmount: Double? = null
    ): OrderResult {
        android.util.Log.i(DebugTags.ORDER_CREATION, "confirmOrder: customer='$customerName', items=${cartItems.size}, method=$paymentMethod")
        // Authoritative Repository Validation
        if (cartItems.isEmpty()) return OrderResult.ValidationError("Add at least one item")
        
        val total = cartItems.sumOf { it.price * it.quantity }
        if (total <= 0.0) return OrderResult.ValidationError("Enter a valid amount greater than ₹0")

        for (item in cartItems) {
            if (item.quantity <= 0) return OrderResult.ValidationError("Invalid quantity for ${item.serviceName}")
            if (item.price < 0) return OrderResult.ValidationError("Invalid price for ${item.serviceName}")
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
            val orderId = printDao.recordOrderAtomic(
                customer = customer,
                items = orderItems,
                total = total,
                requestedPaymentMethod = paymentMethod,
                appliedCredit = appliedCredit,
                currentTime = currentTime,
                receivedAmount = receivedAmount
            )
            
            // Re-fetch order for post-processing logic (like Beauty account)
            val recordedOrder = printDao.getOrderById(orderId)
            if (recordedOrder != null) {
                // Determine actual cash/upi paid (excluding credit)
                val cashPaid = if (paymentMethod == "OWES_ME") 0.0 else total - appliedCredit
                if (paymentMethod == "UPI" && cashPaid > 0) {
                    android.util.Log.d(DebugTags.PAYMENT_PROCESS, "UPI Payment: Recording beauty transaction for order #$orderId")
                    insertBeautyTransaction(cashPaid, "ADD", "Direct Pay - Order #$orderId - ${customer.displayName}")
                }
            }
            
            OrderResult.Success(orderId)
        } catch (e: Exception) {
            android.util.Log.e(DebugTags.ORDER_CREATION, "Failed to record order", e)
            OrderResult.Error(e.message ?: "Failed to save order")
        }
    }

    suspend fun getAllOrders(): List<Order> = printDao.getAllOrders()

    suspend fun getUnpaidOrders(): List<Order> = printDao.getUnpaidOrders()

    suspend fun updatePayment(orderId: Int, newPaidAmount: Double, paymentMethod: String = "CASH", receivedAmount: Double? = null) {
        android.util.Log.i(DebugTags.PAYMENT_PROCESS, "updatePayment: orderId=$orderId, newPaidAmount=$newPaidAmount, method=$paymentMethod")
        val order = printDao.getOrderById(orderId) ?: run {
            android.util.Log.w(DebugTags.PAYMENT_PROCESS, "updatePayment: Order #$orderId not found")
            return
        }
        val delta = newPaidAmount - order.paidAmount
        if (delta <= 0.0) return

        val status = when {
            newPaidAmount >= order.totalAmount -> "PAID"
            newPaidAmount > 0 -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }

        val method = when {
            order.paymentMethod == "NONE" || order.paymentMethod == "" -> paymentMethod
            order.paymentMethod == paymentMethod -> paymentMethod
            else -> "MIXED"
        }

        val customerId = order.customerId
        val currentBalance = getCustomerBalanceById(customerId)
        val newBalance = currentBalance - delta

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
            transactionAmount = -delta,
            newBalance = newBalance,
            originId = orderId,
            receivedAmount = receivedAmount
        )

        printDao.recordPaymentAtomic(orderId, newPaidAmount, status, method, settlement)

        // If UPI, record in Beauty Account
        if (paymentMethod == "UPI") {
            insertBeautyTransaction(delta, "ADD", "Payment Order #${order.id} - ${order.customerName}")
        }
    }

    suspend fun cancelOrder(orderId: Int) {
        val order = printDao.getOrderById(orderId) ?: return
        if (order.orderStatus == "CANCELLED") return

        val customerId = order.customerId
        val currentBalance = getCustomerBalanceById(customerId)
        
        // Reverse debt = total - paid
        val amountToReverse = order.totalAmount - order.paidAmount
        val newBalance = currentBalance - amountToReverse

        val settlement = SettlementHistory(
            customerName = order.customerName,
            customerId = customerId,
            balanceBefore = currentBalance,
            amountPaid = 0.0,
            balanceAfter = newBalance,
            timestamp = System.currentTimeMillis(),
            type = "CANCEL",
            ledgerEntryType = "ORDER_CANCEL",
            note = "Cancelled Order #$orderId",
            transactionAmount = -amountToReverse,
            newBalance = newBalance,
            originId = orderId
        )

        printDao.cancelOrderAtomic(orderId, "CANCELLED", settlement)

        if (order.paymentMethod == "UPI" && order.paidAmount > 0) {
            insertBeautyTransaction(order.paidAmount, "RETURN", "Order Cancelled #$orderId - ${order.customerName}")
        }
    }

    suspend fun deleteOrder(orderId: Int) {
        cancelOrder(orderId) // Financial reversal
        val order = printDao.getOrderById(orderId)
        if (order != null) {
            printDao.deleteOrderAndItems(order)
        }
    }

    suspend fun getOrdersBetween(start: Long, end: Long): List<Order> = printDao.getOrdersBetween(start, end)

    suspend fun getRevenueBetween(start: Long, end: Long): Double? = printDao.getRevenueBetween(start, end)

    suspend fun getExpensesBetween(start: Long, end: Long): Double = printDao.getExpensesBetween(start, end)

    suspend fun getExpensesByMethodBetween(start: Long, end: Long, method: String): Double = 
        printDao.getExpensesByMethodBetween(start, end, method)

    suspend fun getSalesRevenueBetween(start: Long, end: Long): Double = 
        printDao.getSalesRevenueBetween(start, end)

    suspend fun getSettledDebtRevenueBetween(start: Long, end: Long): Double = 
        printDao.getSettledDebtRevenueBetween(start, end)

    suspend fun getRevenueByMethodBetween(start: Long, end: Long, method: String): Double = 
        printDao.getRevenueByMethodBetween(start, end, method)

    suspend fun getOrdersCountBetween(start: Long, end: Long): Int = 
        printDao.getOrdersCountBetween(start, end)

    suspend fun getOrdersCountByMethodBetween(start: Long, end: Long, method: String): Int = 
        printDao.getOrdersCountByMethodBetween(start, end, method)

    suspend fun getTotalReceivables(): Double = printDao.getTotalReceivables()

    suspend fun getDebtorsCount(): Int = printDao.getDebtorsCount()

    suspend fun getRevenueTrendByMethod(start: Long, end: Long, method: String): List<TrendPoint> = 
        printDao.getRevenueTrendByMethod(start, end, method)

    suspend fun getPaymentBreakdownBetween(start: Long, end: Long): List<PaymentBreakdown> = 
        printDao.getPaymentBreakdownBetween(start, end)

    suspend fun getServiceBreakdownBetween(start: Long, end: Long): List<CategoryRevenue> = 
        printDao.getServiceBreakdownBetween(start, end)

    suspend fun getExpenseBreakdownBetween(start: Long, end: Long): List<CategoryRevenue> = 
        printDao.getExpenseBreakdownBetween(start, end)

    fun getFilteredBeautyTransactions(start: Long, end: Long) = 
        printDao.getFilteredBeautyTransactions(start, end)

    suspend fun getBeautyReceivedBetween(start: Long, end: Long): Double = 
        printDao.getBeautyReceivedBetween(start, end)

    suspend fun getBeautyReturnedBetween(start: Long, end: Long): Double = 
        printDao.getBeautyReturnedBetween(start, end)

    suspend fun getBeautyNetFlowBetween(start: Long, end: Long): Double =
        printDao.getBeautyNetFlowBetween(start, end)

    suspend fun getBeautyTransactionCountBetween(start: Long, end: Long): Int = 
        printDao.getBeautyTransactionCountBetween(start, end)

    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>> = printDao.getRevenueByCategoryFlow()

    // --- Debt & Settlements ---

    suspend fun applyPaymentToCustomer(customerName: String, paymentAmount: Double, paymentMethod: String = "CASH", receivedAmount: Double? = null) {
        val customer = getOrCreateCustomer(customerName)
        applyPaymentToCustomerId(customer.id, paymentAmount, paymentMethod, receivedAmount)
    }

    suspend fun applyPaymentToCustomerId(customerId: Long, paymentAmount: Double, paymentMethod: String = "CASH", receivedAmount: Double? = null) {
        android.util.Log.i(DebugTags.PAYMENT_PROCESS, "applyPaymentToCustomerId: customerId=$customerId, amount=$paymentAmount, method=$paymentMethod")
        printDao.applyPaymentToCustomerIdAtomic(customerId, paymentAmount, paymentMethod, receivedAmount)
        
        if (paymentMethod == "UPI") {
            val customer = printDao.getCustomerById(customerId)
            android.util.Log.d(DebugTags.PAYMENT_PROCESS, "UPI Debt Settlement: Recording beauty transaction")
            insertBeautyTransaction(paymentAmount, "ADD", "Debt Settlement - ${customer?.displayName ?: "Unknown"}")
        }
    }

    suspend fun getCustomerBalanceById(customerId: Long): Double {
        val balance = printDao.getAuthoritativeCustomerBalance(customerId)
        android.util.Log.d(DebugTags.DEBT_CALC, "getCustomerBalanceById: ID=$customerId, balance=$balance")
        return balance
    }

    suspend fun getCustomerBalance(customerName: String): Double {
        val customer = printDao.getCustomerByNormalizedName(customerName.trim().lowercase())
        return if (customer != null) getCustomerBalanceById(customer.id) else 0.0
    }

    suspend fun getCustomerSummaries(): List<DebtorSummary> = printDao.getDebtors()

    suspend fun addOrUpdateDebtorCredit(customerName: String, amountDelta: Double, note: String? = null, receivedAmount: Double? = null) {
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

    suspend fun adjustCustomerBalance(customerId: Long, newAmountOwing: Double, reason: String? = null) {
        val customer = printDao.getCustomerById(customerId) ?: return
        val currentBalance = getCustomerBalanceById(customerId)
        val adjustmentDelta = newAmountOwing - currentBalance

        // Skip if no actual change
        if (Math.abs(adjustmentDelta) < 0.001) return

        val now = System.currentTimeMillis()
        
        val settlement = SettlementHistory(
            customerName = customer.displayName,
            customerId = customer.id,
            balanceBefore = currentBalance,
            amountPaid = 0.0, 
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
    fun getCashInHandFlow(): Flow<Double?> = printDao.getAuthoritativeCashInHandFlow()
    
    fun getTotalReceivablesFlow(): Flow<Double?> = printDao.getAuthoritativeTotalReceivablesFlow()

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
            "ADD" -> amount
            "RETURN" -> -amount
            "RESET" -> -previousBalance
            else -> amount
        }
        val newBalance = when (type) {
            "RESET" -> 0.0
            else -> previousBalance + transactionAmount
        }

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

    suspend fun getCurrentBeautyBalance(): Double {
        return printDao.getAuthoritativeWalletBalance()
    }

    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction) {
        printDao.deleteBeautyTransaction(transaction)
        reconcileBeautyAccount()
    }

    suspend fun reconcileBeautyAccount() {
        val all = printDao.getAllBeautyTransactions().sortedBy { it.timestamp }
        var runningBalance = 0.0

        for (item in all) {
            val previousBalance = runningBalance
            val transactionAmount = when (item.type) {
                "ADD" -> item.amount
                "RETURN" -> -item.amount
                "RESET" -> -previousBalance
                else -> item.transactionAmount
            }
            val newBalance = if (item.type == "RESET") 0.0 else previousBalance + transactionAmount

            val updated = item.copy(
                previousBalance = previousBalance,
                transactionAmount = transactionAmount,
                newBalance = newBalance
            )
            printDao.updateBeautyTransaction(updated)
            runningBalance = newBalance
        }
    }

    suspend fun getExternalBalance(): Double? = getBeautyBalance()
    suspend fun getAllExternalLedgerEntries(): List<BeautyTransaction> = getAllBeautyTransactions()

    // --- Expenses ---

    suspend fun insertExpense(expense: Expense) {
        printDao.insertExpense(expense)
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

    suspend fun reconcileAll() {
        // 1. Fix order payment statuses & methods
        val orders = printDao.getAllOrders()
        for (order in orders) {
            val status = when {
                order.paidAmount >= order.totalAmount -> "PAID"
                order.paidAmount > 0 -> "PARTIALLY_PAID"
                else -> "UNPAID"
            }
            val method = if (order.paymentMethod == "CASH" && order.paidAmount == 0.0) "NONE" else order.paymentMethod
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
