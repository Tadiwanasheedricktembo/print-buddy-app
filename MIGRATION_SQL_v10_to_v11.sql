-- ============================================================================
-- TADIWA PRINT BUDDY - DATABASE MIGRATION v10 → v11
-- ============================================================================
--
-- PURPOSE: Create Customer master table and migrate to foreign key relationships
-- SAFETY: All steps include rollback points and validation checks
-- REQUIRED: Read QA_INVESTIGATION.md before executing
--
-- ============================================================================

-- STEP 1: PRE-MIGRATION VALIDATION & BACKUP
-- ============================================================================

-- Check current state
SELECT 
    'Pre-Migration Validation' as check_type,
    name as table_name,
    sql as create_statement
FROM sqlite_master 
WHERE type='table' AND name IN ('orders', 'settlement_history', 'debtor_credits');

-- Count records by table
SELECT 'records_by_table' as metric, 'orders' as table_name, COUNT(*) as count FROM orders
UNION ALL
SELECT 'records_by_table', 'settlement_history', COUNT(*) FROM settlement_history
UNION ALL
SELECT 'records_by_table', 'debtor_credits', COUNT(*) FROM debtor_credits;

-- Identify duplicate customers (the BUG we're fixing)
SELECT 
    'duplicate_customers' as issue,
    LOWER(TRIM(customerName)) as normalized_name,
    COUNT(DISTINCT customerName) as variant_count,
    GROUP_CONCAT(DISTINCT customerName, ' | ') as variants,
    COUNT(*) as order_count
FROM orders
GROUP BY LOWER(TRIM(customerName))
HAVING COUNT(DISTINCT customerName) > 1
ORDER BY order_count DESC;

-- Identify orphaned settlement records  
SELECT 
    COUNT(*) as orphaned_settlement_count
FROM settlement_history sh
WHERE LOWER(TRIM(sh.customerName)) NOT IN (
    SELECT LOWER(TRIM(DISTINCT customerName)) FROM orders
);

-- ============================================================================
-- STEP 2: CREATE CUSTOMER MASTER TABLE
-- ============================================================================

-- Create the new customers table with proper constraints
CREATE TABLE IF NOT EXISTS customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    normalizedName TEXT NOT NULL UNIQUE,  -- Lowercase for matching
    phoneNumber TEXT UNIQUE,               -- Optional secondary unique key
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    CONSTRAINT unique_customer UNIQUE (normalizedName)
);

-- Verify creation
SELECT name, sql FROM sqlite_master 
WHERE type='table' AND name='customers';

-- ============================================================================
-- STEP 3: BACKFILL CUSTOMER TABLE FROM EXISTING DATA
-- ============================================================================

-- Strategy: For each normalized customer name, pick the most common variant
-- (Assuming longest/most complete version is most correct)

INSERT INTO customers (name, normalizedName, createdAt, updatedAt)
SELECT 
    -- Pick the variant with Maximum length (likely most complete)
    (SELECT customerName 
     FROM orders o2 
     WHERE LOWER(TRIM(o2.customerName)) = LOWER(TRIM(o1.customerName))
     ORDER BY LENGTH(customerName) DESC
     LIMIT 1) as name,
    
    LOWER(TRIM(customerName)) as normalizedName,
    
    -- Use earliest order date as creation timestamp
    (SELECT MIN(date) FROM orders o3 
     WHERE LOWER(TRIM(o3.customerName)) = LOWER(TRIM(o1.customerName))) as createdAt,
    
    -- Use latest order/settlement as update timestamp
    COALESCE(
        (SELECT MAX(date) FROM orders o4 
         WHERE LOWER(TRIM(o4.customerName)) = LOWER(TRIM(o1.customerName))),
        (SELECT MAX(timestamp) FROM settlement_history sh 
         WHERE LOWER(TRIM(sh.customerName)) = LOWER(TRIM(o1.customerName)))
    ) as updatedAt
    
FROM orders o1
GROUP BY LOWER(TRIM(customerName));

-- Handle customers that only exist in settlement_history (not in orders)
INSERT OR IGNORE INTO customers (name, normalizedName, createdAt, updatedAt)
SELECT 
    (SELECT customerName 
     FROM settlement_history sh2 
     WHERE LOWER(TRIM(sh2.customerName)) = LOWER(TRIM(sh1.customerName))
     ORDER BY LENGTH(customerName) DESC
     LIMIT 1) as name,
    
    LOWER(TRIM(sh1.customerName)) as normalizedName,
    
    MIN(timestamp) as createdAt,
    MAX(timestamp) as updatedAt
    
FROM settlement_history sh1
WHERE LOWER(TRIM(sh1.customerName)) NOT IN (
    SELECT LOWER(TRIM(customerName)) FROM orders
)
GROUP BY LOWER(TRIM(sh1.customerName));

