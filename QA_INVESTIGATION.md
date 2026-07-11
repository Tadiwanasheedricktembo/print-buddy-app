# TADIWA PRINT BUDDY - COMPREHENSIVE QA INVESTIGATION
## Customer Identity Duplication & Debt Tracking Issues

**Investigation Date:** May 14, 2026  
**App Version:** Database v10  
**Severity:** CRITICAL - Data Integrity Issue  

---

## EXECUTIVE SUMMARY

The app has a **critical database architecture flaw** where customer identity is managed using a mutable string (`customerName`) instead of an immutable unique ID. This creates multiple failure modes:

1. **Duplicate Customer Creation** - Same customer stored multiple times due to case/whitespace variations
2. **Fragmented Debt Tracking** - Debt records split across multiple customer entries
3. **Balance Calculation Errors** - Incorrect totals when customer names don't match exactly
4. **Settlement History Inconsistencies** - Payment history disconnected from order history
5. **No Referential Integrity** - No foreign keys enforce data consistency

**Root Cause:** Three critical tables use `customerName` as identifier with NO:
- Customer master table with unique ID
- Foreign key constraints
- Transaction support for multi-table operations
- Case-normalization enforcement

---

## PART 1: DATABASE STRUCTURE ANALYSIS

### Current Schema Vulnerabilities

