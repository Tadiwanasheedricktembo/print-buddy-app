package com.tadiwaprintbuddy.app.data

import androidx.room.Dao
import androidx.room.Query
import java.math.BigDecimal

@Dao
interface IntegrityCheckDao {

    @Query("""
        SELECT COUNT(*) FROM (
            SELECT normalizedName
            FROM customers
            GROUP BY normalizedName
            HAVING COUNT(*) > 1
        )
    """)
    suspend fun getDuplicateCustomerCount(): Int

    @Query("""
        SELECT displayName as customerName, COUNT(*) as cnt
        FROM customers
        GROUP BY normalizedName
        HAVING COUNT(*) > 1
        ORDER BY cnt DESC
    """)
    suspend fun getDuplicateCustomers(): List<DuplicateCustomerReport>

    @Query("""
        SELECT COUNT(*) FROM settlement_history sh
        WHERE NOT EXISTS (
            SELECT 1 FROM customers c 
            WHERE c.id = sh.customerId
        )
    """)
    suspend fun getOrphanedSettlementCount(): Int

    @Query("""
        SELECT customerName, 
               CAST(SUM(CAST(totalAmount AS REAL) - CAST(paidAmount AS REAL)) as REAL) as order_debt,
               (SELECT remainingBalance FROM settlement_history sh 
                WHERE sh.customerId = orders.customerId
                ORDER BY sh.timestamp DESC, sh.id DESC LIMIT 1) as settlement_balance
        FROM orders
        GROUP BY customerId
        HAVING (IFNULL(CAST(settlement_balance AS REAL), 0.0) > 0.01 AND ABS(order_debt - CAST(settlement_balance AS REAL)) > 0.01) 
           OR (IFNULL(CAST(settlement_balance AS REAL), 0.0) <= 0.01 AND order_debt > 0.01)
    """)
    suspend fun getBalanceMismatches(): List<BalanceMismatchReport>
}

data class DuplicateCustomerReport(
    val customerName: String,
    val cnt: Int
)

data class BalanceMismatchReport(
    val customerName: String,
    val order_debt: BigDecimal,
    val settlement_balance: BigDecimal?
)