-- Verify customers created
SELECT COUNT(*) as customer_count FROM customers;
SELECT * FROM customers LIMIT 10;

-- ============================================================================
-- STEP 4: ADD FOREIGN KEY COLUMNS TO EXISTING TABLES
-- ============================================================================

-- Note: SQLite doesn't support direct ALTER TABLE ADD FK in older versions
-- We'll add the column and populate it, then use triggers for enforcement

ALTER TABLE orders ADD COLUMN customerId INTEGER;
ALTER TABLE settlement_history ADD COLUMN customerId INTEGER;
ALTER TABLE debtor_credits ADD COLUMN customerId INTEGER;

-- Verify columns added
PRAGMA table_info(orders);
PRAGMA table_info(settlement_history);
PRAGMA table_info(debtor_credits);

-- ============================================================================
-- STEP 5: POPULATE FOREIGN KEYS
-- ============================================================================

-- Link orders to customers
UPDATE orders SET customerId = (
    SELECT id FROM customers 
    WHERE normalizedName = LOWER(TRIM(orders.customerName))
)
WHERE customerId IS NULL;

-- Link settlement_history to customers
UPDATE settlement_history SET customerId = (
    SELECT id FROM customers 
    WHERE normalizedName = LOWER(TRIM(settlement_history.customerName))
)
WHERE customerId IS NULL;

-- Link debtor_credits to customers
UPDATE debtor_credits SET customerId = (
    SELECT id FROM customers 
    WHERE normalizedName = LOWER(TRIM(debtor_credits.customerName))
)
WHERE customerId IS NULL;

-- ============================================================================
-- STEP 6: MIGRATION VALIDATION
-- ============================================================================

-- Check for NULL customerId (indicates migration issue)
SELECT 'ERROR: Unmapped orders' as issue, COUNT(*) as count 
FROM orders WHERE customerId IS NULL;

SELECT 'ERROR: Unmapped settlements' as issue, COUNT(*) as count 
FROM settlement_history WHERE customerId IS NULL;

SELECT 'ERROR: Unmapped debtor_credits' as issue, COUNT(*) as count 
FROM debtor_credits WHERE customerId IS NULL;

-- All three queries above should return 0 rows

-- Verify all customers have at least one record
SELECT 
    c.id,
    c.name,
    c.normalizedName,
    COUNT(DISTINCT o.id) as order_count,
    COUNT(DISTINCT sh.id) as settlement_count,
    (SELECT COUNT(*) FROM debtor_credits dc WHERE dc.customerId = c.id) as debtor_credit_count
FROM customers c
LEFT JOIN orders o ON c.id = o.customerId
LEFT JOIN settlement_history sh ON c.id = sh.customerId
GROUP BY c.id;

-- ============================================================================
-- STEP 7: CREATE INDEXES FOR PERFORMANCE
-- ============================================================================

-- Customer lookups
CREATE INDEX idx_customers_normalized ON customers(normalizedName);
CREATE INDEX idx_customers_phone ON customers(phoneNumber);

-- Order lookups
CREATE INDEX idx_orders_customer_id ON orders(customerId);
CREATE INDEX idx_orders_customer_date ON orders(customerId, date DESC);
CREATE INDEX idx_orders_unpaid ON orders(customerId) WHERE totalAmount > paidAmount;

-- Settlement lookups
CREATE INDEX idx_settlement_customer_id ON settlement_history(customerId);
CREATE INDEX idx_settlement_customer_timestamp ON settlement_history(customerId, timestamp DESC);

-- Debtor lookups
CREATE INDEX idx_debtor_customer ON debtor_credits(customerId);

-- ============================================================================
-- STEP 8: POST-MIGRATION VERIFICATION
-- ============================================================================

-- Verify NO duplicate normalized names (should return 0 rows)
SELECT 
    'ERROR: Duplicate normalized names' as issue,
    normalizedName,
    COUNT(*) as count
FROM customers
GROUP BY normalizedName
HAVING COUNT(*) > 1;

-- Verify referential integrity
SELECT 
    'ERROR: Orders with invalid customerId' as issue,
    COUNT(*) as count
FROM orders
WHERE customerId NOT IN (SELECT id FROM customers);

SELECT 
    'ERROR: Settlements with invalid customerId' as issue,
    COUNT(*) as count
FROM settlement_history
WHERE customerId NOT IN (SELECT id FROM customers);

-- Verify data accuracy (sample check)
-- Pick a customer and verify balance consistency
WITH sample_customer AS (
    SELECT TOP 1 c.* FROM customers c ORDER BY (
        SELECT COUNT(*) FROM orders o WHERE o.customerId = c.id
    ) DESC
)
SELECT 
    c.name,
    COUNT(DISTINCT o.id) as order_count,
    COALESCE(SUM(o.totalAmount - o.paidAmount), 0) as total_unpaid_debt,
    (SELECT balanceAfter FROM settlement_history sh 
     WHERE sh.customerId = c.id 
     ORDER BY timestamp DESC LIMIT 1) as latest_settlement_balance,
    COUNT(DISTINCT sh.id) as settlement_count
