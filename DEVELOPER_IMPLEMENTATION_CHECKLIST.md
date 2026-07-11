# DEVELOPER IMPLEMENTATION CHECKLIST
## Customer Identity Refactor for Tadiwa Print Buddy

**Objective:** Migrate from customerName-based to customerId-based customer identification  
**Timeline:** 2-3 weeks  
**Complexity:** Medium (database migration + code refactor)  

---

## PHASE 1: PREPARATION (Days 1-2)

### Day 1: Analysis & Setup
- [ ] Read QA_INVESTIGATION.md completely
- [ ] Run CUSTOMER_DEDUPLICATION_SCRIPT.sql against production database
- [ ] Document findings (how many duplicates exist, impact)
- [ ] Create feature branch: `feature/customer-identity-refactor`
- [ ] Set up staging database clone
- [ ] Set up separate testing database

### Day 2: Environment Setup
- [ ] Create test fixtures for unit tests
- [ ] Prepare test data with KT files for different scenarios
- [ ] Set up monitoring dashboard

---

## PHASE 2: CODE IMPLEMENTATION (Days 3-8)

### PART A: Create Customer Entity

**File:** `app/src/main/java/com/tadiwaprintbuddy/app/data/Customer.kt`

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
    
    val name: String,           // Display name
    val normalizedName: String,  // lowercase for matching
    val phoneNumber: String? = null,
    
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

**Checklist:**
- [ ] Entity created with UNIQUE constraints
- [ ] Indexes added for normalizedName and phoneNumber
- [ ] Compiles without errors

---

### PART B: Update Order Entity

**File:** `app/src/main/java/com/tadiwaprintbuddy/app/data/Order.kt`

```kotlin
@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    val totalAmount: Double,
    val date: Long,
    
    val customerId: Int,        // NEW: FK to customers
    val customerName: String,   // KEEP: For display during transition
    
    val paidAmount: Double = 0.0,
    val paymentMethod: String = "CASH"
)
```

**Checklist:**
- [ ] customerId column added
- [ ] customerName column kept (for backward compatibility)
- [ ] Foreign key constraint defined (if Room supports, use @ForeignKey)
- [ ] Compiles without errors

---

### PART C: Update SettlementHistory Entity

**File:** `app/src/main/java/com/tadiwaprintbuddy/app/data/SettlementHistory.kt`

```kotlin
@Entity(tableName = "settlement_history")
data class SettlementHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    val customerId: Int,        // NEW: FK to customers
    val customerName: String,   // KEEP: For display
    
    @ColumnInfo(name = "previousBalance") val balanceBefore: Double,
    @ColumnInfo(name = "settledAmount") val amountPaid: Double,
    @ColumnInfo(name = "remainingBalance") val balanceAfter: Double,
    
    val timestamp: Long,
    val type: String = "PAYMENT",
    val note: String = ""
)
```

**Checklist:**
- [ ] customerId column added
- [ ] Foreign key relationship defined
- [ ] Column indexes optimized

---

### PART D: Update DebtorCredit Entity

**File:** `app/src/main/java/com/tadiwaprintbuddy/app/data/DebtorCredit.kt`

```kotlin
@Entity(tableName = "debtor_credits")
data class DebtorCredit(
    @PrimaryKey
    val customerId: Int,        // NEW: Changed from customerName
    
    val customerName: String,   // KEEP: For display
    val amount: Double,
    val lastUpdated: Long = System.currentTimeMillis()
)
```

**Checklist:**
- [ ] Primary key changed from customerName to customerId
- [ ] Foreign key constraint added
- [ ] Compiles without errors

---

### PART E: Update DAO Queries

**File:** `app/src/main/java/com/tadiwaprintbuddy/app/data/PrintDao.kt`

