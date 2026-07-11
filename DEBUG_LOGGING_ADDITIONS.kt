package com.tadiwaprintbuddy.app.data

import android.util.Log
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DEBUG LOGGING ADDITIONS FOR CUSTOMER IDENTITY & DEBT TRACKING
 * 
 * Add these logs to track down duplication bugs in production
 * Can be toggled via BuildConfig.DEBUG
 */

// ============================================================================
// LOGGING TAGS
// ============================================================================

object DebugTags {
    const val CUSTOMER_LOOKUP = "CustomerLookup"
    const val CUSTOMER_CREATION = "CustomerCreation"
    const val CUSTOMER_MATCH = "CustomerMatch"
    const val DEBT_CALC = "DebtCalculation"
    const val PAYMENT_PROCESS = "PaymentProcess"
    const val SETTLEMENT_AUDIT = "SettlementAudit"
    const val ORDER_CREATION = "OrderCreation"
    const val DATABASE_INTEGRITY = "DatabaseIntegrity"
    const val PERFORMANCE_WARNING = "PerfWarning"
}

// ============================================================================
// ENHANCED PrintRepository WITH LOGGING
// ============================================================================

/**
 * REFACTORED CLASS (Add to existing PrintRepository or create extension)
 * 
 * Key additions:
 * - Detailed logging at each customer operation
 * - Balance calculation tracing
 * - Payment flow audit
 * - Data consistency warnings
 */

class PrintRepositoryDebugExtension(private val printDao: PrintDao) {

    /**
     * ENHANCED: getCustomerBalance with detailed logging
     * 
     * Currently line 119 of original PrintRepository:
     *   suspend fun getCustomerBalance(customerName: String): Double {
     *       val allSettlements = printDao.getAllSettlementHistoryOnce()
     *       return allSettlements...
     *   }
     */
    suspend fun getCustomerBalance_DEBUG(customerName: String): Double {
        Log.d(DebugTags.DEBT_CALC, "=== getCustomerBalance START ===")
        Log.d(DebugTags.DEBT_CALC, "Input customerName: '$customerName'")
        Log.d(DebugTags.DEBT_CALC, "After trim: '${customerName.trim()}'")
        Log.d(DebugTags.DEBT_CALC, "After lowercase: '${customerName.trim().lowercase()}'")

        val allSettlements = printDao.getAllSettlementHistoryOnce()
        Log.d(DebugTags.DEBT_CALC, "Total settlement records in DB: ${allSettlements.size}")

        // Show distribution by customer
        val byCustomer = allSettlements.groupBy { it.customerName }
        Log.d(DebugTags.DEBT_CALC, "Customers in settlement history: ${byCustomer.keys.size}")
        byCustomer.forEach { (custName, records) ->
            Log.v(DebugTags.DEBT_CALC, "  - '$custName': ${records.size} records")
        }

        val filtered = allSettlements.filter {
            it.customerName.trim().equals(customerName.trim(), ignoreCase = true)
        }
        Log.d(DebugTags.DEBT_CALC, "After filter [trim().equals(ignoreCase)]: ${filtered.size} records")

        filtered.forEach { record ->
            Log.v(DebugTags.DEBT_CALC, 
                "  - Record#${record.id}: timestamp=${record.timestamp}, balance=${record.balanceAfter}")
        }

        val latest = filtered.maxByOrNull { it.timestamp }
        if (latest != null) {
            Log.d(DebugTags.DEBT_CALC, "Latest settlement found: ID=${latest.id}, balance=₹${latest.balanceAfter}")
        } else {
            Log.w(DebugTags.DEBT_CALC, "⚠️  NO settlement history found for '$customerName'")
            Log.d(DebugTags.DEBT_CALC, "Possible causes: new customer, name variation, or data orphaning")
        }

        val balance = latest?.balanceAfter ?: 0.0
        Log.d(DebugTags.DEBT_CALC, "=== getCustomerBalance RESULT: ₹$balance ===")
        return balance
    }

