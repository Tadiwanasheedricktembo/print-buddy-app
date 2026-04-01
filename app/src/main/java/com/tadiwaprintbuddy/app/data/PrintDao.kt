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
    SELECT customerName, SUM(totalAmount - paidAmount) as totalOwed
    FROM `orders`
    WHERE totalAmount > paidAmount
    GROUP BY customerName
    """)
    suspend fun getDebtors(): List<DebtorSummary>

    @Query("SELECT * FROM `orders` WHERE customerName = :customerName AND totalAmount > paidAmount ORDER BY date ASC")
    suspend fun getUnpaidOrdersForCustomer(customerName: String): List<Order>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDebtorCredit(debtorCredit: DebtorCredit)

    @Query("SELECT * FROM debtor_credits WHERE customerName = :customerName")
    suspend fun getDebtorCreditByName(customerName: String): DebtorCredit?

    @Query("SELECT * FROM debtor_credits ORDER BY amount DESC")
    suspend fun getDebtorCreditList(): List<DebtorCredit>

    @Query("DELETE FROM debtor_credits WHERE customerName = :customerName")
    suspend fun deleteDebtorCredit(customerName: String)

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
    }

    @Insert
    suspend fun insertSettlement(settlement: SettlementHistory)

    @Query("SELECT * FROM settlement_history ORDER BY timestamp DESC")
    suspend fun getAllSettlements(): List<SettlementHistory>

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

    @Query("SELECT SUM(CASE WHEN type = 'ADD' THEN amount ELSE -amount END) FROM beauty_transactions")
    fun getBeautyBalanceFlow(): Flow<Double?>

    @Query("SELECT SUM(CASE WHEN type = 'ADD' THEN amount ELSE -amount END) FROM beauty_transactions")
    suspend fun getBeautyBalance(): Double?
}
