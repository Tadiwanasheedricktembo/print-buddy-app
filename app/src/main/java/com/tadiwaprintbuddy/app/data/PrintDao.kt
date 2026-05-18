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
    SELECT o.customerId as customerId, c.displayName as customerName, SUM(o.totalAmount - o.paidAmount) as totalBalance, 'OWES' as type
    FROM `orders` o
    JOIN customers c ON o.customerId = c.id
    WHERE o.totalAmount > o.paidAmount
    GROUP BY o.customerId
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

    @Query("SELECT SUM(CASE WHEN type = 'ADD' THEN amount WHEN type = 'RETURN' THEN -amount WHEN type = 'RESET' THEN amount ELSE 0 END) FROM beauty_transactions")
    fun getBeautyBalanceFlow(): Flow<Double?>

    @Query("SELECT SUM(CASE WHEN type = 'ADD' THEN amount WHEN type = 'RETURN' THEN -amount WHEN type = 'RESET' THEN amount ELSE 0 END) FROM beauty_transactions")
    suspend fun getBeautyBalance(): Double?

    @Query("SELECT IFNULL(SUM(CASE WHEN type = 'ADD' THEN amount WHEN type = 'RETURN' THEN -amount WHEN type = 'RESET' THEN amount ELSE 0 END), 0.0) FROM beauty_transactions")
    suspend fun getCurrentBeautyBalance(): Double
}