    /**
     * ENHANCED: getUnpaidOrdersForCustomer_DEBUG
     * 
     * Logs to verify database-level matching behavior
     */
    suspend fun getUnpaidOrdersForCustomer_DEBUG(customerName: String): List<Order> {
        Log.d(DebugTags.ORDER_CREATION, "=== getUnpaidOrdersForCustomer START ===")
        Log.d(DebugTags.ORDER_CREATION, "Query: WHERE customerName = '$customerName' (CASE SENSITIVE)")

        // First, show what's in DB
        val allOrders = printDao.getAllOrders()
        Log.d(DebugTags.ORDER_CREATION, "Total orders in DB: ${allOrders.size}")

        val byCustomer = allOrders.groupBy { it.customerName }
        Log.d(DebugTags.ORDER_CREATION, "Unique customer names in DB: ${byCustomer.size}")
        byCustomer.forEach { (name, orders) ->
            Log.v(DebugTags.ORDER_CREATION, 
                "  - '$name' (length=${name.length}): ${orders.size} orders")
        }

        // Now run actual query
        val unpaidOrders = printDao.getUnpaidOrdersForCustomer(customerName)
        Log.d(DebugTags.ORDER_CREATION, "Query matched: ${unpaidOrders.size} unpaid orders")

        // Check if similar names exist
        val similarNames = byCustomer.keys.filter {
            it.trim().equals(customerName.trim(), ignoreCase = true) && it != customerName
        }
        
        if (similarNames.isNotEmpty()) {
            Log.w(DebugTags.ORDER_CREATION, "⚠️  POTENTIAL BUG: Similar names found but not matched by query!")
            similarNames.forEach { name ->
                Log.w(DebugTags.ORDER_CREATION, "  - DB has '$name' but query searched for '$customerName'")
                val count = byCustomer[name]?.size ?: 0
                Log.w(DebugTags.ORDER_CREATION, "    (This represents $count potentially missing orders)")
            }
        }

        Log.d(DebugTags.ORDER_CREATION, "=== getUnpaidOrdersForCustomer RESULT: ${unpaidOrders.size} orders ===")
        return unpaidOrders
    }

    /**
     * ENHANCED: applyPaymentToCustomer with full audit trail
     * 
     * Add this enhanced version or patch existing at line ~81
     */
    suspend fun applyPaymentToCustomer_DEBUG(
        customerName: String, 
        paymentAmount: Double, 
        paymentMethod: String = "CASH"
    ) {
        Log.i(DebugTags.PAYMENT_PROCESS, "=== applyPaymentToCustomer START ===")
        Log.i(DebugTags.PAYMENT_PROCESS, "Customer: '$customerName'")
        Log.i(DebugTags.PAYMENT_PROCESS, "Payment: ₹$paymentAmount via $paymentMethod")

        val currentBalance = printDao.getDebtorCreditByName(customerName)?.amount ?: 0.0
        Log.d(DebugTags.PAYMENT_PROCESS, "Current balance (from debtor_credits): ₹$currentBalance")

        val unpaidOrders = printDao.getUnpaidOrdersForCustomer(customerName)
        Log.d(DebugTags.PAYMENT_PROCESS, "Unpaid orders: ${unpaidOrders.size}")

        unpaidOrders.forEachIndexed { index, order ->
            val due = order.totalAmount - order.paidAmount
            Log.d(DebugTags.PAYMENT_PROCESS, "  Order#${order.id}: ₹$due due (total=₹${order.totalAmount}, paid=₹${order.paidAmount})")
        }

        var remainingPayment = paymentAmount
        unpaidOrders.forEachIndexed { index, order ->
            if (remainingPayment <= 0) {
                Log.d(DebugTags.PAYMENT_PROCESS, "All payment allocated after order#${index}")
                return@forEachIndexed
            }

            val amountOwed = order.totalAmount - order.paidAmount
            val paymentForThisOrder = if (remainingPayment >= amountOwed) amountOwed else remainingPayment

            Log.d(DebugTags.PAYMENT_PROCESS, 
                "  Allocating ₹$paymentForThisOrder to Order#${order.id} (was missing ₹$amountOwed)")

            val newPaidAmount = order.paidAmount + paymentForThisOrder
            printDao.updatePayment(order.id, newPaidAmount)

            remainingPayment -= paymentForThisOrder
        }

        Log.d(DebugTags.PAYMENT_PROCESS, "Remaining unallocated payment: ₹$remainingPayment")

        val actualSettled = paymentAmount

        Log.i(DebugTags.SETTLEMENT_AUDIT, "Recording settlement:")
        Log.i(DebugTags.SETTLEMENT_AUDIT, "  Customer: '$customerName'")
        Log.i(DebugTags.SETTLEMENT_AUDIT, "  Before balance: ₹$currentBalance")
        Log.i(DebugTags.SETTLEMENT_AUDIT, "  Amount paid: ₹$actualSettled")
        Log.i(DebugTags.SETTLEMENT_AUDIT, "  After balance: ₹${currentBalance - actualSettled}")

        // Log to file/analytics if needed
    }