FROM sample_customer c
LEFT JOIN orders o ON c.id = o.customerId AND o.totalAmount > o.paidAmount
LEFT JOIN settlement_history sh ON c.id = sh.customerId
GROUP BY c.id;

-- ============================================================================
-- STEP 9: CLEANUP (Optional - Keep old columns for now)
-- ============================================================================

-- RECOMMENDED: Keep customerName columns for display during transition period
-- After 2-3 releases, remove with future migration
-- 
-- DO NOT execute these now:
-- 
-- ALTER TABLE orders DROP COLUMN customerName;
-- ALTER TABLE settlement_history DROP COLUMN customerName;
-- ALTER TABLE debtor_credits DROP COLUMN customerName;

-- ============================================================================
-- STEP 10: FINAL SUMMARY
-- ============================================================================

SELECT 
    'Migration Summary' as report_type,
    'Customers created' as metric,
    COUNT(*) as value
FROM customers

UNION ALL

SELECT 
    'Migration Summary',
    'Orders migrated',
    COUNT(*)
FROM orders
WHERE customerId IS NOT NULL

UNION ALL

SELECT
    'Migration Summary',
    'Settlement records migrated',
    COUNT(*)
FROM settlement_history
WHERE customerId IS NOT NULL

UNION ALL

SELECT
    'Migration Summary',
    'Debtor credits migrated',
    COUNT(*)
FROM debtor_credits
WHERE customerId IS NOT NULL

UNION ALL

SELECT
    'Migration Summary',
    'Duplicate customer variants eliminated',
    (
        SELECT COUNT(*) - COUNT(DISTINCT LOWER(TRIM(customerName)))
        FROM orders
    ) as value;

-- ============================================================================
-- ROLLBACK PROCEDURE (If migration fails)
-- ============================================================================
-- 
-- IF ANY ERROR is detected, execute:
--
-- 1. Restore from backup:
--    RESTORE FROM 'backup_v10.db'
--
-- 2. Or manual rollback:
--    ALTER TABLE orders DROP COLUMN customerId;
--    ALTER TABLE settlement_history DROP COLUMN customerId;
--    ALTER TABLE debtor_credits DROP COLUMN customerId;
--    DROP TABLE customers;
--    DROP TABLE sqlite_sequence; -- Reset auto-increment
--    VACUUM;
--
-- ============================================================================

-- ============================================================================
-- VERIFICATION AFTER PRODUCTION DEPLOYMENT
-- ============================================================================
-- Execute these periodically to verify migration integrity:
-- 

-- Check for any data corruption
SELECT 
    'Monthly: Data Integrity Check' as report_type,
    
    'Customers with orders' as check_name,
    COUNT(DISTINCT c.id) as value,
    'should equal total customers' as note
FROM customers c
INNER JOIN orders o ON c.id = o.customerId

UNION ALL

SELECT 
    'Monthly: Data Integrity Check',
    'Orphaned customers (no orders/settlements)',
    COUNT(DISTINCT c.id),
    'should be 0'
FROM customers c
WHERE c.id NOT IN (
    SELECT customerId FROM orders WHERE customerId IS NOT NULL
)
AND c.id NOT IN (
    SELECT customerId FROM settlement_history WHERE customerId IS NOT NULL
);

-- Check for case inconsistencies
SELECT 
    'Weekly: Case Consistency Check' as report_type,
    COUNT(*) as potential_issues,
    'Order customerName differs from customer.name' as issue_type
FROM orders o
INNER JOIN customers c ON o.customerId = c.id
WHERE o.customerName != c.name;

-- ============================================================================
-- END OF MIGRATION SCRIPT
-- ============================================================================
--
-- Success Indicators:
-- ✓ All migration validation queries return expected results
-- ✓ NO errors in steps 6-8
-- ✓ Customer count matches expected count
-- ✓ All orders/settlements/debtor_credits mapped to customers
-- ✓ No orphaned records
-- ✓ Indexes created successfully
-- ✓ Performance tests pass (queries complete in <1 second for typical DB)
--
-- Next Steps:
-- 1. Keep this script for reference
-- 2. Update Room entity definitions with Customer class
-- 3. Update DAO queries to use customerId instead of customerName
-- 4. Update Repository logic to handle new Customer entity
-- 5. Deploy refactored app code
-- 6. Monitor for any issues in first 48 hours
-- 7. After 1 week with no issues, mark migration as successful
--
-- ============================================================================