Add new customer queries:

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
    
    @Query("SELECT * FROM customers WHERE id = :customerId")
    suspend fun getCustomerById(customerId: Int): Customer?
    
    @Query("SELECT * FROM customers ORDER BY name ASC")
    suspend fun getAllCustomers(): List<Customer>
    
    @Query("""
        SELECT * FROM customers 
        WHERE normalizedName LIKE '%' || LOWER(TRIM(:query)) || '%'
        OR phoneNumber LIKE :phone
    """)
    suspend fun searchCustomers(query: String, phone: String): List<Customer>
    
    // UPDATE EXISTING QUERIES TO USE customerId
    @Query("SELECT * FROM orders WHERE customerId = :customerId AND totalAmount > paidAmount ORDER BY date ASC")
    suspend fun getUnpaidOrdersForCustomer(customerId: Int): List<Order>
    
    @Query("""
        SELECT customerName, SUM(totalAmount - paidAmount) as totalBalance, 'OWES' as type
        FROM orders
        WHERE totalAmount > paidAmount
        GROUP BY customerId
    """)
    suspend fun getDebtors(): List<DebtorSummary>
    
    // DATABASE INTEGRITY CHECKS (for monitoring)
    @Query("SELECT COUNT(*) FROM orders WHERE customerId IS NULL")
    suspend fun countOrphanedOrders(): Int
    
    @Query("SELECT COUNT(*) FROM settlement_history WHERE customerId IS NULL")
    suspend fun countOrphanedSettlements(): Int
    
    @Query("""
        SELECT COUNT(DISTINCT customerName) 
        FROM orders 
        GROUP BY customerId 
        HAVING COUNT(DISTINCT customerName) > 1
    """)
    suspend fun countCustomerNamingInconsistencies(): Int
}
```

**Checklist:**
- [ ] All new queries added
- [ ] Updated existing queries to use customerId instead of customerName
- [ ] WHERE clauses use customerId (immutable FK) not customerName (mutable string)
- [ ] Queries compile and are type-safe

---

### PART F: Update Repository Layer

**File:** `app/src/main/java/com/tadiwaprintbuddy/app/data/PrintRepository.kt`

Key method refactors:

```kotlin
class PrintRepository(private val printDao: PrintDao) {
    