    /**
     * ENHANCED: confirmOrder with customer matching trace
     */
    suspend fun confirmOrder_DEBUG(
        customerName: String,
        cartItems: List<CartItem>,
        paymentMethod: String = "CASH"
    ): Int {
        Log.i(DebugTags.ORDER_CREATION, "=== confirmOrder START ===")
        Log.i(DebugTags.ORDER_CREATION, "Customer name (input): '$customerName'")
        Log.d(DebugTags.ORDER_CREATION, "  Length: ${customerName.length}")
        Log.d(DebugTags.ORDER_CREATION, "  Trimmed: '${customerName.trim()}'")
        Log.d(DebugTags.ORDER_CREATION, "  Lowercase: '${customerName.lowercase()}'")

        // Check if similar customer exists
        val allOrders = printDao.getAllOrders()
        val existingNames = allOrders.map { it.customerName }.distinct()
        
        Log.d(DebugTags.ORDER_CREATION, "Existing customer names in DB: ${existingNames.size}")

        val exactMatch = existingNames.find { it == customerName }
        val caseInsensitiveMatch = existingNames.find { 
            it.trim().equals(customerName.trim(), ignoreCase = true) 
        }

        if (exactMatch != null) {
            Log.d(DebugTags.ORDER_CREATION, "✓ Exact match found: '$exactMatch'")
        } else if (caseInsensitiveMatch != null) {
            Log.w(DebugTags.ORDER_CREATION, "⚠️  CASE VARIATION: Existing='$caseInsensitiveMatch', Input='$customerName'")
            Log.w(DebugTags.ORDER_CREATION, "    This will CREATE A DUPLICATE CUSTOMER RECORD!")
        } else {
            Log.i(DebugTags.ORDER_CREATION, "New customer (no existing match)")
        }

        // Continue with order creation...
        Log.i(DebugTags.ORDER_CREATION, "=== confirmOrder END ===")
        return -1  // Placeholder
    }
}

// ============================================================================
// DATABASE INTEGRITY CHECKER (Can be run periodically)
// ============================================================================

/**
 * Add this as a DAO query or standalone check function
 * Run periodically to detect data corruption
 */

@Dao
interface IntegrityCheckDao {

    @Query("""
        SELECT COUNT(*) FROM (
            SELECT LOWER(TRIM(customerName)) as norm
            FROM orders
            GROUP BY norm
            HAVING COUNT(*) > 1
        )
    """)
    suspend fun getDuplicateCustomerCount(): Int

    @Query("""
        SELECT customerName, COUNT(*) as cnt
        FROM orders
        GROUP BY customerName
        HAVING COUNT(*) > 1
        ORDER BY cnt DESC
    """)
    suspend fun getDuplicateCustomers(): List<DuplicateCustomerReport>

    @Query("""
        SELECT COUNT(*) FROM settlement_history sh
        WHERE NOT EXISTS (
            SELECT 1 FROM orders o 
            WHERE LOWER(TRIM(o.customerName)) = LOWER(TRIM(sh.customerName))
        )
    """)
    suspend fun getOrphanedSettlementCount(): Int

