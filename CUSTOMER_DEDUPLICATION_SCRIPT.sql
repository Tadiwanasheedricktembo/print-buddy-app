-- ============================================================================
-- CUSTOMER DEDUPLICATION & CLEANUP SCRIPT
-- ============================================================================
--
-- PURPOSE: Detect and clean existing duplicates & orphaned records
-- USAGE: Run BEFORE migration to understand scope of problem
-- SAFETY: All queries are READ-ONLY unless explicitly marked for UPDATE/DELETE
--
-- ============================================================================

-- ============================================================================
-- PART 1: DUPLICATE DETECTION & ANALYSIS
-- ============================================================================

-- Query 1: Show all customer name variations (grouped by normalized name)
SELECT 
    LOWER(TRIM(customerName)) as normalized_name,
    COUNT(DISTINCT customerName) as variant_count,
    GROUP_CONCAT(DISTINCT customerName, ' | ') as all_variants,
    COUNT(*) as total_orders,
    SUM(totalAmount) as total_revenue,
    SUM(totalAmount - paidAmount) as total_unpaid_debt
FROM orders
GROUP BY LOWER(TRIM(customerName))
HAVING COUNT(DISTINCT customerName) > 1
ORDER BY total_unpaid_debt DESC;

-- Query 2: Specific duplicate example - customer "Rahul" variations
SELECT 
    customerName,
    COUNT(*) as order_count,
    SUM(totalAmount) as revenue,
    SUM(totalAmount - paidAmount) as unpaid_debt,
    COUNT(DISTINCT CAST(date / 86400000 AS INTEGER)) as days_with_orders,
    MIN(date) as first_order,
    MAX(date) as last_order
FROM orders
WHERE LOWER(TRIM(customerName)) = 'rahul'
GROUP BY customerName
ORDER BY order_count DESC;

-- Query 3: Settlement history fragmentation
SELECT 
    LOWER(TRIM(customerName)) as normalized_name,
    GROUP_CONCAT(DISTINCT customerName, ' | ') as variants_in_settlements,
    COUNT(DISTINCT customerName) as variant_count,
    COUNT(*) as settlement_count,
    COUNT(DISTINCT type) as settlement_types,
    SUM(CASE WHEN type='PAYMENT' THEN amountPaid ELSE 0 END) as total_payments
FROM settlement_history
GROUP BY LOWER(TRIM(customerName))
HAVING COUNT(DISTINCT customerName) > 1
ORDER BY settlement_count DESC;

-- Query 4: Debtor credits inconsistencies
SELECT 
    LOWER(TRIM(customerName)) as normalized_name,
    GROUP_CONCAT(DISTINCT customerName, ' | ') as variants,
    COUNT(*) as debtcredit_count,
    SUM(amount) as total_amount
FROM debtor_credits
GROUP BY LOWER(TRIM(customerName))
ORDER BY debtcredit_count DESC;

-- ============================================================================
-- PART 2: ORPHANED RECORD DETECTION
-- ============================================================================

-- Query 5: Orphaned orders (no settlement history)
SELECT 
    'orphaned_order' as issue_type,
    o.id as order_id,
    o.customerName,
    o.totalAmount,
    o.paidAmount,
    o.date,
    COALESCE((SELECT COUNT(*) FROM settlement_history sh 
              WHERE LOWER(TRIM(sh.customerName)) = LOWER(TRIM(o.customerName))), 0) as settlement_count
FROM orders o
WHERE LOWER(TRIM(o.customerName)) NOT IN (
    SELECT LOWER(TRIM(DISTINCT customerName)) FROM settlement_history
)
ORDER BY o.date DESC
LIMIT 20;

-- Query 6: Orphaned settlement records (no corresponding orders)
SELECT 
    'orphaned_settlement' as issue_type,
    sh.id as settlement_id,
    sh.customerName,
    sh.balanceBefore,
    sh.settledAmount,
    sh.timestamp,
    COALESCE((SELECT COUNT(*) FROM orders o 
              WHERE LOWER(TRIM(o.customerName)) = LOWER(TRIM(sh.customerName))), 0) as order_count
FROM settlement_history sh
WHERE LOWER(TRIM(sh.customerName)) NOT IN (
    SELECT LOWER(TRIM(DISTINCT customerName)) FROM orders
)
ORDER BY sh.timestamp DESC
LIMIT 20;

