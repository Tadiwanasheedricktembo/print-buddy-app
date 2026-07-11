-- ============================================================================
-- PRODUCTION MONITORING QUERIES
-- ============================================================================
--
-- Run these queries periodically (weekly/monthly) to detect data degradation
-- Add to monitoring dashboard or cron jobs
--
-- ============================================================================

-- ============================================================================
-- WEEKLY CHECKS
-- ============================================================================

-- [WEEKLY-01] Customer Name Consistency
-- Should return 0 rows after migration
SELECT 
    'WEEKLY-01: Customer name consistency',
    normalizedName as issue_name,
    COUNT(*) as variant_count,
    GROUP_CONCAT(DISTINCT customerName, ' | ') as variants
FROM (
    SELECT DISTINCT
        customerName,
        LOWER(TRIM(customerName)) as normalizedName
    FROM orders
)
GROUP BY normalizedName
HAVING COUNT(DISTINCT customerName) > 1;

-- [WEEKLY-02] Orphaned Order Records
-- Should return 0 rows after migration
SELECT 
    'WEEKLY-02: Orphaned orders',
    COUNT(*) as orphan_count
FROM orders o
WHERE o.customerId IS NULL OR o.customerId NOT IN (
    SELECT id FROM customers
);

-- [WEEKLY-03] Orphaned Settlement Records
-- Should return 0 rows after migration
SELECT 
    'WEEKLY-03: Orphaned settlements',
    COUNT(*) as orphan_count
FROM settlement_history sh
WHERE sh.customerId IS NULL OR sh.customerId NOT IN (
    SELECT id FROM customers
);

-- [WEEKLY-04] Debt Calculation Anomalies
-- Shows customers where order debt != settlement balance
SELECT 
    'WEEKLY-04: Balance mismatch',
    c.id as customer_id,
    c.name as customer_name,
    ROUND(COALESCE(o_debt.total, 0), 2) as order_debt,
    ROUND(COALESCE(sh_balance.latest, 0), 2) as settlement_balance,
    ROUND(ABS(COALESCE(o_debt.total, 0) - COALESCE(sh_balance.latest, 0)), 2) as discrepancy
FROM customers c
LEFT JOIN (
    SELECT customerId, SUM(totalAmount - paidAmount) as total
    FROM orders
    WHERE totalAmount > paidAmount
    GROUP BY customerId
) o_debt ON c.id = o_debt.customerId
LEFT JOIN (
    SELECT customerId, balanceAfter as latest
    FROM settlement_history
    WHERE customerId IN (
        SELECT customerId FROM settlement_history
        WHERE customerId IS NOT NULL
        GROUP BY customerId
        HAVING MAX(timestamp) = MAX(timestamp)
    )
    ORDER BY timestamp DESC
) sh_balance ON c.id = sh_balance.customerId
WHERE ABS(COALESCE(o_debt.total, 0) - COALESCE(sh_balance.latest, 0)) > 1.0;

-- [WEEKLY-05] Payment Processing Failures
-- Orders that show as paid but aren't zero debt
SELECT 
    'WEEKLY-05: Suspicious paid orders',
    COUNT(*) as issue_count
FROM orders o
WHERE o.paidAmount = o.totalAmount
AND o.id IN (
    SELECT order_id FROM OrderItem where orderId = o.id
)
AND NOT EXISTS (
    SELECT 1 FROM settlement_history sh
    WHERE sh.customerId = o.customerId
    AND sh.timestamp > o.date
);

-- ============================================================================
-- MONTHLY CHECKS
-- ============================================================================

-- [MONTHLY-01] Revenue Accuracy Verification
-- Compare orders total vs external ledger
SELECT 
    'MONTHLY-01: Revenue audit',
    ROUND(SUM(totalAmount), 2) as order_total_revenue,
    (SELECT ROUND(SUM(amount), 2) FROM external_ledger) as external_ledger_total,
    'Verify these match' as note
FROM orders;

-- [MONTHLY-02] Debt Recovery Analysis
-- Top 10 debtors - identify collections targets
SELECT 
    'MONTHLY-02: Top debtors',
    ROW_NUMBER() OVER (ORDER BY debt DESC) as rank,
    c.name as customer_name,
    ROUND(debt, 2) as outstanding_debt,
    COUNT(o.id) as order_count,
    MAX(o.date) as last_order_date,
    CAST((CURRENT_TIMESTAMP - MAX(o.date)) / 86400000 AS INTEGER) as days_since_last_order
FROM (
    SELECT 
        customerId,
        SUM(totalAmount - paidAmount) as debt
    FROM orders
    WHERE totalAmount > paidAmount
    GROUP BY customerId
) unpaid
JOIN customers c ON unpaid.customerId = c.id
LEFT JOIN orders o ON c.id = o.customerId
GROUP BY c.id
ORDER BY debt DESC
LIMIT 10;