    /**
     * NEW: Get or create customer with normalization
     */
    suspend fun getOrCreateCustomer(name: String): Customer {
        val normalizedName = name.trim().lowercase()
        
        Log.d("CUSTOMER_LOOKUP", "Looking up: '$name' (normalized: '$normalizedName')")
        
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
    
    /**
     * REFACTORED: Use customerId instead of customerName
     */
    suspend fun confirmOrder(customerName: String, cartItems: List<CartItem>, paymentMethod: String = "CASH"): Int {
        if (cartItems.isEmpty()) return -1
        
        val customer = getOrCreateCustomer(customerName)
        
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
        
        val orderItems = cartItems.map { cartItem ->
            OrderItem(
                orderId = orderId,
                serviceName = cartItem.serviceName,
                price = cartItem.price,
                quantity = cartItem.quantity
            )
        }
        printDao.insertOrderItems(orderItems)
        
        if (paymentMethod == "UPI") {
            insertBeautyTransaction(total, "ADD", "Direct Pay - $customerName")
        }
        
        return orderId
    }
    
    /**
     * REFACTORED: Get customer balance by customerId
     */
    suspend fun getCustomerBalance(customerId: Int): Double {
        Log.d("DEBT_CALC", "getCustomerBalance for customerId: $customerId")
        
        return printDao.getLatestSettlementForCustomer(customerId)?.balanceAfter ?: 0.0
    }
    
    /**
     * NEW DAO METHOD NEEDED:
     * @Query("""
     *     SELECT * FROM settlement_history 
     *     WHERE customerId = :customerId 
     *     ORDER BY timestamp DESC LIMIT 1
     * """)
     * suspend fun getLatestSettlementForCustomer(customerId: Int): SettlementHistory?
     */
    
    /**
     * REFACTORED: Payment processing with customerId
     */
    @Transaction
    suspend fun applyPaymentToCustomer(customerId: Int, paymentAmount: Double, paymentMethod: String = "CASH") {
        val currentBalance = getCustomerBalance(customerId)
        val customer = printDao.getCustomerById(customerId) ?: return
        
        val unpaidOrders = printDao.getUnpaidOrdersForCustomer(customerId)
        var remainingPayment = paymentAmount
        
        for (order in unpaidOrders) {
            if (remainingPayment <= 0) break
            
            val amountOwed = order.totalAmount - order.paidAmount
            val paymentForThisOrder = if (remainingPayment >= amountOwed) amountOwed else remainingPayment
            
            val newPaidAmount = order.paidAmount + paymentForThisOrder
            printDao.updatePayment(order.id, newPaidAmount)
            
            remainingPayment -= paymentForThisOrder
        }
        
        val newBalance = currentBalance - paymentAmount
        
        printDao.insertSettlement(
            SettlementHistory(
                customerId = customerId,
                customerName = customer.name,  // Display only
                balanceBefore = currentBalance,
                amountPaid = paymentAmount,
                balanceAfter = newBalance,
                timestamp = System.currentTimeMillis(),
                type = "PAYMENT",
                note = "Payment via $paymentMethod"
            )
        )
        
        // Update debtor credit
        printDao.insertOrUpdateDebtorCredit(
            DebtorCredit(
                customerId = customerId,
                customerName = customer.name,
                amount = newBalance,
                lastUpdated = System.currentTimeMillis()
            )
        )
        
        if (paymentMethod == "UPI") {
            insertBeautyTransaction(paymentAmount, "ADD", "Debt Settlement - ${customer.name}")
        }
    }
}
```

**Checklist:**
- [ ] getOrCreateCustomer() implemented
- [ ] confirmOrder() updated to use customerId
- [ ] applyPaymentToCustomer() refactored
- [ ] getCustomerBalance() takes customerId
- [ ] All calls to DAO updated to use customerId
- [ ] Transaction support added where needed (@Transaction annotations)
- [ ] Logging added to DEBUG_LOGGING_ADDITIONS.kt

---

## PHASE 3: TESTING (Days 9-12)

### Unit Tests

**File:** `app/src/test/java/com/tadiwaprintbuddy/app/data/DatabaseIntegrityTest.kt`

```kotlin
// Copy the entire DatabaseIntegrityTest.kt provided
```

**Checklist:**
- [ ] All 20+ test cases copied
- [ ] Tests run and PASS with new code
- [ ] Each test documents the bug it's catching
- [ ] Add 5-10 NEW tests for customer ID scenarios:
  - [ ] testGetOrCreateCustomerIdempotent() - same name returns same ID
  - [ ] testCustomerCaseNormalization() - "Rahul", "RAHUL", "rahul" return same customer
  - [ ] testCustomerWhitespaceNormalization() - " Rahul " returns same as "Rahul"
  - [ ] testMultipleOrdersSameCustomerId() - all orders linked via ID
  - [ ] testPaymentAllocationByCustomerId() - payment applies correctly
  - [ ] testNoOrphanedOrdersAfterMigration() - all orders have customerId
  - [ ] testBalanceCalculationAccuracy() - correct totals

---

### Integration Tests

**File:** `app/src/androidTest/java/com/tadiwaprintbuddy/app/data/CustomerIntegrationTest.kt`

```kotlin
@RunWith(AndroidJUnit4::class)
class CustomerIntegrationTest {
    
    @Test
    fun testCompleteOrderFlowWithNewCustomer() = runTest {
        // 1. Create customer (should be added automatically)
        val orderId = repository.confirmOrder("John Doe", testCartItems)
        
        // 2. Verify customer was created
        val customer = repository.getOrCreateCustomer("John Doe")
        assertEquals("john doe", customer.normalizedName)
        
        // 3. Create duplicate with different case
        val orderId2 = repository.confirmOrder("JOHN DOE", testCartItems)
        
        // 4. Verify same customer was reused
        assertEquals(customer.id, 
            repository.getOrCreateCustomer("john doe").id)
        
        // 5. Verify all orders linked
        val unpaidOrders = dao.getUnpaidOrdersForCustomer(customer.id)
        assertEquals(2, unpaidOrders.size)
    }
    
