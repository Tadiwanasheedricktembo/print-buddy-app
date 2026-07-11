package com.tadiwaprintbuddy.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: Order): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItems(items: List<OrderItem>)

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

    // Revenue from debt settlements
    @Query("SELECT IFNULL(SUM(settledAmount), 0.0) FROM `settlement_history` WHERE timestamp BETWEEN :start AND :end AND ledgerEntryType = 'PAYMENT'")
    suspend fun getSettledDebtRevenueBetween(start: Long, end: Long): Double

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

    @Query("SELECT date as timestamp, IFNULL(SUM(paidAmount), 0.0) as amount FROM `orders` WHERE orderStatus = 'ACTIVE' AND paymentMethod = :method AND date BETWEEN :start AND :end GROUP BY date / (24 * 60 * 60 * 1000)")
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
    suspend fun updateOrderPaymentStatus(orderId: Int, newPaidAmount: Double, status: String, method: String)

    @Query("SELECT customerId, customerName, IFNULL(SUM(totalAmount - paidAmount), 0.0) as totalBalance, 'OWES' as type FROM `orders` WHERE orderStatus = 'ACTIVE' GROUP BY customerId HAVING totalBalance > 0")
    suspend fun getDebtors(): List<DebtorSummary>

    @Query("SELECT * FROM `orders` WHERE customerId = :customerId AND paidAmount < totalAmount AND orderStatus = 'ACTIVE' ORDER BY date ASC")
    suspend fun getUnpaidOrdersForCustomer(customerId: Long): List<Order>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDebtorCredit(credit: DebtorCredit): Unit

    @Query("SELECT * FROM debtor_credits WHERE customerId = :customerId")
    suspend fun getDebtorCreditById(customerId: Long): DebtorCredit?

    @Query("SELECT * FROM debtor_credits")
    suspend fun getDebtorCreditList(): List<DebtorCredit>

    @Query("DELETE FROM debtor_credits WHERE customerId = :customerId")
    suspend fun deleteDebtorCredit(customerId: Long): Unit

    @Query("DELETE FROM customers WHERE id = :customerId")
    suspend fun deleteCustomer(customerId: Long): Unit

    @Query("DELETE FROM `orders` WHERE customerId = :customerId")
    suspend fun deleteOrdersForCustomer(customerId: Long): Unit

    @Query("DELETE FROM settlement_history WHERE customerId = :customerId")
    suspend fun deleteSettlementsForCustomer(customerId: Long): Unit

    @Transaction
    suspend fun deleteCustomerCompletely(customerId: Long) {
        deleteDebtorCredit(customerId)
        deleteOrdersForCustomer(customerId)
        deleteSettlementsForCustomer(customerId)
        deleteCustomer(customerId)
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

    @Query("SELECT remainingBalance FROM settlement_history WHERE customerId = :customerId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBalanceForCustomer(customerId: Long): Double?

    @Query("SELECT IFNULL(SUM(totalAmount - paidAmount), 0.0) FROM `orders` WHERE customerId = :customerId AND orderStatus = 'ACTIVE'")
    suspend fun getUnpaidTotalForCustomer(customerId: Long): Double

    @Query("UPDATE customers SET displayName = :newName, normalizedName = :normalized WHERE id = :customerId")
    suspend fun updateCustomerIdentity(customerId: Long, newName: String, normalized: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPhoto(photo: Photo): Unit

    @Query("SELECT * FROM photos WHERE orderId = :orderId")
    suspend fun getPhotosForOrder(orderId: Int): List<Photo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPrinterReference(reference: PrinterReference): Unit

    @Query("SELECT * FROM printer_references ORDER BY timestamp DESC")
    suspend fun getAllPrinterReferences(): List<PrinterReference>

    @Delete
    suspend fun deletePrinterReference(reference: PrinterReference): Unit

    @Query("SELECT * FROM `orders` WHERE id = :orderId")
    suspend fun getOrderById(orderId: Int): Order?

    @Delete
    suspend fun deleteOrder(order: Order): Unit

    @Query("DELETE FROM `orders` WHERE date BETWEEN :start AND :end")
    suspend fun deleteOrdersBetween(start: Long, end: Long): Unit

    @Query("DELETE FROM `orders`")
    suspend fun deleteAllOrders(): Unit

    @Transaction
    suspend fun deleteOrderAndItems(order: Order) {
        deleteOrder(order)
        // OrderItems should be deleted via ForeignKey CASCADE, but we can be explicit if needed
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: SettlementHistory): Unit

    @Transaction
    suspend fun recordOrderAtomic(order: Order, items: List<OrderItem>): Int {
        // 1. Deduct Stock
        for (item in items) {
            val affected = safeDeductStock(item.serviceName, item.quantity)
            val stockItem = getStockItemByName(item.serviceName)
            if (stockItem != null && affected == 0) {
                throw Exception("Insufficient stock for ${item.serviceName}")
            }
        }

        // 2. Insert Order
        val orderId = insertOrder(order).toInt()
        val itemsWithOrderId = items.map { it.copy(orderId = orderId) }
        insertOrderItems(itemsWithOrderId)

        // 3. Settlement
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
                    ledgerEntryType = "ORDER_POST"
                )
            )
            rebuildCustomerProjection(order.customerId)
        }
        return orderId
    }

    @Transaction
    suspend fun recordPaymentAtomic(orderId: Int, newPaidAmount: Double, status: String, method: String, settlement: SettlementHistory) {
        updateOrderPaymentStatus(orderId, newPaidAmount, status, method)
        insertSettlement(settlement)
        rebuildCustomerProjection(settlement.customerId)
    }

    @Transaction
    suspend fun cancelOrderAtomic(orderId: Int, status: String, settlement: SettlementHistory) {
        val items = getItemsForOrder(orderId)
        for (item in items) {
            restoreStock(item.serviceName, item.quantity)
        }
        updateOrderStatus(orderId, status)
        insertSettlement(settlement)
        rebuildCustomerProjection(settlement.customerId)
    }

    @Transaction
    suspend fun applyPaymentToCustomerIdAtomic(customerId: Long, paymentAmount: Double, paymentMethod: String) {
        val customer = getCustomerById(customerId) ?: return
        val currentBalance = getLatestBalanceForCustomer(customerId) ?: getUnpaidTotalForCustomer(customerId)
        
        val unpaidOrders = getUnpaidOrdersForCustomer(customerId)
        var remainingPayment = paymentAmount

        for (order in unpaidOrders) {
            if (remainingPayment <= 0) break

            val amountOwed = order.totalAmount - order.paidAmount
            val paymentForThisOrder = if (remainingPayment >= amountOwed) amountOwed else remainingPayment

            val newPaidAmount = order.paidAmount + paymentForThisOrder
            
            val newStatus = if (newPaidAmount >= order.totalAmount) "PAID" else "PARTIALLY_PAID"
            val newMethod = if (order.paymentMethod == "NONE" || order.paymentMethod == "") paymentMethod else if (order.paymentMethod == paymentMethod) paymentMethod else "MIXED"
            
            updateOrderPaymentStatus(order.id, newPaidAmount, newStatus, newMethod)

            remainingPayment -= paymentForThisOrder
        }

        val newBalance = currentBalance - paymentAmount

        insertSettlement(
            SettlementHistory(
                customerName = customer.displayName,
                customerId = customer.id,
                balanceBefore = currentBalance,
                amountPaid = paymentAmount,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = "PAYMENT",
                ledgerEntryType = "PAYMENT",
                note = "Debt Payment via $paymentMethod",
                transactionAmount = -paymentAmount,
                newBalance = newBalance
            )
        )
        
        rebuildCustomerProjection(customer.id)
    }

    @Transaction
    suspend fun rebuildCustomerProjection(customerId: Long) {
        val customer = getCustomerById(customerId) ?: return
        val latestSettlement = getLatestBalanceForCustomer(customerId)
        val calculatedBalance = if (latestSettlement != null) {
            latestSettlement
        } else {
            getUnpaidTotalForCustomer(customerId)
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

    @Query("SELECT COUNT(*) = 0 FROM (SELECT id FROM settlement_history WHERE customerId = :customerId EXCEPT SELECT id FROM settlement_history WHERE customerId = :customerId)")
    suspend fun verifyCustomerBalance(customerId: Long): Boolean

    @Query("SELECT * FROM settlement_history ORDER BY timestamp DESC")
    suspend fun getAllSettlements(): List<SettlementHistory>

    @Query("SELECT * FROM settlement_history")
    suspend fun getAllSettlementHistoryOnce(): List<SettlementHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSettlements(settlements: List<SettlementHistory>): Unit

    @Query("DELETE FROM settlement_history")
    suspend fun clearSettlementHistory(): Unit

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExternalLedger(entry: ExternalLedger): Unit

    @Query("SELECT SUM(amount) FROM external_ledger")
    suspend fun getExternalBalance(): Double?

    @Query("SELECT IFNULL(SUM(paidAmount), 0.0) FROM `orders` WHERE paymentMethod = 'CASH' AND orderStatus = 'ACTIVE'")
    fun getCashInHandFlow(): Flow<Double?>

    @Query("SELECT IFNULL(SUM(totalAmount - paidAmount), 0.0) FROM `orders` WHERE orderStatus = 'ACTIVE'")
    fun getTotalReceivablesFlow(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeautyTransaction(transaction: BeautyTransaction): Unit

    @Query("SELECT * FROM `beauty_transactions` ORDER BY timestamp DESC")
    fun getAllBeautyTransactionsFlow(): Flow<List<BeautyTransaction>>

    @Query("SELECT * FROM `beauty_transactions`")
    suspend fun getAllBeautyTransactions(): List<BeautyTransaction>

    @Query("SELECT newBalance FROM `beauty_transactions` ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun getBeautyBalanceFlow(): Flow<Double?>

    @Query("SELECT newBalance FROM `beauty_transactions` ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getBeautyBalance(): Double?

    @Query("SELECT IFNULL((SELECT newBalance FROM `beauty_transactions` ORDER BY timestamp DESC, id DESC LIMIT 1), 0.0)")
    suspend fun getCurrentBeautyBalance(): Double

    @Delete
    suspend fun deleteBeautyTransaction(transaction: BeautyTransaction): Unit

    @Query("SELECT * FROM `beauty_transactions` WHERE timestamp > :timestamp OR (timestamp = :timestamp AND id > :id) ORDER BY timestamp ASC, id ASC")
    suspend fun getBeautyTransactionsAfter(timestamp: Long, id: Int): List<BeautyTransaction>

    @Update
    suspend fun updateBeautyTransaction(transaction: BeautyTransaction): Unit

    @Query("SELECT newBalance FROM `beauty_transactions` WHERE timestamp < :timestamp OR (timestamp = :timestamp AND id < :id) ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getBeautyBalanceBefore(timestamp: Long, id: Int): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense): Unit

    @Query("SELECT * FROM `expenses` ORDER BY timestamp DESC")
    fun getAllExpensesFlow(): Flow<List<Expense>>

    @Query("SELECT SUM(amount) FROM `expenses`")
    suspend fun getTotalExpenses(): Double?

    @Query("DELETE FROM `expenses` WHERE id = :id")
    suspend fun deleteExpense(id: Int): Unit

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllExpenses(expenses: List<Expense>): Unit

    @Query("DELETE FROM `expenses`")
    suspend fun clearExpenses(): Unit

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockItem(item: StockItem): Unit

    @Query("SELECT * FROM `stock_items` ORDER BY name ASC")
    fun getAllStockItemsFlow(): Flow<List<StockItem>>

    @Query("SELECT * FROM `stock_items` WHERE currentQuantity <= lowStockThreshold")
    fun getLowStockItemsFlow(): Flow<List<StockItem>>

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity - :quantity WHERE name = :name AND currentQuantity >= :quantity")
    suspend fun safeDeductStock(name: String, quantity: Int): Int

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity - :quantity WHERE name = :name")
    suspend fun deductStockByName(name: String, quantity: Int): Unit

    @Query("UPDATE `stock_items` SET currentQuantity = currentQuantity + :quantity WHERE name = :name")
    suspend fun restoreStock(name: String, quantity: Int): Unit

    @Query("SELECT * FROM `stock_items` WHERE name = :name")
    suspend fun getStockItemByName(name: String): StockItem?

    @Delete
    suspend fun deleteStockItem(item: StockItem): Unit

    @Query("UPDATE `orders` SET orderStatus = :status WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: Int, status: String)
}
