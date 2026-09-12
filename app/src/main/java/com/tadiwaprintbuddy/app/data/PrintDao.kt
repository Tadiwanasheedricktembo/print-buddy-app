package com.tadiwaprintbuddy.app.data

import android.util.Log
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

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

    // Pull raw data for high-precision Kotlin summation
    @Query("SELECT paidAmount FROM `orders` WHERE orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = ''")
    fun getAllActivePaidAmountsFlow(): Flow<List<BigDecimal>>

    @Query("SELECT COUNT(*) FROM `orders` WHERE orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = ''")
    fun getTotalOrdersFlow(): Flow<Int>

    @Query("SELECT paidAmount FROM `orders` WHERE (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '') AND date BETWEEN :start AND :end")
    suspend fun getPaidAmountsBetween(start: Long, end: Long): List<BigDecimal>

    @Query("SELECT totalAmount FROM `orders` WHERE (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '') AND date BETWEEN :start AND :end")
    suspend fun getTotalAmountsBetween(start: Long, end: Long): List<BigDecimal>

    @Query("SELECT paidAmount FROM `orders` WHERE (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '') AND date BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getPaidAmountsByMethodBetween(start: Long, end: Long, method: String): List<BigDecimal>

    @Query("""
        SELECT sh.settledAmount 
        FROM `settlement_history` sh
        LEFT JOIN `orders` o ON sh.originId = o.id
        WHERE sh.timestamp BETWEEN :start AND :end 
        AND sh.ledgerEntryType IN ('PAYMENT', 'CREDIT')
        AND (sh.originId IS NULL OR (o.id IS NOT NULL AND (o.orderStatus = 'ACTIVE' OR o.orderStatus IS NULL OR o.orderStatus = '')))
        AND (:method = 'ALL' 
             OR (:method = 'ALL_PAYMENTS' AND sh.ledgerEntryType = 'PAYMENT')
             OR (:method = 'UPI' AND sh.note LIKE '%UPI%') 
             OR (:method = 'CASH' AND (sh.note IS NOT NULL AND sh.note NOT LIKE '%UPI%'))
             OR (:method = 'CREDIT' AND sh.ledgerEntryType = 'CREDIT'))
    """)
    suspend fun getFilteredSettledAmounts(start: Long, end: Long, method: String): List<BigDecimal>

    @Query("SELECT amount FROM `expenses` WHERE timestamp BETWEEN :start AND :end")
    suspend fun getExpenseAmountsBetween(start: Long, end: Long): List<BigDecimal>

    @Query("SELECT amount FROM `expenses` WHERE timestamp BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getExpenseAmountsByMethodBetween(start: Long, end: Long, method: String): List<BigDecimal>

    @Query("SELECT amount FROM `expenses`")
    suspend fun getAllExpenseAmounts(): List<BigDecimal>

    @Query("SELECT COUNT(*) FROM `orders` WHERE (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '') AND date BETWEEN :start AND :end")
    suspend fun getOrdersCountBetween(start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM `orders` WHERE (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '') AND date BETWEEN :start AND :end AND (:method = 'ALL' OR (:method = 'PAID_ONLY' AND paymentMethod != 'NONE') OR paymentMethod = :method)")
    suspend fun getOrdersCountByMethodBetween(start: Long, end: Long, method: String): Int

    @Query("SELECT (totalAmount - paidAmount) FROM `orders` WHERE (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '')")
    suspend fun getAllReceivableDiffs(): List<BigDecimal>

    @Query("SELECT COUNT(*) FROM debtor_credits WHERE amount != '0' AND amount != '0.0' AND amount != '0.00'")
    suspend fun getDebtorsCount(): Int

    @Query("""
        SELECT MIN(sh.timestamp) as timestamp, 
               SUM(CAST(sh.settledAmount AS REAL)) as amount 
        FROM `settlement_history` sh
        LEFT JOIN `orders` o ON sh.originId = o.id
        WHERE sh.timestamp BETWEEN :start AND :end
        AND sh.ledgerEntryType IN ('PAYMENT', 'CREDIT')
        AND (sh.originId IS NULL OR (o.id IS NOT NULL AND (o.orderStatus = 'ACTIVE' OR o.orderStatus IS NULL OR o.orderStatus = '')))
        AND (:method = 'ALL' 
             OR (:method = 'ALL_PAYMENTS' AND sh.ledgerEntryType = 'PAYMENT')
             OR (:method = 'UPI' AND sh.note LIKE '%UPI%') 
             OR (:method = 'CASH' AND (sh.note IS NOT NULL AND sh.note NOT LIKE '%UPI%'))
             OR (:method = 'CREDIT' AND sh.ledgerEntryType = 'CREDIT'))
        GROUP BY strftime('%Y-%m-%d', datetime(sh.timestamp / 1000, 'unixepoch', 'localtime'))
    """)
    suspend fun getSettledRevenueTrendByMethod(start: Long, end: Long, method: String): List<TrendPoint>

    @Query("""
        SELECT CASE 
                 WHEN sh.ledgerEntryType = 'CREDIT' THEN 'CREDIT'
                 WHEN sh.note LIKE '%UPI%' THEN 'UPI' 
                 ELSE 'CASH' 
               END as type, 
               SUM(CAST(sh.settledAmount AS REAL)) as total 
        FROM `settlement_history` sh
        LEFT JOIN `orders` o ON sh.originId = o.id
        WHERE sh.timestamp BETWEEN :start AND :end
        AND sh.ledgerEntryType IN ('PAYMENT', 'CREDIT')
        AND (sh.originId IS NULL OR (o.id IS NOT NULL AND (o.orderStatus = 'ACTIVE' OR o.orderStatus IS NULL OR o.orderStatus = '')))
        GROUP BY type
    """)
    suspend fun getSettledPaymentBreakdownBetween(start: Long, end: Long): List<PaymentBreakdown>

    @Query("""
        SELECT serviceName as category, SUM(CAST(price AS REAL) * quantity) as total 
        FROM `OrderItem` 
        JOIN `orders` ON orders.id = OrderItem.orderId 
        WHERE (orders.orderStatus = 'ACTIVE' OR orders.orderStatus IS NULL OR orders.orderStatus = '') 
        AND orders.date BETWEEN :start AND :end 
        GROUP BY serviceName
    """)
    suspend fun getServiceBreakdownBetween(start: Long, end: Long): List<CategoryRevenue>

    @Query("SELECT category as category, SUM(CAST(amount AS REAL)) as total FROM `expenses` WHERE timestamp BETWEEN :start AND :end GROUP BY category")
    suspend fun getExpenseBreakdownBetween(start: Long, end: Long): List<CategoryRevenue>

    @Query("SELECT * FROM `beauty_transactions` WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getFilteredBeautyTransactions(start: Long, end: Long): Flow<List<BeautyTransaction>>

    @Query("SELECT amount FROM `beauty_transactions` WHERE type = 'ADD' AND timestamp BETWEEN :start AND :end")
    suspend fun getBeautyReceivedAmounts(start: Long, end: Long): List<BigDecimal>

    @Query("SELECT amount FROM `beauty_transactions` WHERE type = 'RETURN' AND timestamp BETWEEN :start AND :end")
    suspend fun getBeautyReturnedAmounts(start: Long, end: Long): List<BigDecimal>

    @Query("SELECT transactionAmount FROM `beauty_transactions` WHERE timestamp BETWEEN :start AND :end")
    suspend fun getBeautyTransactionAmountsBetween(start: Long, end: Long): List<BigDecimal>

    @Query("SELECT transactionAmount FROM `beauty_transactions`")
    suspend fun getAllBeautyTransactionAmounts(): List<BigDecimal>

    @Query("SELECT transactionAmount FROM `beauty_transactions`")
    fun getBeautyTransactionAmountsFlow(): Flow<List<BigDecimal>>

    @Query("SELECT COUNT(*) FROM `beauty_transactions` WHERE timestamp BETWEEN :start AND :end")
    suspend fun getBeautyTransactionCountBetween(start: Long, end: Long): Int

    @Query("""
        SELECT serviceName as category, SUM(CAST(price AS REAL) * quantity) as total 
        FROM `OrderItem` 
        JOIN `orders` ON orders.id = OrderItem.orderId 
        WHERE (orders.orderStatus = 'ACTIVE' OR orders.orderStatus IS NULL OR orders.orderStatus = '') 
        GROUP BY serviceName
    """)
    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>>

    @Query("SELECT * FROM `orders` WHERE paidAmount != totalAmount AND (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '')")
    suspend fun getUnpaidOrders(): List<Order>

    @Query("UPDATE `orders` SET paidAmount = :newPaidAmount, paymentStatus = :status, paymentMethod = :method, updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun updateOrderPaymentStatus(orderId: Int, newPaidAmount: BigDecimal, status: String, method: String, updatedAt: Long): Int

    @Query("SELECT customerId, customerName, '0.0' as totalBalance, 'OWES' as type FROM `orders` WHERE (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '') GROUP BY customerId")
    suspend fun getDebtorGroups(): List<DebtorSummary>

    @Query("SELECT * FROM `orders` WHERE customerId = :customerId AND paidAmount != totalAmount AND (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '') ORDER BY date ASC")
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
        // Since we don't have a deletedAt on Customer, we might need one for sync.
        // For now, let's mark it as a defect that DELETE sync for Customer isn't fully implemented in DAO.
        deleteDebtorCredit(customerId)
        deleteOrdersForCustomer(customerId)
        deleteSettlementsForCustomer(customerId)
        deleteCustomer(customerId)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomerInternal(customer: CustomerEntity): Long

    @Transaction
    suspend fun insertCustomer(customer: CustomerEntity): Long {
        val id = insertCustomerInternal(customer)
        insertSyncEvent(SyncOutbox(entityType = "CUSTOMER", entitySyncId = customer.syncId, operation = "CREATE"))
        return id
    }

    @Query("SELECT * FROM customers WHERE normalizedName = :normalizedName")
    suspend fun getCustomerByNormalizedName(normalizedName: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers")
    fun getAllCustomersFlow(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Query("SELECT transactionAmount FROM settlement_history WHERE customerId = :customerId")
    suspend fun getTransactionAmountsForCustomer(customerId: Long): List<BigDecimal>

    @Query("SELECT (totalAmount - paidAmount) FROM `orders` WHERE customerId = :customerId AND (orderStatus = 'ACTIVE' OR orderStatus IS NULL OR orderStatus = '')")
    suspend fun getUnpaidOrderDiffsForCustomer(customerId: Long): List<BigDecimal>

    @Query("UPDATE customers SET displayName = :newName, normalizedName = :normalized, updatedAt = :updatedAt WHERE id = :customerId")
    suspend fun updateCustomerIdentityInternal(customerId: Long, newName: String, normalized: String, updatedAt: Long): Int

    @Transaction
    suspend fun updateCustomerIdentity(customerId: Long, newName: String, normalized: String): Int {
        val now = System.currentTimeMillis()
        val affected = updateCustomerIdentityInternal(customerId, newName, normalized, now)
        val customer = getCustomerById(customerId)
        if (customer != null) {
            insertSyncEvent(SyncOutbox(entityType = "CUSTOMER", entitySyncId = customer.syncId, operation = "UPDATE"))
        }
        return affected
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPhotoInternal(photo: Photo): Long

    @Transaction
    suspend fun addPhoto(photo: Photo): Long {
        val id = addPhotoInternal(photo)
        insertSyncEvent(SyncOutbox(entityType = "PHOTO", entitySyncId = photo.syncId, operation = "CREATE"))
        return id
    }

    @Query("SELECT * FROM photos WHERE orderId = :orderId")
    suspend fun getPhotosForOrder(orderId: Int): List<Photo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPrinterReferenceInternal(reference: PrinterReference): Long

    @Transaction
    suspend fun addPrinterReference(reference: PrinterReference): Long {
        val id = addPrinterReferenceInternal(reference)
        insertSyncEvent(SyncOutbox(entityType = "PRINTER_REFERENCE", entitySyncId = reference.syncId, operation = "CREATE"))
        return id
    }

    @Query("SELECT * FROM printer_references ORDER BY timestamp DESC")
    suspend fun getAllPrinterReferences(): List<PrinterReference>

    @Delete
    suspend fun deletePrinterReferenceInternal(reference: PrinterReference): Int

    @Transaction
    suspend fun deletePrinterReference(reference: PrinterReference): Int {
        val now = System.currentTimeMillis()
        val affected = deletePrinterReferenceInternal(reference)
        insertSyncEvent(SyncOutbox(entityType = "PRINTER_REFERENCE", entitySyncId = reference.syncId, operation = "DELETE"))
        return affected
    }

    @Query("SELECT * FROM `orders` WHERE id = :orderId")
    suspend fun getOrderById(orderId: Int): Order?

    @Delete
    suspend fun deleteOrderInternal(order: Order): Int

    @Transaction
    suspend fun deleteOrder(order: Order): Int {
        val affected = deleteOrderInternal(order)
        insertSyncEvent(SyncOutbox(entityType = "ORDER", entitySyncId = order.syncId, operation = "DELETE"))
        return affected
    }

    @Query("DELETE FROM `orders` WHERE date BETWEEN :start AND :end")
    suspend fun deleteOrdersBetween(start: Long, end: Long): Int

    @Query("DELETE FROM `orders`")
    suspend fun deleteAllOrders(): Int

    @Transaction
    suspend fun deleteOrderAndItems(order: Order): Boolean {
        deleteOrder(order)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: SettlementHistory): Long

    @Transaction
    suspend fun recordOrderAtomic(
        customer: CustomerEntity,
        items: List<OrderItem>,
        total: BigDecimal,
        requestedPaymentMethod: String,
        appliedCredit: BigDecimal,
        currentTime: Long,
        receivedAmount: BigDecimal? = null
    ): Int {
        for (item in items) {
            val affected = safeDeductStock(item.serviceName, item.quantity)
            if (affected == 0) {
                 val stockItem = getStockItemByName(item.serviceName)
                 if (stockItem != null) throw Exception("Insufficient stock for ${item.serviceName}")
            }
        }

        val previousBalance = getAuthoritativeCustomerBalance(customer.id)
        val availableCredit = if (previousBalance < BigDecimal.ZERO) previousBalance.negate() else BigDecimal.ZERO
        
        val cashPaid = if (requestedPaymentMethod == "OWES_ME") BigDecimal.ZERO else total.subtract(appliedCredit)
        val creditUsed = if (total > BigDecimal.ZERO) availableCredit.min(total.subtract(cashPaid).max(BigDecimal.ZERO)) else BigDecimal.ZERO
        
        val finalPaidAmount = cashPaid.add(creditUsed)
        val transactionAmount = total.subtract(cashPaid)
        val newBalance = previousBalance.add(transactionAmount)
        
        val finalPaymentMethod = if (requestedPaymentMethod == "OWES_ME") {
            if (creditUsed > BigDecimal.ZERO) "CREDIT" else "NONE"
        } else {
            if (creditUsed > BigDecimal.ZERO) "${requestedPaymentMethod}_MIXED" else requestedPaymentMethod
        }
        
        val finalPaymentStatus = when {
            finalPaidAmount >= total -> "PAID"
            finalPaidAmount > BigDecimal.ZERO -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }

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
            orderStatus = "ACTIVE",
            customerSyncId = customer.syncId,
            updatedAt = currentTime
        )
        
        val orderId = insertOrder(order).toInt()
        insertOrderItems(items.map { it.copy(orderId = orderId, orderSyncId = order.syncId) })

        insertSyncEvent(SyncOutbox(entityType = "ORDER", entitySyncId = order.syncId, operation = "CREATE"))

        val settlement1 = SettlementHistory(
            customerName = order.customerName,
            customerId = order.customerId,
            balanceBefore = previousBalance,
            amountPaid = creditUsed,
            balanceAfter = previousBalance.add(total),
            timestamp = currentTime,
            type = "ORDER",
            note = "Order #$orderId (Total: ₹$total)",
            transactionAmount = total,
            newBalance = previousBalance.add(total),
            originId = orderId,
            ledgerEntryType = "ORDER_POST",
            customerSyncId = customer.syncId,
            originSyncId = order.syncId,
            updatedAt = currentTime
        )
        insertSettlement(settlement1)
        insertSyncEvent(SyncOutbox(entityType = "SETTLEMENT", entitySyncId = settlement1.syncId, operation = "CREATE"))

        if (cashPaid > BigDecimal.ZERO) {
            val settlement2 = SettlementHistory(
                customerName = order.customerName,
                customerId = order.customerId,
                balanceBefore = settlement1.balanceAfter,
                amountPaid = cashPaid,
                balanceAfter = settlement1.balanceAfter.subtract(cashPaid),
                timestamp = currentTime,
                type = "PAYMENT",
                ledgerEntryType = "PAYMENT",
                note = "Payment for Order #$orderId via $requestedPaymentMethod",
                transactionAmount = cashPaid.negate(),
                newBalance = settlement1.balanceAfter.subtract(cashPaid),
                originId = orderId,
                receivedAmount = receivedAmount,
                customerSyncId = customer.syncId,
                originSyncId = order.syncId,
                updatedAt = currentTime
            )
            insertSettlement(settlement2)
            insertSyncEvent(SyncOutbox(entityType = "SETTLEMENT", entitySyncId = settlement2.syncId, operation = "CREATE"))
        }
        
        rebuildCustomerProjection(order.customerId)
        return orderId
    }

    @Transaction
    suspend fun recordPaymentAtomic(orderId: Int, newPaidAmount: BigDecimal, status: String, method: String, settlement: SettlementHistory): Boolean {
        val now = System.currentTimeMillis()
        updateOrderPaymentStatus(orderId, newPaidAmount, status, method, now)
        val order = getOrderById(orderId)
        val customer = getCustomerById(settlement.customerId)
        val enriched = settlement.copy(customerSyncId = customer?.syncId ?: "", originSyncId = order?.syncId, updatedAt = now)
        insertSettlement(enriched)
        insertSyncEvent(SyncOutbox(entityType = "SETTLEMENT", entitySyncId = enriched.syncId, operation = "CREATE"))
        if (order != null) insertSyncEvent(SyncOutbox(entityType = "ORDER", entitySyncId = order.syncId, operation = "UPDATE"))
        rebuildCustomerProjection(settlement.customerId)
        return true
    }

    @Transaction
    suspend fun cancelOrderAtomic(orderId: Int, status: String, settlement: SettlementHistory): Boolean {
        val now = System.currentTimeMillis()
        val items = getItemsForOrder(orderId)
        for (item in items) restoreStock(item.serviceName, item.quantity)
        updateOrderStatusInternal(orderId, status, now)
        val order = getOrderById(orderId)
        val customer = getCustomerById(settlement.customerId)
        val enriched = settlement.copy(customerSyncId = customer?.syncId ?: "", originSyncId = order?.syncId, updatedAt = now)
        insertSettlement(enriched)
        insertSyncEvent(SyncOutbox(entityType = "SETTLEMENT", entitySyncId = enriched.syncId, operation = "CREATE"))
        if (order != null) insertSyncEvent(SyncOutbox(entityType = "ORDER", entitySyncId = order.syncId, operation = "UPDATE"))
        rebuildCustomerProjection(settlement.customerId)
        return true
    }

    @Transaction
    suspend fun applyPaymentToCustomerIdAtomic(
        customerId: Long, 
        paymentAmount: BigDecimal, 
        paymentMethod: String,
        receivedAmount: BigDecimal? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        val customer = getCustomerById(customerId) ?: return false
        val currentBalance = getAuthoritativeCustomerBalance(customerId)
        val unpaidOrders = getUnpaidOrdersForCustomer(customerId)
        var remainingPayment = paymentAmount
        var runningBalance = currentBalance
        var tenderAccountedFor = false

        for (order in unpaidOrders) {
            if (remainingPayment <= BigDecimal.ZERO) break
            val amountOwed = order.totalAmount.subtract(order.paidAmount)
            val paymentForThisOrder = if (remainingPayment >= amountOwed) amountOwed else remainingPayment
            val newPaidAmount = order.paidAmount.add(paymentForThisOrder)
            updateOrderPaymentStatus(order.id, newPaidAmount, if (newPaidAmount >= order.totalAmount) "PAID" else "PARTIALLY_PAID", paymentMethod, now)
            
            val balanceBefore = runningBalance
            runningBalance = runningBalance.subtract(paymentForThisOrder)
            val settlement = SettlementHistory(
                customerName = customer.displayName, customerId = customer.id,
                balanceBefore = balanceBefore, amountPaid = paymentForThisOrder, balanceAfter = runningBalance,
                timestamp = now, type = "PAYMENT", ledgerEntryType = "PAYMENT",
                note = "Debt Payment for Order #${order.id}", transactionAmount = paymentForThisOrder.negate(),
                newBalance = runningBalance, originId = order.id, receivedAmount = if (!tenderAccountedFor) receivedAmount else null,
                customerSyncId = customer.syncId, originSyncId = order.syncId, updatedAt = now
            )
            insertSettlement(settlement)
            insertSyncEvent(SyncOutbox(entityType = "SETTLEMENT", entitySyncId = settlement.syncId, operation = "CREATE"))
            insertSyncEvent(SyncOutbox(entityType = "ORDER", entitySyncId = order.syncId, operation = "UPDATE"))
            tenderAccountedFor = true
            remainingPayment = remainingPayment.subtract(paymentForThisOrder)
        }

        if (remainingPayment > BigDecimal("0.001")) {
            val balanceBefore = runningBalance
            runningBalance = runningBalance.subtract(remainingPayment)
            val settlement = SettlementHistory(
                customerName = customer.displayName, customerId = customer.id,
                balanceBefore = balanceBefore, amountPaid = remainingPayment, balanceAfter = runningBalance,
                timestamp = now, type = "PAYMENT", ledgerEntryType = "CREDIT",
                note = "Overpayment Credit", transactionAmount = remainingPayment.negate(),
                newBalance = runningBalance, receivedAmount = if (!tenderAccountedFor) receivedAmount else null,
                customerSyncId = customer.syncId, updatedAt = now
            )
            insertSettlement(settlement)
            insertSyncEvent(SyncOutbox(entityType = "SETTLEMENT", entitySyncId = settlement.syncId, operation = "CREATE"))
        }
        rebuildCustomerProjection(customer.id)
        return true
    }

    @Transaction
    suspend fun rebuildCustomerProjection(customerId: Long): Boolean {
        val customer = getCustomerById(customerId) ?: return false
        val calculatedBalance = getAuthoritativeCustomerBalance(customerId)
        insertOrUpdateDebtorCredit(DebtorCredit(customerId = customer.id, customerName = customer.displayName, amount = calculatedBalance))
        return true
    }

    @Transaction
    suspend fun reconcileBeautyAccountAtomic() {
        val all = getAllBeautyTransactions().sortedBy { it.timestamp }
        var runningBalance = BigDecimal.ZERO
        for (item in all) {
            val previousBalance = runningBalance
            val transactionAmount = when (item.type) {
                "ADD" -> item.amount
                "RETURN" -> item.amount.negate()
                "RESET" -> previousBalance.negate()
                else -> item.transactionAmount
            }
            val newBalance = if (item.type == "RESET") BigDecimal.ZERO else previousBalance.add(transactionAmount)
            updateBeautyTransactionInternal(item.copy(previousBalance = previousBalance, transactionAmount = transactionAmount, newBalance = newBalance))
            runningBalance = newBalance
        }
    }

    @Transaction
    suspend fun adjustBalanceAtomic(settlement: SettlementHistory): Boolean {
        val now = System.currentTimeMillis()
        val customer = getCustomerById(settlement.customerId)
        val enriched = settlement.copy(customerSyncId = customer?.syncId ?: "", updatedAt = now)
        insertSettlement(enriched)
        insertSyncEvent(SyncOutbox(entityType = "SETTLEMENT", entitySyncId = enriched.syncId, operation = "CREATE"))
        rebuildCustomerProjection(settlement.customerId)
        return true
    }

    @Transaction
    suspend fun recordOrderWithWalletAtomic(
        customer: CustomerEntity,
        items: List<OrderItem>,
        total: BigDecimal,
        requestedPaymentMethod: String,
        appliedCredit: BigDecimal,
        currentTime: Long,
        receivedAmount: BigDecimal? = null
    ): Int {
        val orderId = recordOrderAtomic(customer, items, total, requestedPaymentMethod, appliedCredit, currentTime, receivedAmount)
        val cashPaid = if (requestedPaymentMethod == "OWES_ME") BigDecimal.ZERO else total.subtract(appliedCredit)
        if (requestedPaymentMethod == "UPI" && cashPaid > BigDecimal.ZERO) {
            insertBeautyTransactionAtomic(cashPaid, "ADD", "Direct Pay - Order #$orderId")
        }
        return orderId
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeautyTransactionInternal(transaction: BeautyTransaction): Long

    @Transaction
    suspend fun insertBeautyTransactionAtomic(amount: BigDecimal, type: String, note: String? = null) {
        val now = System.currentTimeMillis()
        val previousBalance = getAuthoritativeWalletBalance()
        val transactionAmount = when (type) {
            "ADD" -> amount
            "RETURN" -> amount.negate()
            "RESET" -> previousBalance.negate()
            else -> amount
        }
        val bt = BeautyTransaction(
            amount = amount, type = type, note = note, 
            previousBalance = previousBalance, transactionAmount = transactionAmount, 
            newBalance = previousBalance.add(transactionAmount),
            updatedAt = now
        )
        insertBeautyTransactionInternal(bt)
        insertSyncEvent(SyncOutbox(entityType = "BEAUTY_TRANSACTION", entitySyncId = bt.syncId, operation = "CREATE"))
    }

    @Transaction
    suspend fun applyPaymentToCustomerIdWithWalletAtomic(
        customerId: Long, 
        paymentAmount: BigDecimal, 
        paymentMethod: String,
        receivedAmount: BigDecimal? = null
    ): Boolean {
        val success = applyPaymentToCustomerIdAtomic(customerId, paymentAmount, paymentMethod, receivedAmount)
        if (success && paymentMethod == "UPI") {
            insertBeautyTransactionAtomic(paymentAmount, "ADD", "Debt Settlement")
        }
        return success
    }

    @Transaction
    suspend fun recordPaymentWithWalletAtomic(
        orderId: Int, 
        newPaidAmount: BigDecimal, 
        status: String, 
        method: String, 
        settlement: SettlementHistory,
        walletDelta: BigDecimal
    ): Boolean {
        recordPaymentAtomic(orderId, newPaidAmount, status, method, settlement)
        if (method == "UPI" && walletDelta > BigDecimal.ZERO) {
            insertBeautyTransactionAtomic(walletDelta, "ADD", "Payment Order #$orderId")
        }
        return true
    }

    @Transaction
    suspend fun cancelOrderWithWalletAtomic(
        orderId: Int, 
        status: String, 
        settlement: SettlementHistory,
        walletReturnAmount: BigDecimal
    ): Boolean {
        cancelOrderAtomic(orderId, status, settlement)
        if (walletReturnAmount > BigDecimal.ZERO) {
             insertBeautyTransactionAtomic(walletReturnAmount, "RETURN", "Order Cancelled #$orderId")
        }
        return true
    }

    @Transaction
    suspend fun getAuthoritativeCustomerBalance(customerId: Long): BigDecimal {
        val transactions = getTransactionAmountsForCustomer(customerId)
        if (transactions.isNotEmpty()) {
            return transactions.fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
        }
        val diffs = getUnpaidOrderDiffsForCustomer(customerId)
        return diffs.fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
    }

    @Transaction
    suspend fun getAuthoritativeWalletBalance(): BigDecimal {
        return getAllBeautyTransactionAmounts().fold(BigDecimal.ZERO) { acc, d -> acc.add(d) }
    }

    @Query("SELECT * FROM settlement_history ORDER BY timestamp DESC")
    suspend fun getAllSettlements(): List<SettlementHistory>

    @Query("SELECT * FROM settlement_history")
    suspend fun getAllSettlementHistoryOnce(): List<SettlementHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSettlements(settlements: List<SettlementHistory>): List<Long>

    @Query("DELETE FROM settlement_history")
    suspend fun clearSettlementHistory(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExternalLedgerInternal(entry: ExternalLedger): Long

    @Transaction
    suspend fun insertExternalLedger(entry: ExternalLedger): Long {
        val id = insertExternalLedgerInternal(entry)
        insertSyncEvent(SyncOutbox(entityType = "EXTERNAL_LEDGER", entitySyncId = entry.syncId, operation = "CREATE"))
        return id
    }

    @Query("SELECT amount FROM external_ledger")
    suspend fun getExternalAmounts(): List<BigDecimal>

    @Query("SELECT * FROM `beauty_transactions` ORDER BY timestamp DESC")
    fun getAllBeautyTransactionsFlow(): Flow<List<BeautyTransaction>>

    @Query("SELECT * FROM `beauty_transactions`")
    suspend fun getAllBeautyTransactions(): List<BeautyTransaction>

    @Delete
    suspend fun deleteBeautyTransactionInternal(transaction: BeautyTransaction): Int

    @Transaction
    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction): Int {
        val affected = deleteBeautyTransactionInternal(transaction)
        insertSyncEvent(SyncOutbox(entityType = "BEAUTY_TRANSACTION", entitySyncId = transaction.syncId, operation = "DELETE"))
        return affected
    }

    @Update
    suspend fun updateBeautyTransactionInternal(transaction: BeautyTransaction): Int

    @Transaction
    suspend fun updateBeautyTransaction(transaction: BeautyTransaction): Int {
        val affected = updateBeautyTransactionInternal(transaction)
        insertSyncEvent(SyncOutbox(entityType = "BEAUTY_TRANSACTION", entitySyncId = transaction.syncId, operation = "UPDATE"))
        return affected
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenseInternal(expense: Expense): Long

    @Transaction
    suspend fun insertExpense(expense: Expense): Long {
        val id = insertExpenseInternal(expense)
        insertSyncEvent(SyncOutbox(entityType = "EXPENSE", entitySyncId = expense.syncId, operation = "CREATE"))
        return id
    }

    @Query("SELECT * FROM `expenses` ORDER BY timestamp DESC")
    fun getAllExpensesFlow(): Flow<List<Expense>>

    @Query("DELETE FROM `expenses` WHERE id = :id")
    suspend fun deleteExpenseInternal(id: Int): Int

    @Transaction
    suspend fun deleteExpense(id: Int): Int {
        // We need syncId for delete outbox, but we only have id. 
        // We'll need to fetch first.
        val expense = getExpenseById(id)
        val affected = deleteExpenseInternal(id)
        if (expense != null) {
            insertSyncEvent(SyncOutbox(entityType = "EXPENSE", entitySyncId = expense.syncId, operation = "DELETE"))
        }
        return affected
    }

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getExpenseById(id: Int): Expense?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllExpenses(expenses: List<Expense>): List<Long>

    @Query("DELETE FROM `expenses`")
    suspend fun clearExpenses(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockItemInternal(item: StockItem): Long

    @Transaction
    suspend fun insertStockItem(item: StockItem): Long {
        val id = insertStockItemInternal(item)
        insertSyncEvent(SyncOutbox(entityType = "STOCK", entitySyncId = item.syncId, operation = "CREATE"))
        return id
    }

    @Query("SELECT * FROM `stock_items` ORDER BY name ASC")
    fun getAllStockItemsFlow(): Flow<List<StockItem>>

    @Query("SELECT * FROM `stock_items` WHERE currentQuantity <= lowStockThreshold")
    fun getLowStockItemsFlow(): Flow<List<StockItem>>

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity - :quantity, updatedAt = :updatedAt WHERE name = :name AND currentQuantity >= :quantity")
    suspend fun safeDeductStockInternal(name: String, quantity: Int, updatedAt: Long): Int

    @Transaction
    suspend fun safeDeductStock(name: String, quantity: Int): Int {
        val now = System.currentTimeMillis()
        val affected = safeDeductStockInternal(name, quantity, now)
        val stock = getStockItemByName(name)
        if (stock != null) {
            insertSyncEvent(SyncOutbox(entityType = "STOCK", entitySyncId = stock.syncId, operation = "UPDATE"))
        }
        return affected
    }

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity + :quantity, updatedAt = :updatedAt WHERE name = :name")
    suspend fun restoreStockInternal(name: String, quantity: Int, updatedAt: Long): Int

    @Transaction
    suspend fun restoreStock(name: String, quantity: Int): Int {
        val now = System.currentTimeMillis()
        val affected = restoreStockInternal(name, quantity, now)
        val stock = getStockItemByName(name)
        if (stock != null) {
            insertSyncEvent(SyncOutbox(entityType = "STOCK", entitySyncId = stock.syncId, operation = "UPDATE"))
        }
        return affected
    }

    @Query("SELECT * FROM `stock_items` WHERE name = :name")
    suspend fun getStockItemByName(name: String): StockItem?

    @Delete
    suspend fun deleteStockItemInternal(item: StockItem): Int

    @Transaction
    suspend fun deleteStockItem(item: StockItem): Int {
        val affected = deleteStockItemInternal(item)
        insertSyncEvent(SyncOutbox(entityType = "STOCK", entitySyncId = item.syncId, operation = "DELETE"))
        return affected
    }

    @Query("UPDATE `orders` SET orderStatus = :status, updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun updateOrderStatusInternal(orderId: Int, status: String, updatedAt: Long): Int

    @Insert
    suspend fun insertSyncEvent(entry: SyncOutbox): Long
}