-- Query 7: Orphaned debtor credit records
SELECT 
    'orphaned_debtor_credit' as issue_type,
    dc.customerName,
    dc.amount,
    dc.lastUpdated,
    (SELECT COUNT(*) FROM orders o 
     WHERE LOWER(TRIM(o.customerName)) = LOWER(TRIM(dc.customerName))) as order_count,
    (SELECT COUNT(*) FROM settlement_history sh 
     WHERE LOWER(TRIM(sh.customerName)) = LOWER(TRIM(dc.customerName))) as settlement_count
FROM debtor_credits dc
WHERE LOWER(TRIM(dc.customerName)) NOT IN (
    SELECT LOWER(TRIM(DISTINCT customerName)) FROM orders
)
AND LOWER(TRIM(dc.customerName)) NOT IN (
    SELECT LOWER(TRIM(DISTINCT customerName)) FROM settlement_history
);

-- ============================================================================
-- PART 3: BALANCE INCONSISTENCY DETECTION
-- ============================================================================

-- Query 8: Orders vs Settlement balance mismatch
SELECT 
    c.normalized_name,
    c.order_total_unpaid as orders_show_debt,
    c.latest_settlement as settlement_shows_balance,
    (c.order_total_unpaid - c.latest_settlement) as discrepancy,
    CASE 
        WHEN ABS(c.order_total_unpaid - c.latest_settlement) > 0.1 THEN 'MISMATCH'
        ELSE 'consistent'
    END as status
FROM (
    SELECT 
        LOWER(TRIM(o.customerName)) as normalized_name,
        SUM(o.totalAmount - o.paidAmount) as order_total_unpaid,
        (SELECT COALESCE(balanceAfter, 0)
         FROM settlement_history sh
         WHERE LOWER(TRIM(sh.customerName)) = LOWER(TRIM(o.customerName))
         ORDER BY sh.timestamp DESC
         LIMIT 1) as latest_settlement
    FROM orders o
    WHERE o.totalAmount > o.paidAmount
    GROUP BY LOWER(TRIM(o.customerName))
) c
WHERE ABS(c.order_total_unpaid - c.latest_settlement) > 0.1
ORDER BY ABS(discrepancy) DESC;

-- Query 9: Zero-balance false positives (fully paid orders but settlement shows debt)
SELECT 
    LOWER(TRIM(o.customerName)) as customer,
    (SELECT balanceAfter FROM settlement_history sh 
     WHERE LOWER(TRIM(sh.customerName)) = LOWER(TRIM(o.customerName))
     ORDER BY timestamp DESC LIMIT 1) as settlement_balance
FROM orders o
WHERE o.totalAmount = o.paidAmount  -- Order fully paid
GROUP BY LOWER(TRIM(o.customerName))
HAVING settlement_balance > 0.1;  -- But settlement shows debt

-- ============================================================================
-- PART 4: DATA CLEANUP STRATEGIES
-- ============================================================================

-- Strategy 1: Identify which variant should be the canonical name
-- (Use the longest/most complete version)
WITH canonical_names AS (
    SELECT 
        LOWER(TRIM(customerName)) as normalized_name,
        (SELECT customerName 
         FROM orders o2
         WHERE LOWER(TRIM(o2.customerName)) = LOWER(TRIM(o1.customerName))
         ORDER BY LENGTH(customerName) DESC
         LIMIT 1) as canonical_name,
        COUNT(*) as order_count
    FROM orders o1
    GROUP BY LOWER(TRIM(customerName))
)
SELECT 
    normalized_name,
    canonical_name,
    order_count
FROM canonical_names
WHERE canonical_name IS NOT NULL
ORDER BY order_count DESC;

