package com.tadiwaprintbuddy.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: Order): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItems(items: List<OrderItem>): List<Long>

    @Query("SELECT * FROM `orders` ORDER BY date DESC")
    suspend fun getAllOrders(): List<Order>

    @Query("SELECT * FROM `orders` WHERE date BETWEEN :start AND :end ORDER BY date DESC")
    suspend fun getOrdersBetween(start: Long, end: Long): List<Order>

    @Query("SELECT * FROM `OrderItem` WHERE orderId = :orderId")
    suspend fun getItemsForOrder(orderId: Int): List<OrderItem>

    @Query("SELECT SUM(paidAmount) FROM `orders` WHERE orderStatus = 'ACTIVE'")
    fun getTotalRevenueFlow(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM `orders` WHERE orderStatus = 'ACTIVE'")
    fun getTotalOrdersFlow(): Flow<Int>

    @Query("SELECT IFNULL(SUM(paidAmount), 0.0) FROM `orders` WHERE orderStatus = 'ACTIVE' AND date BETWEEN :start AND :end")
    suspend fun getRevenueBetween(start: Long, end: Long): Double?

    // Actual revenue collected (excluding credit sales)
    @Query("SELECT IFNULL(SUM(paidAmount), 0.0) FROM `orders` WHERE orderStatus = 'ACTIVE' AND date BETWEEN :start AND :end AND paymentMethod != 'NONE'")
    suspend fun getSalesRevenueBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(totalAmount), 0.0) FROM `orders` WHERE orderStatus = 'ACTIVE' AND date BETWEEN :start AND :end")
    suspend fun getSalesVolumeBetween(start: Long, end: Long): Double

    // Revenue from collection (authoritative money in)
    @Query("""
        SELECT IFNULL(SUM(settledAmount), 0.0) FROM `settlement_history` 
        WHERE timestamp BETWEEN :start AND :end 
        AND ledgerEntryType IN ('PAYMENT', 'CREDIT')
        AND (:method = 'ALL' OR (:method = 'UPI' AND note LIKE '%UPI%') OR (:method = 'CASH' AND (note IS NULL OR note NOT LIKE '%UPI%')))
    """)
    suspend fun getSettledRevenueByMethodBetween(start: Long, end: Long, method: String): Double

    @Query("SELECT IFNULL(SUM(paidAmount), 0.0) FROM `orders` WHERE orderStatus = 'ACTIVE' AND date BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getRevenueByMethodBetween(start: Long, end: Long, method: String): Double

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM `expenses` WHERE timestamp BETWEEN :start AND :end")
    suspend fun getExpensesBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM `expenses` WHERE timestamp BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getExpensesByMethodBetween(start: Long, end: Long, method: String): Double

    @Query("SELECT COUNT(*) FROM `orders` WHERE orderStatus = 'ACTIVE' AND date BETWEEN :start AND :end")
    suspend fun getOrdersCountBetween(start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM `orders` WHERE orderStatus = 'ACTIVE' AND date BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getOrdersCountByMethodBetween(start: Long, end: Long, method: String): Int

    @Query("SELECT IFNULL(SUM(totalAmount - paidAmount), 0.0) FROM `orders` WHERE orderStatus = 'ACTIVE'")
    suspend fun getTotalReceivables(): Double

    @Query("SELECT COUNT(*) FROM debtor_credits WHERE amount > 0")
    suspend fun getDebtorsCount(): Int

    @Query("""
        SELECT date as timestamp, IFNULL(SUM(paidAmount), 0.0) as amount 
        FROM `orders` 
        WHERE orderStatus = 'ACTIVE' 
        AND (:method = 'ALL' OR paymentMethod = :method) 
        AND date BETWEEN :start AND :end 
        GROUP BY date / (24 * 60 * 60 * 1000)
    """)
    suspend fun getRevenueTrendByMethod(start: Long, end: Long, method: String): List<TrendPoint>

    @Query("SELECT paymentMethod as type, IFNULL(SUM(paidAmount), 0.0) as total FROM `orders` WHERE orderStatus = 'ACTIVE' AND date BETWEEN :start AND :end GROUP BY paymentMethod")
    suspend fun getPaymentBreakdownBetween(start: Long, end: Long): List<PaymentBreakdown>

    @Query("SELECT serviceName as category, IFNULL(SUM(price * quantity), 0.0) as total FROM `OrderItem` JOIN `orders` ON orders.id = OrderItem.orderId WHERE orders.orderStatus = 'ACTIVE' AND orders.date BETWEEN :start AND :end GROUP BY serviceName")
    suspend fun getServiceBreakdownBetween(start: Long, end: Long): List<CategoryRevenue>

    @Query("SELECT category as category, SUM(amount) as total FROM `expenses` WHERE timestamp BETWEEN :start AND :end GROUP BY category")
    suspend fun getExpenseBreakdownBetween(start: Long, end: Long): List<CategoryRevenue>

    @Query("SELECT * FROM `beauty_transactions` WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getFilteredBeautyTransactions(start: Long, end: Long): Flow<List<BeautyTransaction>>

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM `beauty_transactions` WHERE type = 'ADD' AND timestamp BETWEEN :start AND :end")
    suspend fun getBeautyReceivedBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM `beauty_transactions` WHERE type = 'RETURN' AND timestamp BETWEEN :start AND :end")
    suspend fun getBeautyReturnedBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(transactionAmount), 0.0) FROM `beauty_transactions` WHERE timestamp BETWEEN :start AND :end")
    suspend fun getBeautyNetFlowBetween(start: Long, end: Long): Double

    @Query("SELECT COUNT(*) FROM `beauty_transactions` WHERE timestamp BETWEEN :start AND :end")
    suspend fun getBeautyTransactionCountBetween(start: Long, end: Long): Int

    @Query("SELECT serviceName as category, IFNULL(SUM(price * quantity), 0.0) as total FROM `OrderItem` JOIN `orders` ON orders.id = OrderItem.orderId WHERE orders.orderStatus = 'ACTIVE' GROUP BY serviceName")
    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>>

    @Query("SELECT * FROM `orders` WHERE paidAmount < totalAmount AND orderStatus = 'ACTIVE'")
    suspend fun getUnpaidOrders(): List<Order>

    @Query("UPDATE `orders` SET paidAmount = :newPaidAmount, paymentStatus = :status, paymentMethod = :method WHERE id = :orderId")
    suspend fun updateOrderPaymentStatus(orderId: Int, newPaidAmount: Double, status: String, method: String): Int

    @Query("SELECT customerId, customerName, IFNULL(SUM(totalAmount - paidAmount), 0.0) as totalBalance, 'OWES' as type FROM `orders` WHERE orderStatus = 'ACTIVE' GROUP BY customerId HAVING totalBalance > 0")
    suspend fun getDebtors(): List<DebtorSummary>

    @Query("SELECT * FROM `orders` WHERE customerId = :customerId AND paidAmount < totalAmount AND orderStatus = 'ACTIVE' ORDER BY date ASC")
    suspend fun getUnpaidOrdersForCustomer(customerId: Long): List<Order>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDebtorCredit(credit: DebtorCredit): Long

    @Query("SELECT * FROM debtor_credits WHERE customerId = :customerId")
    suspend fun getDebtorCreditById(customerId: Long): DebtorCredit?

    @Query("SELECT * FROM debtor_credits")
    suspend fun getDebtorCreditList(): List<DebtorCredit>

    @Query("DELETE FROM debtor_credits WHERE customerId = :customerId")
    suspend fun deleteDebtorCredit(customerId: Long): Int

    @Query("DELETE FROM customers WHERE id = :customerId")
    suspend fun deleteCustomer(customerId: Long): Int

    @Query("DELETE FROM `orders` WHERE customerId = :customerId")
    suspend fun deleteOrdersForCustomer(customerId: Long): Int

    @Query("DELETE FROM settlement_history WHERE customerId = :customerId")
    suspend fun deleteSettlementsForCustomer(customerId: Long): Int

    @Transaction
    suspend fun deleteCustomerCompletely(customerId: Long): Boolean {
        deleteDebtorCredit(customerId)
        deleteOrdersForCustomer(customerId)
        deleteSettlementsForCustomer(customerId)
        deleteCustomer(customerId)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Query("SELECT * FROM customers WHERE normalizedName = :normalizedName")
    suspend fun getCustomerByNormalizedName(normalizedName: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers")
    fun getAllCustomersFlow(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Query("SELECT IFNULL(SUM(transactionAmount), 0.0) FROM settlement_history WHERE customerId = :customerId")
    suspend fun getLatestBalanceForCustomer(customerId: Long): Double

    @Query("SELECT IFNULL(SUM(totalAmount - paidAmount), 0.0) FROM `orders` WHERE customerId = :customerId AND orderStatus = 'ACTIVE'")
    suspend fun getUnpaidTotalForCustomer(customerId: Long): Double

    @Query("UPDATE customers SET displayName = :newName, normalizedName = :normalized WHERE id = :customerId")
    suspend fun updateCustomerIdentity(customerId: Long, newName: String, normalized: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPhoto(photo: Photo): Long

    @Query("SELECT * FROM photos WHERE orderId = :orderId")
    suspend fun getPhotosForOrder(orderId: Int): List<Photo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPrinterReference(reference: PrinterReference): Long

    @Query("SELECT * FROM printer_references ORDER BY timestamp DESC")
    suspend fun getAllPrinterReferences(): List<PrinterReference>

    @Delete
    suspend fun deletePrinterReference(reference: PrinterReference): Int

    @Query("SELECT * FROM `orders` WHERE id = :orderId")
    suspend fun getOrderById(orderId: Int): Order?

    @Delete
    suspend fun deleteOrder(order: Order): Int

    @Query("DELETE FROM `orders` WHERE date BETWEEN :start AND :end")
    suspend fun deleteOrdersBetween(start: Long, end: Long): Int

    @Query("DELETE FROM `orders`")
    suspend fun deleteAllOrders(): Int

    @Transaction
    suspend fun deleteOrderAndItems(order: Order): Boolean {
        deleteOrder(order)
        // OrderItems should be deleted via ForeignKey CASCADE, but we can be explicit if needed
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: SettlementHistory): Long

    @Transaction
    suspend fun recordOrderAtomic(
        customer: CustomerEntity,
        items: List<OrderItem>,
        total: Double,
        requestedPaymentMethod: String,
        appliedCredit: Double,
        currentTime: Long,
        receivedAmount: Double? = null
    ): Int {
        // 1. Stock Deduction
        for (item in items) {
            val affected = safeDeductStock(item.serviceName, item.quantity)
            val stockItem = getStockItemByName(item.serviceName)
            if (stockItem != null && affected == 0) {
                throw Exception("Insufficient stock for ${item.serviceName}")
            }
        }

        // 2. Authoritative Financial Calculation (Inside Transaction)
        val previousBalance = getAuthoritativeCustomerBalance(customer.id)
        val availableCredit = if (previousBalance < 0) -previousBalance else 0.0
        
        val cashPaid = if (requestedPaymentMethod == "OWES_ME") 0.0 else total - appliedCredit
        val creditUsed = Math.min(availableCredit, Math.max(0.0, total - cashPaid))
        
        val finalPaidAmount = cashPaid + creditUsed
        val transactionAmount = total - cashPaid // The amount added to the customer's account (Revenue)
        val newBalance = previousBalance + transactionAmount
        
        val finalPaymentMethod = if (requestedPaymentMethod == "OWES_ME") {
            if (creditUsed > 0) "CREDIT" else "NONE"
        } else {
            if (creditUsed > 0) "${requestedPaymentMethod}_MIXED" else requestedPaymentMethod
        }
        
        val finalPaymentStatus = when {
            finalPaidAmount >= total -> "PAID"
            finalPaidAmount > 0 -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }

        // 3. Insert Order
        val order = Order(
            totalAmount = total,
            date = currentTime,
            customerName = customer.displayName,
            customerId = customer.id,
            paidAmount = finalPaidAmount,
            paymentMethod = finalPaymentMethod,
            previousBalance = previousBalance,
            transactionAmount = transactionAmount,
            newBalance = newBalance,
            paymentStatus = finalPaymentStatus,
            orderStatus = "ACTIVE"
        )
        
        val orderId = insertOrder(order).toInt()
        val itemsWithOrderId = items.map { it.copy(orderId = orderId) }
        insertOrderItems(itemsWithOrderId)

        // 4. Ledger Entries
        // Entry 1: The Order (Revenue/Debt Creation)
        val orderBalanceBefore = previousBalance
        val orderBalanceAfter = previousBalance + total
        
        insertSettlement(
            SettlementHistory(
                customerName = order.customerName,
                customerId = order.customerId,
                balanceBefore = orderBalanceBefore,
                amountPaid = creditUsed, // Mark credit as "paid" for this order audit
                balanceAfter = orderBalanceAfter,
                timestamp = currentTime,
                type = "ORDER",
                note = "Order #$orderId (Total: ₹$total" + (if (creditUsed > 0) ", Credit used: ₹$creditUsed" else "") + ")",
                transactionAmount = total,
                newBalance = orderBalanceAfter,
                originId = orderId,
                ledgerEntryType = "ORDER_POST"
            )
        )

        // Entry 2: Payment at counter (if any)
        if (cashPaid > 0) {
            val paymentBalanceBefore = orderBalanceAfter
            val paymentBalanceAfter = orderBalanceAfter - cashPaid
            
            insertSettlement(
                SettlementHistory(
                    customerName = order.customerName,
                    customerId = order.customerId,
                    balanceBefore = paymentBalanceBefore,
                    amountPaid = cashPaid,
                    balanceAfter = paymentBalanceAfter,
                    timestamp = currentTime,
                    type = "PAYMENT",
                    ledgerEntryType = "PAYMENT",
                    note = "Payment for Order #$orderId via $requestedPaymentMethod",
                    transactionAmount = -cashPaid,
                    newBalance = paymentBalanceAfter,
                    originId = orderId,
                    receivedAmount = receivedAmount
                )
            )
        }

        // Entry 3: Credit usage note (Implicit in Entry 1, but we can add a comment to it or a shadow entry)
        // For now, Entry 1 and Entry 2 correctly represent the state. 
        // Example: Bal -70. Order +150. Bal 80. Pay -80. Bal 0.
        
        rebuildCustomerProjection(order.customerId)
        return orderId
    }

    @Transaction
    suspend fun recordPaymentAtomic(orderId: Int, newPaidAmount: Double, status: String, method: String, settlement: SettlementHistory): Boolean {
        updateOrderPaymentStatus(orderId, newPaidAmount, status, method)
        insertSettlement(settlement)
        rebuildCustomerProjection(settlement.customerId)
        return true
    }

    @Transaction
    suspend fun cancelOrderAtomic(orderId: Int, status: String, settlement: SettlementHistory): Boolean {
        val items = getItemsForOrder(orderId)
        for (item in items) {
            restoreStock(item.serviceName, item.quantity)
        }
        updateOrderStatus(orderId, status)
        insertSettlement(settlement)
        rebuildCustomerProjection(settlement.customerId)
        return true
    }

    @Transaction
    suspend fun applyPaymentToCustomerIdAtomic(
        customerId: Long, 
        paymentAmount: Double, 
        paymentMethod: String,
        receivedAmount: Double? = null
    ): Boolean {
        val customer = getCustomerById(customerId) ?: return false
        val currentBalance = getAuthoritativeCustomerBalance(customerId)
        
        val unpaidOrders = getUnpaidOrdersForCustomer(customerId)
        var remainingPayment = paymentAmount
        var runningBalance = currentBalance
        var tenderAccountedFor = false

        android.util.Log.d("PaymentProcess", "applyPaymentToCustomerIdAtomic: Start - Customer: ${customer.displayName}, Payment: $paymentAmount, Initial Balance: $currentBalance")

        for (order in unpaidOrders) {
            if (remainingPayment <= 0) break

            val amountOwed = order.totalAmount - order.paidAmount
            val paymentForThisOrder = if (remainingPayment >= amountOwed) amountOwed else remainingPayment

            val newPaidAmount = order.paidAmount + paymentForThisOrder
            
            val newStatus = if (newPaidAmount >= order.totalAmount) "PAID" else "PARTIALLY_PAID"
            val newMethod = if (order.paymentMethod == "NONE" || order.paymentMethod == "") paymentMethod else if (order.paymentMethod == paymentMethod) paymentMethod else "MIXED"
            
            updateOrderPaymentStatus(order.id, newPaidAmount, newStatus, newMethod)

            val balanceBefore = runningBalance
            runningBalance -= paymentForThisOrder

            android.util.Log.d("PaymentProcess", "Allocating $paymentForThisOrder to Order #${order.id}. New Order Paid: $newPaidAmount, Order Status: $newStatus")

            insertSettlement(
                SettlementHistory(
                    customerName = customer.displayName,
                    customerId = customer.id,
                    balanceBefore = balanceBefore,
                    amountPaid = paymentForThisOrder,
                    balanceAfter = runningBalance,
                    timestamp = System.currentTimeMillis(),
                    type = "PAYMENT",
                    ledgerEntryType = "PAYMENT",
                    note = "Debt Payment for Order #${order.id} via $paymentMethod",
                    transactionAmount = -paymentForThisOrder,
                    newBalance = runningBalance,
                    originId = order.id,
                    receivedAmount = if (!tenderAccountedFor) receivedAmount else null
                )
            )
            tenderAccountedFor = true

            remainingPayment -= paymentForThisOrder
        }

        if (remainingPayment > 0.001) {
            val balanceBefore = runningBalance
            runningBalance -= remainingPayment

            android.util.Log.d("PaymentProcess", "Creating overpayment credit: $remainingPayment. New Balance: $runningBalance")

            insertSettlement(
                SettlementHistory(
                    customerName = customer.displayName,
                    customerId = customer.id,
                    balanceBefore = balanceBefore,
                    amountPaid = remainingPayment,
                    balanceAfter = runningBalance,
                    timestamp = System.currentTimeMillis(),
                    type = "PAYMENT",
                    ledgerEntryType = "CREDIT",
                    note = "Overpayment Credit via $paymentMethod",
                    transactionAmount = -remainingPayment,
                    newBalance = runningBalance,
                    receivedAmount = if (!tenderAccountedFor) receivedAmount else null
                )
            )
        } else if (!tenderAccountedFor && receivedAmount != null) {
            // Case where debt was 0 or something but money was received (unlikely in this flow but for safety)
            insertSettlement(
                SettlementHistory(
                    customerName = customer.displayName,
                    customerId = customer.id,
                    balanceBefore = runningBalance,
                    amountPaid = 0.0,
                    balanceAfter = runningBalance,
                    timestamp = System.currentTimeMillis(),
                    type = "PAYMENT",
                    ledgerEntryType = "PAYMENT",
                    note = "Physical Tender recorded",
                    transactionAmount = 0.0,
                    newBalance = runningBalance,
                    receivedAmount = receivedAmount
                )
            )
        }
        
        rebuildCustomerProjection(customer.id)
        android.util.Log.d("PaymentProcess", "applyPaymentToCustomerIdAtomic: Finished - Final Balance: $runningBalance")
        return true
    }

    @Transaction
    suspend fun rebuildCustomerProjection(customerId: Long): Boolean {
        val customer = getCustomerById(customerId) ?: return false
        val calculatedBalance = getAuthoritativeCustomerBalance(customerId)
        
        insertOrUpdateDebtorCredit(
            DebtorCredit(
                customerId = customer.id,
                customerName = customer.displayName,
                amount = calculatedBalance,
                lastUpdated = System.currentTimeMillis(),
                phoneNumber = null
            )
        )
        return true
    }

    @Transaction
    suspend fun reconcileBeautyAccountAtomic() {
        val all = getAllBeautyTransactions().sortedBy { it.timestamp }
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
            updateBeautyTransaction(updated)
            runningBalance = newBalance
        }
    }

    @Transaction
    suspend fun adjustBalanceAtomic(settlement: SettlementHistory): Boolean {
        insertSettlement(settlement)
        rebuildCustomerProjection(settlement.customerId)
        return true
    }

    @Transaction
    suspend fun recordOrderWithWalletAtomic(
        customer: CustomerEntity,
        items: List<OrderItem>,
        total: Double,
        requestedPaymentMethod: String,
        appliedCredit: Double,
        currentTime: Long,
        receivedAmount: Double? = null,
        walletNote: String? = null
    ): Int {
        val orderId = recordOrderAtomic(customer, items, total, requestedPaymentMethod, appliedCredit, currentTime, receivedAmount)
        
        val cashPaid = if (requestedPaymentMethod == "OWES_ME") 0.0 else total - appliedCredit
        if (requestedPaymentMethod == "UPI" && cashPaid > 0) {
            insertBeautyTransactionAtomic(cashPaid, "ADD", walletNote ?: "Direct Pay - Order #$orderId - ${customer.displayName}")
        }
        
        return orderId
    }

    @Transaction
    suspend fun insertBeautyTransactionAtomic(amount: Double, type: String, note: String? = null) {
        val previousBalance = getAuthoritativeWalletBalance()
        val transactionAmount = when (type) {
            "ADD" -> amount
            "RETURN" -> -amount
            "RESET" -> -previousBalance
            else -> amount
        }
        val newBalance = if (type == "RESET") 0.0 else previousBalance + transactionAmount

        insertBeautyTransaction(
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

    @Transaction
    suspend fun applyPaymentToCustomerIdWithWalletAtomic(
        customerId: Long, 
        paymentAmount: Double, 
        paymentMethod: String,
        receivedAmount: Double? = null
    ): Boolean {
        val success = applyPaymentToCustomerIdAtomic(customerId, paymentAmount, paymentMethod, receivedAmount)
        if (success && paymentMethod == "UPI") {
            val customer = getCustomerById(customerId)
            insertBeautyTransactionAtomic(paymentAmount, "ADD", "Debt Settlement - ${customer?.displayName ?: "Unknown"}")
        }
        return success
    }

    @Transaction
    suspend fun recordPaymentWithWalletAtomic(
        orderId: Int, 
        newPaidAmount: Double, 
        status: String, 
        method: String, 
        settlement: SettlementHistory,
        walletDelta: Double
    ): Boolean {
        recordPaymentAtomic(orderId, newPaidAmount, status, method, settlement)
        if (method == "UPI" && walletDelta > 0) {
            insertBeautyTransactionAtomic(walletDelta, "ADD", "Payment Order #$orderId - ${settlement.customerName}")
        }
        return true
    }

    @Transaction
    suspend fun cancelOrderWithWalletAtomic(
        orderId: Int, 
        status: String, 
        settlement: SettlementHistory,
        walletReturnAmount: Double
    ): Boolean {
        cancelOrderAtomic(orderId, status, settlement)
        if (walletReturnAmount > 0) {
             insertBeautyTransactionAtomic(walletReturnAmount, "RETURN", "Order Cancelled #$orderId - ${settlement.customerName}")
        }
        return true
    }

    @Query("""
        SELECT (
            SELECT amount FROM debtor_credits WHERE customerId = :customerId
        ) == (
            SELECT IFNULL(SUM(transactionAmount), 0.0) FROM settlement_history WHERE customerId = :customerId
        )
    """)
    suspend fun verifyCustomerBalance(customerId: Long): Boolean

    @Query("SELECT * FROM settlement_history ORDER BY timestamp DESC")
    suspend fun getAllSettlements(): List<SettlementHistory>

    @Query("SELECT * FROM settlement_history")
    suspend fun getAllSettlementHistoryOnce(): List<SettlementHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSettlements(settlements: List<SettlementHistory>): List<Long>

    @Query("DELETE FROM settlement_history")
    suspend fun clearSettlementHistory(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExternalLedger(entry: ExternalLedger): Long

    @Query("SELECT SUM(amount) FROM external_ledger")
    suspend fun getExternalBalance(): Double?

    // --- Authoritative customer balance derived from full ledger ---
    @Query("""
        SELECT CASE 
            WHEN EXISTS (SELECT 1 FROM settlement_history WHERE customerId = :customerId)
            THEN IFNULL((SELECT SUM(transactionAmount) FROM settlement_history WHERE customerId = :customerId), 0.0)
            ELSE IFNULL((SELECT SUM(totalAmount - paidAmount) FROM orders WHERE customerId = :customerId AND orderStatus = 'ACTIVE'), 0.0)
        END
    """)
    suspend fun getAuthoritativeCustomerBalance(customerId: Long): Double

    // --- Authoritative wallet/beauty balance derived from full digital history ---
    @Query("SELECT IFNULL(SUM(transactionAmount), 0.0) FROM beauty_transactions")
    suspend fun getAuthoritativeWalletBalance(): Double

    // --- Corrected cash-in-hand that includes ledger payments and subtracts cash expenses ---
    @Query("""
        SELECT (
            IFNULL((SELECT SUM(settledAmount) FROM settlement_history WHERE ledgerEntryType IN ('PAYMENT', 'CREDIT') AND (note IS NULL OR note NOT LIKE '%UPI%')), 0.0)
            - IFNULL((SELECT SUM(amount) FROM expenses WHERE paymentMethod = 'CASH'), 0.0)
        )
    """)
    fun getAuthoritativeCashInHandFlow(): Flow<Double?>

    // --- Corrected receivables derived from full ledger ---
    @Query("""
        SELECT CASE 
            WHEN EXISTS (SELECT 1 FROM settlement_history)
            THEN IFNULL((SELECT SUM(transactionAmount) FROM settlement_history), 0.0)
            ELSE IFNULL((SELECT SUM(totalAmount - paidAmount) FROM orders WHERE orderStatus = 'ACTIVE'), 0.0)
        END
    """)
    fun getAuthoritativeTotalReceivablesFlow(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeautyTransaction(transaction: BeautyTransaction): Long

    @Query("SELECT * FROM `beauty_transactions` ORDER BY timestamp DESC")
    fun getAllBeautyTransactionsFlow(): Flow<List<BeautyTransaction>>

    @Query("SELECT * FROM `beauty_transactions`")
    suspend fun getAllBeautyTransactions(): List<BeautyTransaction>

    @Query("SELECT IFNULL(SUM(transactionAmount), 0.0) FROM `beauty_transactions`")
    fun getBeautyBalanceFlow(): Flow<Double?>

    @Query("SELECT IFNULL(SUM(transactionAmount), 0.0) FROM `beauty_transactions`")
    suspend fun getBeautyBalance(): Double?

    @Query("SELECT IFNULL(SUM(transactionAmount), 0.0) FROM `beauty_transactions`")
    suspend fun getCurrentBeautyBalance(): Double

    @Delete
    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction): Int

    @Query("SELECT * FROM `beauty_transactions` WHERE timestamp > :timestamp OR (timestamp = :timestamp AND id > :id) ORDER BY timestamp ASC, id ASC")
    suspend fun getBeautyTransactionsAfter(timestamp: Long, id: Int): List<BeautyTransaction>

    @Update
    suspend fun updateBeautyTransaction(transaction: BeautyTransaction): Int

    @Query("SELECT newBalance FROM `beauty_transactions` WHERE timestamp < :timestamp OR (timestamp = :timestamp AND id < :id) ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getBeautyBalanceBefore(timestamp: Long, id: Int): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense): Long

    @Query("SELECT * FROM `expenses` ORDER BY timestamp DESC")
    fun getAllExpensesFlow(): Flow<List<Expense>>

    @Query("SELECT SUM(amount) FROM `expenses`")
    suspend fun getTotalExpenses(): Double?

    @Query("DELETE FROM `expenses` WHERE id = :id")
    suspend fun deleteExpense(id: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllExpenses(expenses: List<Expense>): List<Long>

    @Query("DELETE FROM `expenses`")
    suspend fun clearExpenses(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockItem(item: StockItem): Long

    @Query("SELECT * FROM `stock_items` ORDER BY name ASC")
    fun getAllStockItemsFlow(): Flow<List<StockItem>>

    @Query("SELECT * FROM `stock_items` WHERE currentQuantity <= lowStockThreshold")
    fun getLowStockItemsFlow(): Flow<List<StockItem>>

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity - :quantity WHERE name = :name AND currentQuantity >= :quantity")
    suspend fun safeDeductStock(name: String, quantity: Int): Int

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity - :quantity WHERE name = :name")
    suspend fun deductStockByName(name: String, quantity: Int): Int

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity + :quantity WHERE name = :name")
    suspend fun restoreStock(name: String, quantity: Int): Int

    @Query("SELECT * FROM `stock_items` WHERE name = :name")
    suspend fun getStockItemByName(name: String): StockItem?

    @Delete
    suspend fun deleteStockItem(item: StockItem): Int

    @Query("UPDATE `orders` SET orderStatus = :status WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: Int, status: String): Int
}
