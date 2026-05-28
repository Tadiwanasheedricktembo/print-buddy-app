package com.tadiwaprintbuddy.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintDao {

    @Insert
    suspend fun insertOrder(order: Order): Long

    @Insert
    suspend fun insertOrderItems(items: List<OrderItem>)

    @Query("SELECT * FROM orders ORDER BY date DESC")
    suspend fun getAllOrders(): List<Order>

    @Query("SELECT * FROM orders WHERE date BETWEEN :start AND :end ORDER BY date DESC")
    suspend fun getOrdersBetween(start: Long, end: Long): List<Order>

    @Query("SELECT * FROM OrderItem WHERE orderId = :orderId")
    suspend fun getItemsForOrder(orderId: Int): List<OrderItem>

    @Query("SELECT SUM(totalAmount) FROM `orders`")
    fun getTotalRevenueFlow(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM `orders`")
    fun getTotalOrdersFlow(): Flow<Int>

    @Query("SELECT SUM(totalAmount) FROM `orders` WHERE date BETWEEN :start AND :end")
    suspend fun getRevenueBetween(start: Long, end: Long): Double?

    // --- Analytics Filtered Queries ---

    @Query("SELECT IFNULL(SUM(totalAmount), 0.0) FROM `orders` WHERE date BETWEEN :start AND :end AND paymentMethod IN ('CASH', 'UPI')")
    suspend fun getSalesRevenueBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(settledAmount), 0.0) FROM `settlement_history` WHERE timestamp BETWEEN :start AND :end AND type = 'PAYMENT' AND originId IS NULL")
    suspend fun getSettledDebtRevenueBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(totalAmount), 0.0) FROM `orders` WHERE date BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getRevenueByMethodBetween(start: Long, end: Long, method: String): Double

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM expenses WHERE timestamp BETWEEN :start AND :end")
    suspend fun getExpensesBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM expenses WHERE timestamp BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getExpensesByMethodBetween(start: Long, end: Long, method: String): Double

    @Query("SELECT COUNT(*) FROM `orders` WHERE date BETWEEN :start AND :end")
    suspend fun getOrdersCountBetween(start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM `orders` WHERE date BETWEEN :start AND :end AND paymentMethod = :method")
    suspend fun getOrdersCountByMethodBetween(start: Long, end: Long, method: String): Int

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM debtor_credits WHERE amount > 0")
    suspend fun getTotalReceivables(): Double

    @Query("SELECT COUNT(*) FROM debtor_credits WHERE amount > 0")
    suspend fun getDebtorsCount(): Int

    @Query("""
    SELECT MIN(date) as timestamp, SUM(totalAmount) as amount 
    FROM orders 
    WHERE date BETWEEN :start AND :end AND paymentMethod = :method
    GROUP BY date / 86400000 
    ORDER BY date ASC
    """)
    suspend fun getRevenueTrendByMethod(start: Long, end: Long, method: String): List<TrendPoint>

    @Query("""
    SELECT paymentMethod as type, SUM(totalAmount) as total 
    FROM orders 
    WHERE date BETWEEN :start AND :end 
    GROUP BY paymentMethod
    """)
    suspend fun getPaymentBreakdownBetween(start: Long, end: Long): List<PaymentBreakdown>

    @Query("""
    SELECT oi.serviceName as category, SUM(oi.price * oi.quantity) as total 
    FROM OrderItem oi
    JOIN orders o ON oi.orderId = o.id
    WHERE o.date BETWEEN :start AND :end
    GROUP BY oi.serviceName
    """)
    suspend fun getServiceBreakdownBetween(start: Long, end: Long): List<CategoryRevenue>

    @Query("""
    SELECT category as category, SUM(amount) as total 
    FROM expenses 
    WHERE timestamp BETWEEN :start AND :end
    GROUP BY category
    """)
    suspend fun getExpenseBreakdownBetween(start: Long, end: Long): List<CategoryRevenue>

    // --- Beauty Account Filtered ---

    @Query("SELECT * FROM beauty_transactions WHERE timestamp BETWEEN :start AND :end AND type != 'RESET' ORDER BY timestamp DESC")
    fun getFilteredBeautyTransactions(start: Long, end: Long): Flow<List<BeautyTransaction>>

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM beauty_transactions WHERE timestamp BETWEEN :start AND :end AND type = 'ADD'")
    suspend fun getBeautyReceivedBetween(start: Long, end: Long): Double

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM beauty_transactions WHERE timestamp BETWEEN :start AND :end AND type = 'RETURN'")
    suspend fun getBeautyReturnedBetween(start: Long, end: Long): Double

    @Query("SELECT COUNT(*) FROM beauty_transactions WHERE timestamp BETWEEN :start AND :end AND type != 'RESET'")
    suspend fun getBeautyTransactionCountBetween(start: Long, end: Long): Int

    @Query("""
    SELECT serviceName as category, SUM(price * quantity) as total 
    FROM OrderItem 
    GROUP BY serviceName
    """)
    fun getRevenueByCategoryFlow(): Flow<List<CategoryRevenue>>

    @Query("SELECT * FROM `orders` WHERE totalAmount > paidAmount")
    suspend fun getUnpaidOrders(): List<Order>

    @Query("UPDATE `orders` SET paidAmount = :newPaidAmount WHERE id = :orderId")
    suspend fun updatePayment(orderId: Int, newPaidAmount: Double)

    @Query("""
    SELECT c.id as customerId, c.displayName as customerName, dc.amount as totalBalance, 
           CASE WHEN dc.amount >= 0 THEN 'OWES' ELSE 'CHANGE' END as type
    FROM customers c
    JOIN debtor_credits dc ON c.id = dc.customerId
    WHERE ABS(dc.amount) > 0.01
    """)
    suspend fun getDebtors(): List<DebtorSummary>

    @Query("SELECT * FROM `orders` WHERE customerId = :customerId AND totalAmount > paidAmount ORDER BY date ASC")
    suspend fun getUnpaidOrdersForCustomer(customerId: Long): List<Order>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDebtorCredit(debtorCredit: DebtorCredit)

    @Query("SELECT * FROM debtor_credits WHERE customerId = :customerId")
    suspend fun getDebtorCreditById(customerId: Long): DebtorCredit?

    @Query("SELECT * FROM debtor_credits ORDER BY lastUpdated DESC")
    suspend fun getDebtorCreditList(): List<DebtorCredit>

    @Query("DELETE FROM debtor_credits WHERE customerId = :customerId")
    suspend fun deleteDebtorCredit(customerId: Long)

    @Query("DELETE FROM customers WHERE id = :customerId")
    suspend fun deleteCustomer(customerId: Long)

    @Query("DELETE FROM orders WHERE customerId = :customerId")
    suspend fun deleteOrdersForCustomer(customerId: Long)

    @Query("DELETE FROM settlement_history WHERE customerId = :customerId")
    suspend fun deleteSettlementsForCustomer(customerId: Long)

    @Transaction
    suspend fun deleteCustomerCompletely(customerId: Long) {
        deleteOrdersForCustomer(customerId)
        deleteSettlementsForCustomer(customerId)
        deleteDebtorCredit(customerId)
        deleteCustomer(customerId)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Query("SELECT * FROM customers WHERE normalizedName = :normalizedName")
    suspend fun getCustomerByNormalizedName(normalizedName: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers ORDER BY displayName ASC")
    fun getAllCustomersFlow(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Query("SELECT remainingBalance FROM settlement_history WHERE customerId = :customerId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBalanceForCustomer(customerId: Long): Double?

    @Query("SELECT IFNULL(SUM(totalAmount - paidAmount), 0.0) FROM `orders` WHERE customerId = :customerId")
    suspend fun getUnpaidTotalForCustomer(customerId: Long): Double

    @Transaction
    suspend fun updateCustomerIdentity(oldName: String, newCustomer: CustomerEntity) {
        // This is for merging or fixing names if needed manually later
    }

    @Insert
    suspend fun addPhoto(photo: Photo)

    @Query("SELECT * FROM photos WHERE orderId = :orderId")
    suspend fun getPhotosForOrder(orderId: Int): List<Photo>

    @Insert
    suspend fun addPrinterReference(reference: PrinterReference)

    @Query("SELECT * FROM printer_references ORDER BY timestamp DESC")
    suspend fun getAllPrinterReferences(): List<PrinterReference>

    @Delete
    suspend fun deletePrinterReference(reference: PrinterReference)

    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderById(orderId: Int): Order?

    @Delete
    suspend fun deleteOrder(order: Order)

    @Query("DELETE FROM orders WHERE date BETWEEN :start AND :end")
    suspend fun deleteOrdersBetween(start: Long, end: Long)

    @Query("DELETE FROM orders")
    suspend fun deleteAllOrders()

    @Transaction
    suspend fun deleteOrderAndItems(order: Order) {
        deleteOrder(order)
        // Add item deletion if not handled by foreign key
    }

    @Transaction
    suspend fun insertOrderWithItems(order: Order, items: List<OrderItem>): Long {
        val orderId = insertOrder(order)
        val itemsWithOrderId = items.map { it.copy(orderId = orderId.toInt()) }
        insertOrderItems(itemsWithOrderId)
        return orderId
    }

    @Insert
    suspend fun insertSettlement(settlement: SettlementHistory)

    @Transaction
    suspend fun recordCommercialOrder(order: Order, items: List<OrderItem>): Int {
        val orderId = insertOrder(order).toInt()
        val itemsWithOrderId = items.map { it.copy(orderId = orderId) }
        insertOrderItems(itemsWithOrderId)

        if (order.transactionAmount != 0.0) {
            insertSettlement(
                SettlementHistory(
                    customerName = order.customerName,
                    customerId = order.customerId,
                    balanceBefore = order.previousBalance,
                    amountPaid = order.paidAmount,
                    balanceAfter = order.newBalance,
                    timestamp = order.date,
                    type = "ORDER",
                    note = "Order #$orderId",
                    transactionAmount = order.transactionAmount,
                    newBalance = order.newBalance,
                    originId = orderId,
                    ledgerEntryType = "ORDER_POST",
                    reconciliationStatus = "VERIFIED"
                )
            )
            rebuildCustomerProjection(order.customerId)
        }
        return orderId
    }

    @Transaction
    suspend fun rebuildCustomerProjection(customerId: Long) {
        val customer = getCustomerById(customerId) ?: return
        
        // Balance derivation: Use latest settlement as baseline, then apply order changes
        // If settlement exists (most customers), use its balance directly (it's authoritative)
        // If no settlement (new customers), derive from unpaid orders sum
        val latestSettlement = getLatestBalanceForCustomer(customerId)
        val calculatedBalance = if (latestSettlement != null) {
            latestSettlement  // Settlement is the source of truth (includes all prior transactions)
        } else {
            getUnpaidTotalForCustomer(customerId)  // Fallback for new customers with no settlement
        }
        
        insertOrUpdateDebtorCredit(
            DebtorCredit(
                customerId = customer.id,
                customerName = customer.displayName,
                amount = calculatedBalance,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    @Transaction
    suspend fun verifyCustomerBalance(customerId: Long): Boolean {
        val cached = getDebtorCreditById(customerId)?.amount ?: 0.0
        val calculated = getUnpaidTotalForCustomer(customerId)
        
        if (Math.abs(cached - calculated) > 0.001) {
             rebuildCustomerProjection(customerId)
             return false
        }
        return true
    }

    @Query("SELECT * FROM settlement_history ORDER BY timestamp DESC")
    suspend fun getAllSettlements(): List<SettlementHistory>

    @Query("SELECT * FROM settlement_history")
    suspend fun getAllSettlementHistoryOnce(): List<SettlementHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSettlements(data: List<SettlementHistory>)

    @Query("DELETE FROM settlement_history")
    suspend fun clearSettlementHistory()

    // External Ledger (Legacy/Audit)
    @Insert
    suspend fun insertExternalLedger(entry: ExternalLedger)

    @Query("SELECT SUM(CASE WHEN transactionType = 'CREDIT_TO_EXTERNAL' THEN amount ELSE -amount END) FROM external_ledger")
    suspend fun getExternalBalance(): Double?

    // Cash in Hand (Sum of paid amount from CASH orders)
    @Query("SELECT SUM(paidAmount) FROM `orders` WHERE paymentMethod = 'CASH'")
    fun getCashInHandFlow(): Flow<Double?>

    // Total Receivables (Money owed to business)
    @Query("SELECT SUM(totalAmount - paidAmount) FROM `orders`")
    fun getTotalReceivablesFlow(): Flow<Double?>

    // Beauty Account (New Dedicated Logic)
    @Insert
    suspend fun insertBeautyTransaction(transaction: BeautyTransaction)

    @Query("SELECT * FROM beauty_transactions ORDER BY timestamp DESC")
    fun getAllBeautyTransactionsFlow(): Flow<List<BeautyTransaction>>

    @Query("SELECT * FROM beauty_transactions ORDER BY timestamp DESC")
    suspend fun getAllBeautyTransactions(): List<BeautyTransaction>

    @Query("SELECT newBalance FROM beauty_transactions ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun getBeautyBalanceFlow(): Flow<Double?>

    @Query("SELECT newBalance FROM beauty_transactions ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getBeautyBalance(): Double?

    @Query("SELECT IFNULL((SELECT newBalance FROM beauty_transactions ORDER BY timestamp DESC, id DESC LIMIT 1), 0.0)")
    suspend fun getCurrentBeautyBalance(): Double

    @Delete
    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction)

    @Query("SELECT * FROM beauty_transactions WHERE timestamp > :timestamp OR (timestamp = :timestamp AND id > :id) ORDER BY timestamp ASC, id ASC")
    suspend fun getBeautyTransactionsAfter(timestamp: Long, id: Int): List<BeautyTransaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateBeautyTransaction(transaction: BeautyTransaction)

    @Query("SELECT newBalance FROM beauty_transactions WHERE timestamp < :timestamp OR (timestamp = :timestamp AND id < :id) ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getBeautyBalanceBefore(timestamp: Long, id: Int): Double?

    // Expenses
    @Insert
    suspend fun insertExpense(expense: Expense)

    @Query("SELECT * FROM expenses ORDER BY timestamp DESC")
    fun getAllExpensesFlow(): Flow<List<Expense>>

    @Query("SELECT SUM(amount) FROM expenses")
    suspend fun getTotalExpenses(): Double?

    @Query("DELETE FROM expenses WHERE id = :expenseId")
    suspend fun deleteExpense(expenseId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllExpenses(expenses: List<Expense>)

    @Query("DELETE FROM expenses")
    suspend fun clearExpenses()

    // Stock Management
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockItem(item: StockItem)

    @Query("SELECT * FROM stock_items ORDER BY name ASC")
    fun getAllStockItemsFlow(): Flow<List<StockItem>>

    @Query("SELECT * FROM stock_items WHERE currentQuantity <= lowStockThreshold")
    fun getLowStockItemsFlow(): Flow<List<StockItem>>

    @Query("UPDATE stock_items SET currentQuantity = currentQuantity - :amount WHERE name = :name")
    suspend fun deductStockByName(name: String, amount: Int)

    @Query("SELECT * FROM stock_items WHERE name = :name LIMIT 1")
    suspend fun getStockItemByName(name: String): StockItem?

    @Delete
    suspend fun deleteStockItem(item: StockItem)
}