-- Strategy 2: For manual cleanup - show SQL statements to consolidate
-- (DO NOT execute without review - test in staging first!)
SELECT 
    'UPDATE orders SET customerName = ''' || 
    (SELECT customerName FROM orders o2 WHERE LOWER(TRIM(o2.customerName)) = LOWER(TRIM(o1.customerName)) ORDER BY LENGTH(customerName) DESC LIMIT 1) ||
    ''' WHERE customerName = ''' || o1.customerName || ''';' as update_statement
FROM (
    SELECT DISTINCT customerName FROM orders
) o1
WHERE LOWER(TRIM(customerName)) IN (
    SELECT LOWER(TRIM(customerName)) FROM orders 
    GROUP BY LOWER(TRIM(customerName)) 
    HAVING COUNT(DISTINCT customerName) > 1
)
ORDER BY update_statement;

-- ============================================================================
-- PART 5: COMPLETENESS METRICS
-- ============================================================================

-- Query 10: Overall data quality score
SELECT 
    'data_quality_metrics' as metric_type,
    'total_orders' as metric_name,
    COUNT(*) as value,
    'count of all orders' as description
FROM orders

UNION ALL

SELECT 
    'data_quality_metrics',
    'unique_customer_names',
    COUNT(DISTINCT customerName),
    'count of distinct names in orders'
FROM orders

UNION ALL

SELECT 
    'data_quality_metrics',
    'unique_normalized_names',
    COUNT(DISTINCT LOWER(TRIM(customerName))),
    'count of normalized customer identities'
FROM orders

UNION ALL

SELECT
    'data_quality_metrics',
    'duplicate_customer_sets',
    COUNT(*),
    'number of customer groups with variations'
FROM (
    SELECT LOWER(TRIM(customerName)) as norm
    FROM orders
    GROUP BY LOWER(TRIM(customerName))
    HAVING COUNT(DISTINCT customerName) > 1
)

UNION ALL

SELECT
    'data_quality_metrics',
    'total_unpaid_debt',
    CAST(SUM(totalAmount - paidAmount) as INTEGER),
    'total rupees owed across all orders'
FROM orders
WHERE totalAmount > paidAmount

UNION ALL

SELECT
    'data_quality_metrics',
    'partially_paid_orders',
    COUNT(*),
    'orders with 0 < paidAmount < totalAmount'
FROM orders
WHERE paidAmount > 0 AND paidAmount < totalAmount

UNION ALL

SELECT
    'data_quality_metrics',
    'settlement_history_records',
    COUNT(*),
    'total settlement history entries'
FROM settlement_history

UNION ALL

SELECT
    'data_quality_metrics',
    'debtor_credit_records',
    COUNT(*),
    'total debtor credit entries'
FROM debtor_credits

UNION ALL

SELECT
    'data_quality_metrics',
    'orphaned_settlements',
    COUNT(*),
    'settlement records with no matching orders'
FROM settlement_history sh
WHERE LOWER(TRIM(sh.customerName)) NOT IN (
    SELECT LOWER(TRIM(DISTINCT customerName)) FROM orders
);

-- ============================================================================
-- PART 6: EXPORT FOR ANALYSIS
-- ============================================================================

-- Query 11: Complete duplicate customer report (CSV-ready)
SELECT 
    LOWER(TRIM(customerName)) as 'Normalized Name',
    GROUP_CONCAT(customerName, ' | ') as 'All Variants',
    COUNT(DISTINCT customerName) as 'Variant Count',
    COUNT(*) as 'Total Orders',
    SUM(totalAmount) as 'Total Amount',
    SUM(CASE WHEN totalAmount > paidAmount THEN (totalAmount - paidAmount) ELSE 0 END) as 'Total Unpaid'
FROM orders
GROUP BY LOWER(TRIM(customerName))
ORDER BY 'Total Orders' DESC;

-- ============================================================================
-- EXECUTION STEPS (BEFORE MIGRATION)
-- ============================================================================

-- 1. Run Queries 1-4 to see extent of duplication
-- 2. Run Queries 5-7 to identify orphans
-- 3. Run Queries 8-9 to find balance inconsistencies
-- 4. Review Strategy 1 & 2 to plan consolidation
-- 5. Run Query 10 to understand overall data quality
-- 6. Export Query 11 for reporting to stakeholders
-- 7. Review output and get approval to proceed with migration
-- 8. Execute MIGRATION_SQL_v10_to_v11.sql
-- 9. Re-run Queries 1-4 post-migration (should show 0 duplicates)

-- ============================================================================
-- POST-MIGRATION CLEANUP (OPTIONAL)
-- ============================================================================

-- After migration is complete and tested (do NOT do this immediately):
-- 
-- 1. Verify duplicate customers merged correctly:
-- SELECT COUNT(DISTINCT normalizedName) FROM customers;
-- -- Should much less than count of customers before migration
--
-- 2. Remove old customerName references (if using new schema exclusively):
-- -- ALTER TABLE orders DROP COLUMN customerName;
-- -- ALTER TABLE settlement_history DROP COLUMN customerName;
-- -- ALTER TABLE debtor_credits DROP COLUMN customerName;
--
-- 3. Rebuild indexes:
-- -- REINDEX;
-- -- ANALYZE;
--
-- 4. Vacuum database:
-- -- VACUUM;

-- ============================================================================
-- END OF DEDUPLICATION SCRIPT
-- ============================================================================