#### Table: `orders`
```sql
CREATE TABLE orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    totalAmount REAL,
    date LONG,
    customerName TEXT,              -- ❌ PROBLEM: Mutable string identifier
    paidAmount REAL DEFAULT 0,
    paymentMethod TEXT DEFAULT 'CASH'
)
```
**Issues:**
- No UNIQUE constraint on customerName
- No foreign key to Customer table (doesn't exist)
- Case-sensitive matching at SQLite level
- Whitespace NOT trimmed at DB level

#### Table: `debtor_credits`
```sql
CREATE TABLE debtor_credits (
    customerName TEXT PRIMARY KEY,  -- ❌ PROBLEM: Same mutable string is PK
    amount REAL,
    lastUpdated LONG
)
```
**Issues:**
- SQLite treats "Rahul" and "rahul" as different keys
- Whitespace variations create separate keys
- No CASCADE behavior with orders table
- No referential integrity

#### Table: `settlement_history`
```sql
CREATE TABLE settlement_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customerName TEXT NOT NULL,     -- ❌ PROBLEM: No FK, no uniqueness
    previousBalance REAL NOT NULL,
    settledAmount REAL NOT NULL,
    remainingBalance REAL NOT NULL,
    timestamp LONG NOT NULL,
    type TEXT DEFAULT 'PAYMENT',
    note TEXT DEFAULT ''
)
```
**Issues:**
- No relationship enforcement with orders or debtor_credits
- Multiple balance records don't validate against actual order sum
- Historical data can diverge from current transaction state

#### Table: `OrderItem`
```sql
CREATE TABLE OrderItem (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    orderId INTEGER NOT NULL,       -- ✓ Good: Has FK
    serviceName TEXT NOT NULL,
    price REAL NOT NULL,
    quantity INTEGER NOT NULL,
    FOREIGN KEY (orderId) REFERENCES orders(id) ON DELETE CASCADE
)
```
**Status:** ✓ Correctly references orders table

### Missing Table: `customers` (SHOULD EXIST)
```sql
-- THIS TABLE DOES NOT EXIST - IT SHOULD!
CREATE TABLE customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE NOT NULL,
    normalizedName TEXT NOT NULL UNIQUE,  -- Lowercase for matching
    phoneNumber TEXT UNIQUE,               -- Optional secondary key
    createdAt LONG NOT NULL,
    updatedAt LONG NOT NULL
)
```

### Missing Constraints

| Constraint | Current | Should Be | Impact |
|-----------|---------|-----------|--------|
| Customer Identity | String (mutable) | UUID/ID (immutable) | **CRITICAL** |
| Case Normalization | None | Enforced at DB level | **HIGH** |
| Foreign Keys | No | orders→customers | Missing |
| | | settlement_history→customers | Missing |
| | | debtor_credits→customers | Missing |
| Unique Constraints | No | Name + normalizedName | **HIGH** |
| Transactions | No | ACID for multi-table ops | **MEDIUM** |
| Indexes | No | On normalizedName | **MEDIUM** |

---

## PART 2: REPRODUCTION SCENARIOS

### Scenario 1: Exact Duplicate (Case Variation)
**Steps:**
1. Create customer: "Rahul" with order ₹100 unpaid
2. Create another order: "rahul" with order ₹50 unpaid

**Expected:**
- Single customer record
- ₹150 total debt

**Actual Result:**
```
Orders Table:
  id=1, customerName="Rahul", totalAmount=100, paidAmount=0
  id=2, customerName="rahul", totalAmount=50, paidAmount=0

getUnpaidOrdersForCustomer("Rahul"):
  → Returns only order#1 (₹100)
  → Missing order#2 (₹50)

getCustomerBalance("Rahul"):
  → Searches settlement_history
  → Customer "rahul" may not have any settlement records
  → Returns 0.0 (balance unknown!)
```

**Root Cause:**
- Line in PrintRepository: `getUnpaidOrdersForCustomer(customerName: String)` uses `WHERE customerName = :customerName`
- DAO query is **case-sensitive** at SQLite level
- Repository's trim/ignoreCase logic only applies in Kotlin memory, NOT to database queries

### Scenario 2: Whitespace Variation
**Steps:**
1. Create: "Rahul"
2. Create: "Rahul " (trailing space)
3. Create: " Rahul" (leading space)

**Expected:** Same customer, ₹300 debt

**Actual Result:**
```
Orders: 3 separate entries
getUnpaidOrdersForCustomer("Rahul"):
  → WHERE customerName = "Rahul"
  → Exact match ONLY
  → Other two entries ignored

Debt Fragmentation:
  - "Rahul": ₹100
  - "Rahul ": ₹100
  - " Rahul": ₹100
```

**Root Cause:**
- SQLite's WHERE clause is case-sensitive and whitespace-sensitive
- No trim() applied at DAO/query layer

### Scenario 3: Settlement/Order Mismatch (CRITICAL)
**Steps:**
1. Create order: "Rahul Sharma" ₹100 unpaid
2. Manually create settlement record: "RahulSharma" (no space) ₹40 paid
3. System tries to query customer balance for "Rahul Sharma"

**Expected:**
- Balance reflects ₹60 remaining debt

**Actual Result:**
```
getCustomerBalance("Rahul Sharma"):
  → Searches settlement_history WHERE customerName.equals(..., ignoreCase)
  → No match found (whitespace mismatch)
  → Returns 0.0
  → System thinks customer has ₹0 debt!

Later:
  → Order still shows ₹100 unpaid
  → Settlement history shows payment of ₹40
  → Balance calculations now inconsistent across views
```

**Root Cause:**
- settlement_history uses customerName (vulnerable to typos, variations)
- No validation that customerName in settlement matches orders
- No transaction linking settlement to specific order IDs

### Scenario 4: Concurrent Entry (Multi-Order Same Customer)
**Steps:**
1. User rapidly creates 3 orders for "Rahul"
2. Each order immediately after confirmation

**Issue:**
- `confirmOrder()` has no customer deduplication
- Each order creates a separate "Rahul" entry
- At the DB level, SQLite may create duplicates if not properly indexed

**Code Path:**
```kotlin
fun confirmOrder(customerName: String, cartItems: List<CartItem>): Int {
    val order = Order(
        totalAmount = total,
        customerName = customerName
    )
    printDao.insertOrder(order)  // ❌ No check if customer exists
    // DUPLICATE ORDER ALLOWED
}
```

### Scenario 5: Name Edit (Non-existent Feature)
**Current State:**
- App has NO ability to edit customer name
- If user typos: "Rahul" entered as "Rahul Sharma" by mistake
- No way to merge/rename

**If it existed:**
1. Edit customer "Rahul" → "Rahul Sharma"
2. Old debt records (settlement_history) still reference "Rahul"
3. New orders created under "Rahul Sharma"

**Result:**
- Debt split across two customer identities
- Balance calculations broken
- No referential integrity maintains old links

---

## PART 3: DATABASE INTEGRITY TESTS

### Test Suite 1: Customer Matching Consistency

**Test 1.1: Case Sensitivity Failure**
```kotlin
@Test
suspend fun testCustomerMatchingCaseSensitivity() {
    val dao = database.printDao()
    val repository = PrintRepository(dao)
    
    // Create order for "Rahul"
    val order1 = Order(
        customerName = "Rahul",
        totalAmount = 100.0,
        date = now,
        paidAmount = 0.0
    )
    val orderId = dao.insertOrder(order1)
    
    // Query with different case
    val unpaidRAHUL = dao.getUnpaidOrdersForCustomer("RAHUL")
    val unpaidRahul = dao.getUnpaidOrdersForCustomer("Rahul")
    val unpaidrahul = dao.getUnpaidOrdersForCustomer("rahul")
    
    // EXPECTED: All three should return the same order
    // ACTUAL: Only unpaidRahul returns the order
    
    assertEquals(1, unpaidRahul.size, "Failed to find order with exact case match")
    assertEquals(0, unpaidRAHUL.size, "❌ BUG: uppercase case not matched")
    assertEquals(0, unpaidrahul.size, "❌ BUG: lowercase case not matched")
}
```

**Test 1.2: Whitespace Handling**
```kotlin
@Test
suspend fun testWhitespaceVariations() {
    val dao = database.printDao()
    
    val variations = listOf("Rahul", "Rahul ", " Rahul", " Rahul ", "R ahul")
    val orderIds = mutableListOf<Long>()
    
    for (name in variations) {
        val order = Order(
            customerName = name,
            totalAmount = 50.0,
            date = now,
            paidAmount = 0.0
        )
        orderIds.add(dao.insertOrder(order))
    }
    
    // Query with standard name
    val found = dao.getUnpaidOrdersForCustomer("Rahul")
    
    // EXPECTED: 5 orders all linked to same customer
    // ACTUAL: Only 1 order found
    assertEquals(5, found.size, "Whitespace not handled - found ${found.size} instead of 5")
}
```

**Test 1.3: DebtorCredit Collision**
```kotlin
@Test
suspend fun testDebtorCreditPrimaryKeyCollision() {
    val dao = database.printDao()
    
    val debtRecord1 = DebtorCredit("Rahul", 100.0)
    val debtRecord2 = DebtorCredit("rahul", 50.0)
    
    dao.insertOrUpdateDebtorCredit(debtRecord1)
    dao.insertOrUpdateDebtorCredit(debtRecord2)  // This OVERWRITES debtRecord1!
    
    val retrieved = dao.getDebtorCreditByName("Rahul")
    
    // EXPECTED: Separate records or error
    // ACTUAL: debtRecord2 overwrites, only one exists
    assertNotNull(retrieved)
    assertEquals(150.0, retrieved.amount, "Debt should be ₹150, not ₹50")
}
```

### Test Suite 2: Debt Calculation Accuracy

**Test 2.1: Multiple Orders Same Customer**
```kotlin
@Test
suspend fun testMultipleOrdersDebtCalculation() {
    val dao = database.printDao()
    val repository = PrintRepository(dao)
    
    // Create 3 orders for "Rahul" (case variations)
    val orders = listOf(
        Order("Rahul", 100.0, now, 0.0),
        Order("rahul", 50.0, now, 0.0),
        Order("RAHUL", 75.0, now, 0.0)
    )
    
    for (order in orders) {
        dao.insertOrder(order)
    }
    
    // Query balance
    val balance = repository.getCustomerBalance("Rahul")
    
    // EXPECTED: ₹225 (sum of all unpaid)
    // ACTUAL: ₹0.0 (no settlement history for "Rahul")
    assertEquals(225.0, balance, "❌ BUG: Balance not aggregating all customer orders")
}
```

**Test 2.2: Settlement History Split**
```kotlin
@Test
suspend fun testSettlementHistorySplit() {
    val dao = database.printDao()
    val repository = PrintRepository(dao)
    
    // Case 1: Create settlement for "Rahul"
    dao.insertSettlement(SettlementHistory(
        customerName = "Rahul",
        balanceBefore = 100.0,
        amountPaid = 40.0,
        balanceAfter = 60.0,
        timestamp = now
    ))
    
    // Case 2: Create settlement for "rahul"
    dao.insertSettlement(SettlementHistory(
        customerName = "rahul",
        balanceBefore = 50.0,
        amountPaid = 0.0,
        balanceAfter = 50.0,
        timestamp = now + 1000
    ))
    
    val balance = repository.getCustomerBalance("Rahul")
    
    // EXPECTED: ₹60 (latest balance)
    // ACTUAL: ₹60 (only matches "Rahul", misses "rahul")
    // PROBLEM: "rahul" settlement lost!
    assertEquals(110.0, balance, "❌ BUG: Settlement history split across case variants")
}
```

### Test Suite 3: Data Orphaning Detection

**Test 3.1: Order Without Settlement**
```kotlin
@Test
suspend fun testOrderWithoutSettlement() {
    val dao = database.printDao()
    
    // Create order
    val orderId = dao.insertOrder(Order("Rahul", 100.0, now, 0.0))
    
    // Query balance
    val balance = dao.getDebtorCreditByName("Rahul")
    
    // EXPECTED: Balance ₹100 (from order)
    // ACTUAL: null or 0.0 (no settlement record exists)
    assertNotNull(balance, "❌ BUG: New customer order has no balance record")
}
```

**Test 3.2: Orphaned Settlement Records**
```kotlin
@Test
suspend fun testOrphanedSettlementRecords() {
    val dao = database.printDao()
    
    // Create settlement record for non-existent customer
    dao.insertSettlement(SettlementHistory(
        customerName = "NonexistentCustomer",
        balanceBefore = 100.0,
        amountPaid = 50.0,
        balanceAfter = 50.0,
        timestamp = now
    ))
    
    // No orders created for this customer
    // This should be invalid per referential integrity
    val settlements = dao.getAllSettlements()
    
    // EXPECTED: Error or warning
    // ACTUAL: Record silently created
    assertEquals(1, settlements.size, "❌ BUG: Orphaned settlement allowed")
}
```

### Test Suite 4: Balance Mismatch Detection

**Test 4.1: Settlement vs Order Sum**
```kotlin
@Test
suspend fun testSettlementOrderMismatch() {
    val dao = database.printDao()
    
    // Create 3 unpaid orders: ₹100 + ₹50 + ₹75 = ₹225
    dao.insertOrder(Order("Rahul", 100.0, now, 0.0))
    dao.insertOrder(Order("Rahul", 50.0, now, 0.0))
    dao.insertOrder(Order("Rahul", 75.0, now, 0.0))
    
    // Create settlement claiming ₹100 total debt
    dao.insertSettlement(SettlementHistory(
        customerName = "Rahul",
        balanceBefore = 100.0,          // ❌ MISMATCH: Should be ₹225
        amountPaid = 0.0,
        balanceAfter = 100.0,
        timestamp = now
    ))
    
    // No validation prevents this contradiction
    val settlements = dao.getAllSettlements()
    assertEquals(1, settlements.size, "❌ BUG: Inconsistent settlement allowed")
}
```

---

## PART 4: LOGGING STRATEGY

### Critical Logging Points

#### 1. Customer Lookup Logging
Should log to: `CUSTOMER_LOOKUP` tag
```kotlin
// Before: DAO query at sqlite level
Log.d("CUSTOMER_LOOKUP", "SQL: WHERE customerName = '${customerName}' (case-sensitive)")
Log.d("CUSTOMER_LOOKUP", "Input: customerName='${customerName}'")
Log.d("CUSTOMER_LOOKUP", "Normalized: '${customerName.trim().lowercase()}'")

// After: Result
Log.d("CUSTOMER_LOOKUP", "Query returned ${results.size} records")
if (results.isEmpty()) {
    Log.w("CUSTOMER_LOOKUP", "⚠️  NO MATCH: Customer '${customerName}' not found - potential duplicate!")
}
```

#### 2. Customer Creation Logging
Should log to: `CUSTOMER_CREATION` tag
```kotlin
Log.i("CUSTOMER_CREATION", "Creating order for customer: '${customerName}'")
Log.d("CUSTOMER_CREATION", "Input raw: '${customerName}'")
Log.d("CUSTOMER_CREATION", "After trim: '${customerName.trim()}'")
Log.d("CUSTOMER_CREATION", "After lowercase: '${customerName.trim().lowercase()}'")
Log.i("CUSTOMER_CREATION", "Order saved with customerName='${order.customerName}'")
```

#### 3. Debt Calculation Logging
Should log to: `DEBT_CALC` tag
```kotlin
Log.d("DEBT_CALC", "getCustomerBalance called for: '${customerName}'")
Log.d("DEBT_CALC", "Fetching all settlement history (potential N+1)")
Log.d("DEBT_CALC", "Available settlement records: $count")
Log.d("DEBT_CALC", "After filter [trim().equals(ignoreCase)]: $filtered records")
Log.d("DEBT_CALC", "Latest settlement: timestamp=${latest.timestamp}, balance=${latest.balanceAfter}")
Log.d("DEBT_CALC", "Returning balance: ₹${balance}")
```

#### 4. Payment Processing Logging
Should log to: `PAYMENT_PROCESS` tag
```kotlin
Log.i("PAYMENT_PROCESS", "applyPaymentToCustomer: '${customerName}', amount=₹${paymentAmount}")
Log.d("PAYMENT_PROCESS", "Current balance: ₹${currentBalance}")

for (order in unpaidOrders) {
    Log.d("PAYMENT_PROCESS", "  Unpaid Order#${order.id}: ₹${order.totalAmount - order.paidAmount} due")
}

Log.d("PAYMENT_PROCESS", "Allocating payment...")
Log.i("PAYMENT_PROCESS", "Settlement created: balanceBefore=₹${before}, paid=₹${paid}, balanceAfter=₹${after}")
```

#### 5. Settlement History Logging
Should log to: `SETTLEMENT_AUDIT` tag
```kotlin
Log.i("SETTLEMENT_AUDIT", "Settlement record for '${custName}':")
Log.d("SETTLEMENT_AUDIT", "  Before: ₹${before} → After: ₹${after} | Paid: ₹${paid}")
Log.d("SETTLEMENT_AUDIT", "  Type: ${type} | Note: ${note}")
Log.d("SETTLEMENT_AUDIT", "  Timestamp: ${DateFormat.format(timestamp)}")

// CRITICAL: Verify linkage
val correspondingOrders = dao.getUnpaidOrdersForCustomer(custName)
Log.d("SETTLEMENT_AUDIT", "  Linked orders: ${correspondingOrders.size} unpaid order(s) exist")
if (correspondingOrders.isEmpty() && amountPaid > 0) {
    Log.w("SETTLEMENT_AUDIT", "⚠️  WARNING: Payment recorded but NO unpaid orders found!")
}
```

### Recommended Logging Additions to Repository

See: `DEBUG_LOGGING_ADDITIONS.kt` for complete implementation.

---

## PART 5: ROOT CAUSE ANALYSIS

### Primary Root Cause: Architecture Flaw
**Location:** Entire database design (no Customer entity)

**Problem Chain:**
```
1. No Customer entity with immutable ID
   ↓
2. Query logic uses mutable customerName string
   ↓
3. Case-sensitive WHERE clause at SQLite level
   ↓
4. Name variations create separate logical customer records
   ↓
5. Settlement history fragmented across case variants
   ↓
6. getCustomerBalance() only matches via trim().equals(ignoreCase)
   ↓
7. Repository logic contradicts DAO logic
   ↓
8. Same customer appears as multiple distinct entries
```

### Secondary Root Cause 1: Inconsistent Normalization
**Location:** PrintRepository.getCustomerBalance() line 119, getCustomerSummaries() line 127

**Issue:**
```kotlin
// getCustomerBalance: trim() + equals(ignoreCase)
.filter { it.customerName.trim().equals(customerName.trim(), ignoreCase = true) }

// getUnpaidOrdersForCustomer: DAO uses exact case-sensitive match
// WHERE customerName = :customerName

// Result: Same "Rahul" customer gets different results!
```

### Secondary Root Cause 2: DebtorCredit PRIMARY KEY Design
**Location:** DebtorCredit data class, DebtorCredit PK constraint

**Issue:**
```kotlin
@Entity(tableName = "debtor_credits")
data class DebtorCredit(
    @PrimaryKey
    val customerName: String,  // ❌ String is mutable, case-sensitive PK
    val amount: Double,
    val lastUpdated: Long
)
```

**Problem:**
- SQLite treats "Rahul" and "rahul" as different primary keys
- insertOrUpdateDebtorCredit uses REPLACE strategy
- If "rahul" already exists, creating "Rahul" creates duplicate instead of updating

### Secondary Root Cause 3: No Transaction Boundaries
**Location:** PrintRepository functions, no @Transaction annotation

**Issue:**
```kotlin
suspend fun applyPaymentToCustomer(...) {
    val currentBalance = getCustomerBalance(customerName)  // Query 1
    val unpaidOrders = printDao.getUnpaidOrdersForCustomer(customerName)  // Query 2
    
    for (order in unpaidOrders) {
        printDao.updatePayment(order.id, ...)  // Update 3, 4, 5...
    }
    
    printDao.insertSettlement(...)  // Insert final record
    printDao.insertOrUpdateDebtorCredit(...)  // Insert final record 2
    // ❌ NO TRANSACTION: If app crashes between updates, data state corrupted
}
```

---

## PART 6: RECOMMENDED ARCHITECTURE

### New Database Schema (MIGRATION REQUIRED)

```sql
-- 1. CREATE NEW CUSTOMER MASTER TABLE
CREATE TABLE customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    normalizedName TEXT NOT NULL UNIQUE,  -- lowercase for matching
    phoneNumber TEXT,                      -- optional secondary key
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    CONSTRAINT unique_customer UNIQUE (normalizedName)
);

-- 2. MODIFY: orders TABLE (ADD customerId FK)
ALTER TABLE orders ADD COLUMN customerId INTEGER REFERENCES customers(id) ON DELETE RESTRICT;

-- 3. MODIFY: debtor_credits TABLE
ALTER TABLE debtor_credits ADD COLUMN customerId INTEGER UNIQUE REFERENCES customers(id) ON DELETE CASCADE;

-- 4. MODIFY: settlement_history TABLE  
ALTER TABLE settlement_history ADD COLUMN customerId INTEGER REFERENCES customers(id) ON DELETE RESTRICT;

-- 5. CREATE INDEXES
CREATE INDEX idx_customers_normalized ON customers(normalizedName);
CREATE INDEX idx_customers_phone ON customers(phoneNumber);
CREATE INDEX idx_orders_customer ON orders(customerId);
CREATE INDEX idx_settlement_customer ON settlement_history(customerId);
CREATE INDEX idx_debtor_customer ON debtor_credits(customerId);
```

### Refactored Code Architecture

#### New Customer Entity
```kotlin
@Entity(
    tableName = "customers",
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index(value = ["phoneNumber"], unique = true)
    ]
)
data class Customer(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,                    // Display name
    val normalizedName: String,          // lowercase for matching
    val phoneNumber: String? = null,     // Optional secondary key
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

#### New DAO Methods
```kotlin
@Dao
interface PrintDao {
    
    // CUSTOMER MANAGEMENT
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateCustomer(customer: Customer): Long
    
    @Query("SELECT * FROM customers WHERE normalizedName = :normalizedName")
    suspend fun getCustomerByNormalizedName(normalizedName: String): Customer?
    
    @Query("SELECT * FROM customers WHERE phoneNumber = :phone")
    suspend fun getCustomerByPhone(phone: String): Customer?
    
    @Query("""
        SELECT * FROM customers 
        WHERE normalizedName LIKE :query OR phoneNumber LIKE :query
    """)
    suspend fun searchCustomers(query: String): List<Customer>
    
    // CUSTOMER LOOKUPS WITH TRANSACTION SAFETY
    @Transaction
    @Query("SELECT * FROM customers WHERE id = :customerId")
    suspend fun getCustomerWithOrders(customerId: Int): CustomerWithOrders?
}
```

#### Repository Refactor (Key Changes)
```kotlin
class PrintRepository(private val printDao: PrintDao) {
    
    // NEW: Get or create customer
    suspend fun getOrCreateCustomer(name: String): Customer {
        val normalizedName = name.trim().lowercase()
        
        Log.d("CUSTOMER_LOOKUP", "Looking up customer: '$name' (normalized: '$normalizedName')")
        
        // Try to find existing
        var customer = printDao.getCustomerByNormalizedName(normalizedName)
        
        if (customer != null) {
            Log.d("CUSTOMER_LOOKUP", "Found existing customer ID: ${customer.id}")
            return customer
        }
        
        // Create new
        Log.i("CUSTOMER_CREATION", "Creating new customer: '$name'")
        customer = Customer(
            name = name,
            normalizedName = normalizedName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        
        val customerId = printDao.insertOrUpdateCustomer(customer).toInt()
        Log.i("CUSTOMER_CREATION", "Customer created with ID: $customerId")
        
        return customer.copy(id = customerId)
    }
    
    // REFACTORED: confirmOrder with customer FK
    @Transaction
    suspend fun confirmOrder(customerName: String, cartItems: List<CartItem>, paymentMethod: String = "CASH"): Int {
        Log.i("ORDER_CREATION", "confirmOrder started for: '$customerName'")
        
        val customer = getOrCreateCustomer(customerName)
        Log.d("ORDER_CREATION", "Using customerId: ${customer.id}")
        
        val total = cartItems.sumOf { it.price * it.quantity }
        val order = Order(
            totalAmount = total,
            date = System.currentTimeMillis(),
            customerId = customer.id,  // NEW: Use ID instead of name
            customerName = customerName,  // Keep for display
            paidAmount = total,
            paymentMethod = paymentMethod
        )
        
        val orderId = printDao.insertOrder(order).toInt()
        Log.d("ORDER_CREATION", "Order#$orderId created")
        
        // Rest of logic...
        return orderId
    }
    
    // REFACTORED: getCustomerBalance with FK
    suspend fun getCustomerBalance(customerId: Int): Double {
        Log.d("DEBT_CALC", "getCustomerBalance for customerId: $customerId")
        
        val settlement = printDao.getLatestSettlementForCustomer(customerId)
        val balance = settlement?.balanceAfter ?: 0.0
        
        Log.d("DEBT_CALC", "Returning balance: ₹$balance")
        return balance
    }
}
```

---

## PART 7: MIGRATION PLAN

### Pre-Migration Validation

```sql
-- Backup ALL data before migration
PRAGMA foreign_keys=OFF;  -- Disable constraints during migration

-- Check for duplicates
SELECT COUNT(*) as duplicate_count 
FROM (
    SELECT LOWER(TRIM(customerName)) as norm_name, COUNT(*) as cnt
    FROM orders
    GROUP BY norm_name
    HAVING cnt > 1
);
```

### Migration Strategy (Version 10 → 11)

**Step 1: Create new Customer table**
```sql
CREATE TABLE customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    normalizedName TEXT NOT NULL UNIQUE,
    phoneNumber TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
```

**Step 2: Populate from existing data**
```sql
-- Insert unique customers from orders table
INSERT INTO customers (name, normalizedName, createdAt, updatedAt)
SELECT DISTINCT 
    -- For duplicates, use the version with most characters (likely most correct)
    MAX(customerName) as name,  
    LOWER(TRIM(customerName)) as normalizedName,
    MIN(date) as createdAt,
    MAX(date) as updatedAt
FROM orders
GROUP BY LOWER(TRIM(customerName));

-- De-duplicate settlement_history similarly
INSERT OR IGNORE INTO customers (name, normalizedName, createdAt, updatedAt)
SELECT DISTINCT
    MAX(customerName),
    LOWER(TRIM(customerName)),
    MIN(timestamp),
    MAX(timestamp)
FROM settlement_history
GROUP BY LOWER(TRIM(customerName));
```

**Step 3: Add customerId columns to existing tables**
```sql
ALTER TABLE orders ADD COLUMN customerId INTEGER;
ALTER TABLE settlement_history ADD COLUMN customerId INTEGER;
ALTER TABLE debtor_credits ADD COLUMN customerId INTEGER;
```

**Step 4: Populate foreign keys**
```sql
UPDATE orders 
SET customerId = (
    SELECT id FROM customers 
    WHERE normalizedName = LOWER(TRIM(orders.customerName))
)
WHERE customerId IS NULL;

UPDATE settlement_history
SET customerId = (
    SELECT id FROM customers 
    WHERE normalizedName = LOWER(TRIM(settlement_history.customerName))
)
WHERE customerId IS NULL;

UPDATE debtor_credits
SET customerId = (
    SELECT id FROM customers 
    WHERE normalizedName = LOWER(TRIM(debtor_credits.customerName))
)
WHERE customerId IS NULL;
```

**Step 5: Verify integrity before cleanup**
```sql
-- Check for NULL customerId (indicates migration issue)
SELECT COUNT(*) FROM orders WHERE customerId IS NULL;
SELECT COUNT(*) FROM settlement_history WHERE customerId IS NULL;
SELECT COUNT(*) FROM debtor_credits WHERE customerId IS NULL;

-- All should return 0
```

**Step 6: Add foreign key constraints**
```sql
-- SQLite doesn't support ALTER ADD CONSTRAINT directly
-- Must recreate table with constraints (handled in Room migration code)
```

### Fallback & Rollback Strategy

1. **Backup Location:** Database encrypted backup at `/data/data/com.tadiwaprintbuddy.app/databases/backup_v10.db`
2. **Rollback Method:** If migration fails, restore backup and revert app version
3. **Zero-Downtime:** All reads disabled during migration window

### Testing Migration

```sql
-- After migration, verify:

-- 1. All customers have unique normalized names
SELECT normalizedName, COUNT(*) as cnt FROM customers GROUP BY normalizedName HAVING cnt > 1;
-- Should return 0 rows

-- 2. No orphaned orders
SELECT COUNT(*) FROM orders WHERE customerId NOT IN (SELECT id FROM customers);
-- Should return 0

-- 3. All settlement histories linked
SELECT COUNT(*) FROM settlement_history WHERE customerId NOT IN (SELECT id FROM customers);
-- Should return 0

-- 4. Balance accuracy (sample check)
SELECT c.name, 
       COUNT(o.id) as order_count,
       SUM(o.totalAmount - o.paidAmount) as order_debt,
       (SELECT balanceAfter FROM settlement_history sh WHERE sh.customerId = c.id ORDER BY timestamp DESC LIMIT 1) as settlement_balance
FROM customers c
LEFT JOIN orders o ON c.id = o.customerId
GROUP BY c.id;
```

---

## PART 8: REGRESSION TEST CHECKLIST

### Unit Tests Required

```kotlin
// CustomerLookupTest.kt
- testExactDuplicateCustomerReused()
- testCaseSensitivityHandled()
- testWhitespaceNormalized()
- testPhoneNumberSecondaryKey()
- testCustomerEditPreservesHistory()
- testConcurrentCustomerCreation()

// DebtCalculationTest.kt
- testMultiOrderDebtCalculation()
- testSettlementAccuracy()
- testPaymentAllocation()
- testBalanceMismatchDetection()
- testZeroBalanceHandling()

// DatabaseIntegrityTest.kt
- testNoOrphanedOrders()
- testNoOrphanedSettlements()
- testForeignKeyConstraints()
- testUniqueConstraintEnforcement()
- testTransactionAtomicity()

// MigrationTest.kt
- testDuplicateCustomerMerging()
- testDebtPreservation()
- testSettlementHistoryPreservation()
- testZeroDataLoss()
```

### Integration Tests Required

```kotlin
// End-to-end workflows
- testCompleteOrderFlow_NewCustomer()
- testCompleteOrderFlow_ExistingCustomer()
- testCompletePaymentFlow_MultipleOrders()
- testCompleteSettlementFlow_MultipleCustomers()
- testConcurrentOrderCreation()
- testRapidPaymentProcessing()
```

### Manual QA Scenarios

```
1. Create customer "Rahul": ₹100 → Verify single record
2. Create "rahul": ₹50 → Verify merged, total ₹150
3. Create "Rahul ": ₹75 → Verify merged, total ₹225
4. Payment: ₹100 → Verify ₹125 remaining
5. Settlement: ₹125 → Verify ₹0 balance
6. New order: ₹60 → Verify ₹60 new debt
7. Export report → Verify single "Rahul" entry with correct history
8. Force-sync DB → Verify no data corruption
```

---

## CRITICAL ACTION ITEMS

### ASAP (Week 1)
- [ ] Audit production database for duplicate customers (run SQL scan script)
- [ ] Generate data integrity report (orphaned records, balance mismatches)
- [ ] Create backup of production database
- [ ] Document all current customer variants (case/whitespace issues)

### Week 2-3
- [ ] Create Customer entity and DAO methods
- [ ] Implement migration database v10 → v11
- [ ] Add comprehensive logging (DEBUG_LOGGING_ADDITIONS.kt)
- [ ] Create unit test suite

### Week 4
- [ ] Deploy to staging environment
- [ ] Execute full regression testing
- [ ] Monitor logs for new issues (if any)
- [ ] Deploy to production (phased rollout)

### Post-Deployment
- [ ] Monitor error logs for migration issues
- [ ] Run quarterly database integrity audits
- [ ] Implement automated duplicate detection alerts

---

## APPENDICES

- **See:** DATABASE_INTEGRITY_TESTS.kt - Full test suite code
- **See:** DEBUG_LOGGING_ADDITIONS.kt - Logging implementation
- **See:** MIGRATION_SQL.sql - Complete migration script
- **See:** CUSTOMER_DEDUPLICATION_SCRIPT.sql - Data cleanup queries
-  **See:** MONITORING_QUERIES.sql - Production monitoring queries

---

**Investigation Completed:** May 14, 2026  
**Severity Level:** CRITICAL - Data Integrity Risk  
**Recommended Timeline:** Immediate fix required
