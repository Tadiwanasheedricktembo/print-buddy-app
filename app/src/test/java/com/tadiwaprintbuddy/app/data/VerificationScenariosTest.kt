package com.tadiwaprintbuddy.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.TestApplication
import com.tadiwaprintbuddy.app.CartItem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class VerificationScenariosTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var integrityDao: IntegrityCheckDao
    private lateinit var repository: PrintRepository
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.printDao()
        integrityDao = database.integrityCheckDao()
        repository = PrintRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun scenario1_CustomerIdentityNormalization() = runTest {
        // 1. Create Customer A
        repository.confirmOrder("Customer A", listOf(CartItem("Paper", 10.0, 1)), "OWES_ME")
        
        // 2. Create name variation
        repository.confirmOrder(" customer a ", listOf(CartItem("Ink", 20.0, 1)), "OWES_ME")
        
        // Verify canonical record
        val customers = dao.getAllCustomers()
        assertEquals(1, customers.size, "Should have only one customer record")
        assertEquals("Customer A", customers[0].displayName)
        
        // Verify orders point to same ID
        val customerId = customers[0].id
        val orders = dao.getAllOrders()
        assertEquals(2, orders.size)
        assertTrue(orders.all { it.customerId == customerId }, "All orders should point to same customer ID")
        
        // Verify debt
        assertEquals(30.0, repository.getCustomerBalanceById(customerId))
    }

    @Test
    fun scenario2_OrderCreationFlow() = runTest {
        // Setup stock
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))
        
        // Create order
        val cartItems = listOf(CartItem("Paper", 50.0, 10))
        repository.confirmOrder("John", cartItems, "CASH")
        
        // Verify stock decrease
        val stock = dao.getStockItemByName("Paper")
        assertEquals(90, stock?.currentQuantity, "Stock should decrease by 10")
        
        // Verify ledger entries
        val settlements = dao.getAllSettlements()
        // Now creates 2 entries for CASH order: ORDER_POST (+500) and PAYMENT (-500)
        assertEquals(2, settlements.size)
        assertTrue(settlements.any { it.ledgerEntryType == "ORDER_POST" && it.transactionAmount == 500.0 })
        assertTrue(settlements.any { it.ledgerEntryType == "PAYMENT" && it.transactionAmount == -500.0 })
        
        // Use repository to verify authoritative balance
        assertEquals(0.0, repository.getCustomerBalance("John"), "CASH order should result in 0 balance")
    }

    @Test
    fun scenario3_PaymentAllocation() = runTest {
        repository.confirmOrder("Rahul", listOf(CartItem("A", 100.0, 1)), "OWES_ME")
        repository.confirmOrder("Rahul", listOf(CartItem("B", 50.0, 1)), "OWES_ME")
        
        val customerId = dao.getCustomerByNormalizedName("rahul")!!.id
        
        // Partial payment
        repository.applyPaymentToCustomerId(customerId, 120.0)
        
        // Verify debt decrease
        assertEquals(30.0, repository.getCustomerBalanceById(customerId))
        
        // Verify allocation (oldest first)
        val orders = dao.getAllOrders().sortedBy { it.date }
        assertEquals(100.0, orders[0].paidAmount, "First order should be fully paid")
        assertEquals(20.0, orders[1].paidAmount, "Second order should have 20 paid")
        
        // Payment larger than debt (if supported, should go to negative balance/credit)
        repository.applyPaymentToCustomerId(customerId, 50.0)
        assertEquals(-20.0, repository.getCustomerBalanceById(customerId), "Should handle overpayment as credit")
    }

    @Test
    fun scenario4_DeleteAndIntegrity() = runTest {
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))
        repository.confirmOrder("Maya", listOf(CartItem("Paper", 100.0, 1)), "OWES_ME")
        
        val order = dao.getAllOrders().first()
        repository.deleteOrder(order.id)
        
        // Verify stock restored
        val stock = dao.getStockItemByName("Paper")
        assertEquals(100, stock?.currentQuantity, "Stock should be restored after deletion")
        
        // Verify balance
        assertEquals(0.0, repository.getCustomerBalance("Maya"))
        
        // Run integrity scan
        assertEquals(0, integrityDao.getDuplicateCustomerCount())
        assertEquals(0, integrityDao.getOrphanedSettlementCount())
        assertEquals(0, integrityDao.getBalanceMismatches().size)
    }
}