-- [MONTHLY-03] Settlement History Accuracy
-- Verify settlement records are creating proper balance chain
SELECT 
    'MONTHLY-03: Settlement chain audit',
    c.id as customer_id,
    c.name,
    COUNT(sh.id) as settlement_count,
    MIN(sh.timestamp) as first_settlement,
    MAX(sh.timestamp) as last_settlement,
    (SELECT balanceAfter FROM settlement_history 
     WHERE customerId = c.id 
     ORDER BY timestamp DESC LIMIT 1) as final_balance
FROM customers c
LEFT JOIN settlement_history sh ON c.id = sh.customerId
WHERE sh.id IS NOT NULL
GROUP BY c.id
ORDER BY settlement_count DESC
LIMIT 20;

-- [MONTHLY-04] Data Quality Score
-- Comprehensive metric on database health
SELECT 
    'MONTHLY-04: Data quality',
    'total_customers' as metric,
    COUNT(DISTINCT id) as value,
    'healthy' as status
FROM customers

UNION ALL

SELECT 
    'MONTHLY-04: Data quality',
    'orphaned_orders',
    COUNT(*),
    CASE WHEN COUNT(*) = 0 THEN 'healthy' ELSE 'warning' END
FROM orders WHERE customerId IS NULL

UNION ALL

SELECT 
    'MONTHLY-04: Data quality',
    'orphaned_settlements',
    COUNT(*),
    CASE WHEN COUNT(*) = 0 THEN 'healthy' ELSE 'warning' END
FROM settlement_history WHERE customerId IS NULL

UNION ALL

SELECT 
    'MONTHLY-04: Data quality',
    'duplicate_customer_variants',
    COUNT(*),
    CASE WHEN COUNT(*) = 0 THEN 'healthy' ELSE 'critical' END
FROM (
    SELECT LOWER(TRIM(customerName)) as norm
    FROM orders
    GROUP BY LOWER(TRIM(customerName))
    HAVING COUNT(DISTINCT customerName) > 1
)

UNION ALL

SELECT 
    'MONTHLY-04: Data quality',
    'balance_mismatches',
    COUNT(*),
    CASE WHEN COUNT(*) = 0 THEN 'healthy' ELSE 'warning' END
FROM (
    SELECT c.id
    FROM customers c
    LEFT JOIN orders o ON c.id = o.customerId
    LEFT JOIN settlement_history sh ON c.id = sh.customerId
    GROUP BY c.id
    HAVING ABS(COALESCE(SUM(o.totalAmount - o.paidAmount), 0) - 
               COALESCE(MAX(sh.balanceAfter), 0)) > 1.0
);

-- [MONTHLY-05] Business Metrics
-- Revenue, collections, outstanding receivables
SELECT 
    'MONTHLY-05: Business metrics',
    'current_month_revenue' as metric,
    ROUND(SUM(totalAmount), 2) as value
FROM orders
WHERE date >= datetime('now', 'start of month')

UNION ALL

SELECT 
    'MONTHLY-05: Business metrics',
    'lifetime_revenue',
    ROUND(SUM(totalAmount), 2)
FROM orders

UNION ALL

SELECT 
    'MONTHLY-05: Business metrics',
    'total_outstanding_receivables',
    ROUND(SUM(totalAmount - paidAmount), 2)
FROM orders
WHERE totalAmount > paidAmount

UNION ALL

SELECT 
    'MONTHLY-05: Business metrics',
    'customers_with_debt',
    COUNT(DISTINCT customerId)
FROM orders
WHERE totalAmount > paidAmount

UNION ALL

SELECT 
    'MONTHLY-05: Business metrics',
    'customers_paid_in_full',
    COUNT(DISTINCT customerId)
FROM orders
WHERE totalAmount <= paidAmount;

-- ============================================================================
-- QUARTERLY DEEP DIVE
-- ============================================================================

-- [QUARTERLY-01] Historical Trend Analysis
-- Track customer acquisition and retention
SELECT 
    'QUARTERLY-01: Customer trends',
    CAST(c.createdAt / 2592000000 AS INTEGER) * 2592000000 as cohort_month,
    COUNT(*) as new_customers_created,
    COUNT(DISTINCT (SELECT customerId FROM orders o WHERE o.customerId = c.id)) as customers_with_orders,
    COUNT(DISTINCT (SELECT customerId FROM settlement_history sh WHERE sh.customerId = c.id)) as customers_with_settlements
FROM customers c
GROUP BY cohort_month
ORDER BY cohort_month DESC;

