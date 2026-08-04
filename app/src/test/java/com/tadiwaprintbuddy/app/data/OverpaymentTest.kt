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
class OverpaymentTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var integrityDao: IntegrityCheckDao
    private lateinit var repository: PrintRepository

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
    fun scenario1_OverpaymentCreatesCredit() = runTest {
        // Isaac owes 21
        repository.confirmOrder("Isaac", listOf(CartItem("A", 21.0, 1)), "OWES_ME")
        assertEquals(21.0, repository.getCustomerBalance("Isaac"))

        // Isaac pays 100
        repository.applyPaymentToCustomer("Isaac", 100.0, "CASH")

        // Expected: Balance -79
        val balance = repository.getCustomerBalance("Isaac")
        assertEquals(-79.0, balance, "Balance should be -79 (credit)")

        // Check ledger entries
        val settlements = dao.getAllSettlements().filter { it.customerName == "Isaac" }
        // 1 for order, 1 for debt payment (21), 1 for credit (79)
        assertEquals(3, settlements.size)
        assertTrue(settlements.any { it.ledgerEntryType == "PAYMENT" && it.transactionAmount == -21.0 })
        assertTrue(settlements.any { it.ledgerEntryType == "CREDIT" && it.transactionAmount == -79.0 })

        // Integrity check
        assertEquals(0, integrityDao.getBalanceMismatches().size, "Integrity check should pass for credit")
    }

    @Test
    fun scenario2_OrderConsumesCredit() = runTest {
        // Setup 79 credit
        repository.confirmOrder("Isaac", listOf(CartItem("A", 0.0, 0)), "CASH") // Placeholder to create customer
        repository.applyPaymentToCustomer("Isaac", 79.0, "CASH")
        assertEquals(-79.0, repository.getCustomerBalance("Isaac"))

        // Create 50 order
        repository.confirmOrder("Isaac", listOf(CartItem("B", 50.0, 1)), "OWES_ME")

        // Expected: Balance -29
        assertEquals(-29.0, repository.getCustomerBalance("Isaac"))
        
        val orders = dao.getAllOrders().filter { it.customerName == "Isaac" && it.totalAmount == 50.0 }
        assertEquals(1, orders.size)
        assertEquals(50.0, orders[0].paidAmount, "Order should be fully paid by credit")
        assertEquals("PAID", orders[0].paymentStatus)
    }

    @Test
    fun scenario3_ExactPayment() = runTest {
        repository.confirmOrder("Isaac", listOf(CartItem("A", 21.0, 1)), "OWES_ME")
        repository.applyPaymentToCustomer("Isaac", 21.0, "CASH")

        assertEquals(0.0, repository.getCustomerBalance("Isaac"))
        val settlements = dao.getAllSettlements().filter { it.customerName == "Isaac" }
        // 1 order, 1 payment
        assertEquals(2, settlements.size)
        assertTrue(settlements.none { it.ledgerEntryType == "CREDIT" })
    }

    @Test
    fun scenario4_PartialPayment() = runTest {
        repository.confirmOrder("Isaac", listOf(CartItem("A", 100.0, 1)), "OWES_ME")
        repository.applyPaymentToCustomer("Isaac", 40.0, "CASH")

        assertEquals(60.0, repository.getCustomerBalance("Isaac"))
        val order = dao.getAllOrders().first { it.customerName == "Isaac" }
        assertEquals(40.0, order.paidAmount)
        assertEquals("PARTIALLY_PAID", order.paymentStatus)
    }

    @Test
    fun scenario5_CreditPartiallyCoversOrder() = runTest {
        // Setup 79 credit
        repository.confirmOrder("Isaac", listOf(CartItem("A", 0.0, 0)), "CASH")
        repository.applyPaymentToCustomer("Isaac", 79.0, "CASH")
        assertEquals(-79.0, repository.getCustomerBalance("Isaac"))

        // Create 120 order
        repository.confirmOrder("Isaac", listOf(CartItem("C", 120.0, 1)), "OWES_ME")

        // Expected: Balance 41 debt (120 - 79)
        assertEquals(41.0, repository.getCustomerBalance("Isaac"))
        
        val orders = dao.getAllOrders().filter { it.customerName == "Isaac" && it.totalAmount == 120.0 }
        assertEquals(1, orders.size)
        assertEquals(79.0, orders[0].paidAmount, "Order should be partially paid by 79 credit")
        assertEquals("PARTIALLY_PAID", orders[0].paymentStatus)
        assertEquals(120.0, orders[0].transactionAmount, "Account should be charged with the full order amount (less cash paid)")
    }
}