    @Query("""
        SELECT *  FROM settlement_history sh
        WHERE NOT EXISTS (
            SELECT 1 FROM orders o 
            WHERE LOWER(TRIM(o.customerName)) = LOWER(TRIM(sh.customerName))
        )
        LIMIT 20
    """)
    suspend fun getOrphanedSettlements(): List<SettlementHistory>

    @Query("""
        SELECT customerName, 
               CAST(SUM(totalAmount - paidAmount) as REAL) as order_debt,
               (SELECT balanceAfter FROM settlement_history sh 
                WHERE LOWER(TRIM(sh.customerName)) = LOWER(TRIM(orders.customerName))
                ORDER BY sh.timestamp DESC LIMIT 1) as settlement_balance
        FROM orders
        GROUP BY customerName
        HAVING order_debt != settlement_balance OR settlement_balance IS NULL
    """)
    suspend fun getBalanceMismatches(): List<BalanceMismatchReport>
}

data class DuplicateCustomerReport(
    val customerName: String,
    val cnt: Int
)

data class BalanceMismatchReport(
    val customerName: String,
    val order_debt: Double,
    val settlement_balance: Double?
)

/**
 * Usage:
 * 
 * Launch periodic check (e.g., on app startup in debug builds):
 * 
 *   if (BuildConfig.DEBUG) {
 *       lifecycleScope.launch {
 *           val duplicates = integrityCheckDao.getDuplicateCustomerCount()
 *           if (duplicates > 0) {
 *               Log.e("DATABASE_INTEGRITY", "⚠️  $duplicates duplicate customer sets detected!")
 *               val dupes = integrityCheckDao.getDuplicateCustomers()
 *               dupes.forEach { 
 *                   Log.e("DATABASE_INTEGRITY", "  - ${it.customerName}: ${it.cnt} entries")
 *               }
 *           }
 *           
 *           val orphaned = integrityCheckDao.getOrphanedSettlementCount()
 *           if (orphaned > 0) {
 *               Log.e("DATABASE_INTEGRITY", "⚠️  $orphaned orphaned settlement records!")
 *           }
 *           
 *           val mismatches = integrityCheckDao.getBalanceMismatches()
 *           if (mismatches.isNotEmpty()) {
 *               Log.e("DATABASE_INTEGRITY", "⚠️  ${mismatches.size} balance mismatches!")
 *               mismatches.forEach {
 *                   Log.e("DATABASE_INTEGRITY", 
 *                       "  - ${it.customerName}: orders=₹${it.order_debt} vs settlement=₹${it.settlement_balance}")
 *               }
 *           }
 *       }
 *   }
 */

// ============================================================================
// LOGGING IMPLEMENTATION CHECKLIST
// ============================================================================

/**
 * TODO: Add these logs to production code
 * 
 * Priority 1 (CRITICAL):
 * ☐ confirmOrder() - log customer name variations
 * ☐ getCustomerBalance() - log settlement search & filtering
 * ☐ applyPaymentToCustomer() - log payment allocation
 * ☐ getUnpaidOrdersForCustomer() - log query execution & result count
 * 
 * Priority 2 (HIGH):
 * ☐ insertSettlement() - log settlement creation with timestamp
 * ☐ insertOrUpdateDebtorCredit() - log sync with settlement_history
 * ☐ getCustomerSummaries() - log grouping logic
 * 
 * Priority 3 (MEDIUM):
 * ☐ Periodic integrity checks on app startup/background
 * ☐ Log performance warnings (N+1 queries, large result sets)
 * 
 * Integration steps:
 * 1. Copy DebugTags object to project
 * 2. Add logging calls to key functions (marked above)
 * 3. Create IntegrityCheckDao and add to AppDatabase
 * 4. Run periodic checks (onCreate, onResume, periodic worker)
 * 5. Monitor logcat for warnings during QA testing
 * 6. Keep logs for crash reports
 */
