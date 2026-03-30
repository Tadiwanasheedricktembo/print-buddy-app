package com.tadiwaprintbuddy.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PrintDao {

    @Insert
    suspend fun insertOrder(order: Order): Long

    @Insert
    suspend fun insertOrderItems(items: List<OrderItem>)

    @Query("SELECT * FROM orders ORDER BY date DESC")
    suspend fun getAllOrders(): List<Order>

    @Query("SELECT * FROM OrderItem WHERE orderId = :orderId")
    suspend fun getItemsForOrder(orderId: Int): List<OrderItem>

    @Query("SELECT SUM(totalAmount) FROM `orders`")
    suspend fun getTotalRevenue(): Double?

    @Query("SELECT COUNT(*) FROM `orders`")
    suspend fun getTotalOrders(): Int

    @Query("""
SELECT SUM(totalAmount)
FROM `orders`
WHERE date BETWEEN :start AND :end
""")
    suspend fun getRevenueBetween(start: Long, end: Long): Double?

    @Query("""
    SELECT serviceName as category, SUM(price * quantity) as total 
    FROM OrderItem 
    GROUP BY serviceName
    """)
    suspend fun getRevenueByCategory(): List<CategoryRevenue>

    @Query("""
    SELECT strftime('%w', date / 1000, 'unixepoch') as day,
           SUM(totalAmount) as total
    FROM `orders`
    WHERE date >= :sevenDaysAgo
    GROUP BY day
    ORDER BY day
    """)
    suspend fun getRevenueLast7Days(sevenDaysAgo: Long): List<DailyRevenue>

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
}