-- [QUARTERLY-02] Payment Method Analysis
-- Cash vs UPI breakdown
SELECT 
    'QUARTERLY-02: Payment methods',
    paymentMethod as method,
    COUNT(*) as order_count,
    ROUND(SUM(totalAmount), 2) as revenue,
    ROUND(SUM(paidAmount), 2) as amount_collected,
    ROUND(AVG(totalAmount - paidAmount), 2) as avg_pending_per_order
FROM orders
GROUP BY paymentMethod;

-- [QUARTERLY-03] Settlement Type Distribution
-- What types of transactions are being recorded
SELECT 
    'QUARTERLY-03: Settlement types',
    type as settlement_type,
    COUNT(*) as count,
    ROUND(SUM(settledAmount), 2) as total_amount,
    ROUND(AVG(settledAmount), 2) as avg_amount,
    COUNT(DISTINCT customerName) as unique_customers
FROM settlement_history
GROUP BY type
ORDER BY count DESC;

-- ============================================================================
-- ALERTS & THRESHOLDS
-- ============================================================================

-- Use these queries to populate alerts if values exceed thresholds

-- [ALERT-01] High Outstanding Debt
-- Alert if any single customer owes > ₹5000
SELECT 
    'ALERT: High customer debt',
    c.name,
    ROUND(SUM(o.totalAmount - o.paidAmount), 2) as outstanding,
    COUNT(o.id) as order_count
FROM customers c
JOIN orders o ON c.id = o.customerId
WHERE o.totalAmount > o.paidAmount
GROUP BY c.id
HAVING SUM(o.totalAmount - o.paidAmount) > 5000.0;

-- [ALERT-02] Stale Debt  
-- Alert if customer debt is > 60 days old
SELECT 
    'ALERT: Stale debt',
    c.name,
    ROUND(SUM(o.totalAmount - o.paidAmount), 2) as outstanding,
    CAST((CURRENT_TIMESTAMP - MAX(o.date)) / 86400000 AS INTEGER) as days_old
FROM customers c
JOIN orders o ON c.id = o.customerId
WHERE o.totalAmount > o.paidAmount
GROUP BY c.id
HAVING days_old > 60;

-- [ALERT-03] Data Integrity Issues
-- Alert if any data quality issue detected
SELECT 
    'ALERT: Data integrity',
    'issue_type',
    'count',
    'severity'
UNION ALL
SELECT 
    'ALERT: Data integrity',
    'orphaned_orders',
    COUNT(*),
    CASE WHEN COUNT(*) > 0 THEN 'CRITICAL' ELSE 'OK' END
FROM orders WHERE customerId IS NULL

UNION ALL
SELECT 
    'ALERT: Data integrity',
    'duplicate_names',
    COUNT(*),
    CASE WHEN COUNT(*) > 0 THEN 'HIGH' ELSE 'OK' END
FROM (
    SELECT LOWER(TRIM(customerName)) FROM orders
    GROUP BY LOWER(TRIM(customerName))
    HAVING COUNT(DISTINCT customerName) > 1
);

-- ============================================================================
-- IMPLEMENTATION GUIDE
-- ============================================================================

/*

STEP 1: Add to Monitoring Dashboard
- Weekly checks: Monitor every Monday 9 AM
- Monthly checks: Monitor on 1st of each month
- Quarterly checks: Monitor on 1st of Q
- Alerts: Check daily or integrate with monitoring service

STEP 2: Create Monitoring Worker
```kotlin
class DataQualityWorker : CoroutineWorker() {
    override suspend fun doWork(): Result {
        val dao = app.database.printDao()
        
        // Run weak checks
        val orphanedOrders = dao.countOrphanedOrders()
        if (orphanedOrders > 0) {
            Log.e("DataQuality", "Found $orphanedOrders orphaned orders!")
            // Send alert
        }
        
        val duplicates = dao.countDuplicateCustomers()
        if (duplicates > 0) {
            Log.e("DataQuality", "Found $duplicates duplicate customer sets!")
        }
        
        return Result.success()
    }
}

// Schedule weekly:
PeriodicWorkRequestBuilder<DataQualityWorker>(
    7, TimeUnit.DAYS
).build().let { WorkManager.getInstance(context).enqueueUniquePeriodicWork(...) }
```

STEP 3: Create Dashboards
- Grafana/Kibana dashboard to visualize metrics
- Alert thresholds that send notifications
- Weekly reports to stakeholders

STEP 4: Regular Reviews
- Weekly: Check for orphaned records, data integrity
- Monthly: Business metrics, revenue trends
- Quarterly: Cohort analysis, historical trends

*/

-- ============================================================================
-- END OF MONITORING QUERIES
-- ============================================================================