    @Test
    fun testPaymentWorkflowMultipleOrders() = runTest {
        val customerId = repository.getOrCreateCustomer("Rahul").id
        
        // Create 3 orders
        val o1 = createOrderFor(customerId, 100.0)
        val o2 = createOrderFor(customerId, 50.0)
        val o3 = createOrderFor(customerId, 75.0)
        
        // Payment: ₹120
        repository.applyPaymentToCustomer(customerId, 120.0)
        
        // Verify allocation
        assertEquals(100.0, dao.getOrderById(o1).paidAmount)
        assertEquals(50.0, dao.getOrderById(o2).paidAmount)
        assertEquals(20.0, dao.getOrderById(o3).paidAmount)
    }
}
```

**Checklist:**
- [ ] Integration tests created
- [ ] All tests pass on real Room database (not in-memory)
- [ ] Tests cover end-to-end workflows
- [ ] Tests run against staging environment

---

## PHASE 4: MIGRATION (Days 13-16)

### Pre-Migration

**Day 13: Database Validation**

```bash
# 1. On production-like environment, run:
sqlite3 print_database.db < CUSTOMER_DEDUPLICATION_SCRIPT.sql

# 2. Review output:
#    - How many duplicate customer sets exist?
#    - How many orphaned records?
#    - Total impact assessment

# 3. Document findings in ticket for team review
```

**Checklist:**
- [ ] Duplicate customer count documented
- [ ] Orphaned record count documented
- [ ] Balance mismatches identified
- [ ] Team approved to proceed

---

### Execute Migration

**Day 14: Staging Migration**

```bash
# 1. Backup staging database
cp print_database.db print_database.db.backup

# 2. Execute migration
sqlite3 print_database.db < MIGRATION_SQL_v10_to_v11.sql

# 3. Validate all migration checks pass
sqlite3 print_database.db < MIGRATION_SQL_v10_to_v11.sql | grep ERROR
# Should return no results

# 4. Run monitoring queries
sqlite3 print_database.db < MONITORING_QUERIES.sql
```

**Checklist:**
- [ ] Migration executed successfully
- [ ] No errors in migration log
- [ ] All validation queries pass
- [ ] Customer count reduced significantly
- [ ] All orders have customerId
- [ ] All settlements have customerId
- [ ] No orphaned records remain

---

### Verify & Test

**Day 15: Full QA Cycle**

- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Manual QA scenarios tested (see below)
- [ ] Performance tested (queries < 1 second)
- [ ] Monitoring alerts configured

**Manual QA Scenarios:**

1. **Create customer scenario:**
   - [ ] Create order for "Rahul"
   - [ ] Verify customer created
   - [ ] Verify order linked via customerId
   - [ ] Query same customer by ID ✓

2. **Duplicate customer scenario:**
   - [ ] Create order for "rahul"
   - [ ] Verify SAME customer reused (not duplicate)
   - [ ] Verify 2 orders exist for same customer
   - [ ] Total debt shows ₹(O1+O2) ✓

3. **Payment scenario:**
   - [ ] Make payment to customer
   - [ ] Verify balance updated
   - [ ] Verify settlement record created
   - [ ] Verify balance matches debtor_credits ✓

4. **Reporting scenario:**
   - [ ] List all debtors
   - [ ] Verify each customer appears ONCE
   - [ ] Verify total debt is correct
   - [ ] No duplicate names ✓

---

## PHASE 5: PRODUCTION DEPLOYMENT (Days 17-21)

### Week 2: Production Rollout

**Day 17: Pre-Deployment Checklist**

- [ ] All tests passing
- [ ] All code reviewed
- [ ] Migration script reviewed & approved
- [ ] Rollback plan documented
- [ ] Monitoring dashboard ready
- [ ] On-call engineer assigned

**Day 18: Deployment**

```
1. Schedule maintenance window (off-peak):  
   - 2-3 AM on weekday (minimum 1 hour)
   
2. Pre-deployment:
   - [ ] Backup production database
   - [ ] Notify users of brief downtime
   - [ ] Prep rollback procedures
   
3. Migration:
   - [ ] Execute MIGRATION_SQL_v10_to_v11.sql
   - [ ] Validate all checks pass
   - [ ] Deploy updated app code
   - [ ] Monitor error logs
   
4. Post-deployment:
   - [ ] Verify app connects to database
   - [ ] Test 5 manual scenarios above
   - [ ] Run MONITORING_QUERIES.sql
   - [ ] Notify users service is restored
```

**Checklist:**
- [ ] Migration completed successfully
- [ ] App deployed successfully
- [ ] No errors in first 1 hour
- [ ] Monitoring queries show healthy state

---

### Days 19-21: Monitoring Period

- [ ] Monitor logcat daily for errors
- [ ] Run MONITORING_QUERIES.sql daily
- [ ] Weekly data integrity checks
- [ ] Investigate any alerts immediately
- [ ] Collect customer feedback

**Checklist:**
- [ ] No critical errors
- [ ] Database integrity maintained
- [ ] Performance acceptable
- [ ] After 1 week, mark migration as SUCCESS

---

## CLEANUP (After 1-2 Releases)

**Remove Legacy Code (separate PR):**

```kotlin
// PHASE 6: Cleanup (Sprint N+2)
// Only after 2 releases stable and no issues

// Remove from Order:
// - customerName column (keep for 1 release for logs)

// Remove from SettlementHistory:
// - customerName column

// Remove from DebtorCredit:
// - customerName column

// Update repository:
// - Remove all fallback customerName matching logic
// - Remove legacy comments

// Database migration v11 → v12:
// - ALTER TABLE orders DROP COLUMN customerName;
// - ALT ER TABLE settlement_history DROP COLUMN customerName;
// - ALTER TABLE debtor_credits DROP COLUMN customerName;
```

**Checklist:**
- [ ] Scheduled for N+2 sprint
- [ ] All components tested without legacy columns
- [ ] Migration script prepared
- [ ] Rollback plan ready
- [ ] All references to customerName removed from code

---

## TROUBLESHOOTING GUIDE

### If Migration Fails

```
Problem: "UNIQUE constraint failed: customers.normalizedName"
Cause: Duplicate customer entries already in tables
Solution: 
  1. Run CUSTOMER_DEDUPLICATION_SCRIPT.sql first
  2. Manually consolidate duplicates
  3. Re-run migration

Problem: "NO settlement records for customerId"
Cause: Settlement migration didn't complete
Solution:
  1. Check migration_SQL log for errors
  2. Verify all settlement_history records have customerId
  3. Apply manual fix for NULL customerId values

Problem: "App crashes on startup"
Cause: Database schema mismatch with app code
Solution:
  1. Verify Room @Database version incremented
  2. Verify all entities updated
  3. Verify all DAO queries compatible
  4. Run tests again
```

---

## SUCCESS CRITERIA

- ✅ All unit tests pass
- ✅ All integration tests pass
- ✅ All manual QA scenarios work
- ✅ No duplicate customers exist
- ✅ All orders linked via customerId
- ✅ Balance calculations accurate
- ✅ Performance acceptable
- ✅ Monitoring active
- ✅ Zero errors in first week production
- ✅ Zero customer complaints about duplicates

---

## DOCUMENTATION

- [x] QA_INVESTIGATION.md - Technical deep dive
- [x] DATABASE_INTEGRITY_TEST.kt - Test suite
- [x] DEBUG_LOGGING_ADDITIONS.kt - Enhanced logging
- [x] MIGRATION_SQL_v10_to_v11.sql - Migration script
- [x] CUSTOMER_DEDUPLICATION_SCRIPT.sql - Pre-migration audit
- [x] MONITORING_QUERIES.sql - Production monitoring
- [x] THIS FILE - Implementation checklist

---

**Status:** Ready for Implementation  
**Start Date:** [YOUR DATE]  
**Estimated Completion:** 21 days  
**Owner:** [YOUR NAME]  
**Reviewers:** [TEAM MEMBERS]  

---

Use this checklist to track progress. Check off each item as complete. Stop if any item fails and document issue.
