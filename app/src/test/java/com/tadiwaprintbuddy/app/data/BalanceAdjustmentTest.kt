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
class BalanceAdjustmentTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var repository: PrintRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.printDao()
        repository = PrintRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDecreaseBalance() = runTest {
        // Setup: Customer owes 800
        val result = repository.confirmOrder("Customer A", listOf(CartItem("Paper", 800.0, 1)), "OWES_ME")
        val customerId = (dao.getAllCustomers().first()).id
        assertEquals(800.0, repository.getCustomerBalanceById(customerId))

        // Adjustment: New balance 600
        repository.adjustCustomerBalance(customerId, 600.0, "Correction")

        // Verify
        assertEquals(600.0, repository.getCustomerBalanceById(customerId))
        
        // Verify History
        val settlements = dao.getAllSettlements().filter { it.customerId == customerId }
        assertTrue(settlements.any { it.ledgerEntryType == "ADJUSTMENT" && it.transactionAmount == -200.0 })
    }

    @Test
    fun testIncreaseBalance() = runTest {
        // Setup: Customer owes 800
        repository.confirmOrder("Customer A", listOf(CartItem("Paper", 800.0, 1)), "OWES_ME")
        val customerId = (dao.getAllCustomers().first()).id

        // Adjustment: New balance 1100
        repository.adjustCustomerBalance(customerId, 1100.0, "Forgotten charge")

        // Verify
        assertEquals(1100.0, repository.getCustomerBalanceById(customerId))
        
        // Verify History
        val settlements = dao.getAllSettlements().filter { it.customerId == customerId }
        assertTrue(settlements.any { it.ledgerEntryType == "ADJUSTMENT" && it.transactionAmount == 300.0 })
    }

    @Test
    fun testZeroBalance() = runTest {
        repository.confirmOrder("Customer A", listOf(CartItem("Paper", 800.0, 1)), "OWES_ME")
        val customerId = (dao.getAllCustomers().first()).id

        repository.adjustCustomerBalance(customerId, 0.0, "Cleared manually")

        assertEquals(0.0, repository.getCustomerBalanceById(customerId))
    }

    @Test
    fun testExistingCredit() = runTest {
        // Setup: Customer has 100 credit (Balance -100)
        // First create a debt of 200
        repository.confirmOrder("Customer A", listOf(CartItem("Paper", 200.0, 1)), "OWES_ME")
        val customerId = (dao.getAllCustomers().first()).id
        
        // Then pay 300
        repository.applyPaymentToCustomerId(customerId, 300.0)
        assertEquals(-100.0, repository.getCustomerBalanceById(customerId))

        // Adjustment: Set balance to 50 (Now they owe 50)
        repository.adjustCustomerBalance(customerId, 50.0)

        // Verify
        assertEquals(50.0, repository.getCustomerBalanceById(customerId))
        
        // Delta should be 50 - (-100) = 150
        val settlements = dao.getAllSettlements().filter { it.customerId == customerId }
        assertTrue(settlements.any { it.ledgerEntryType == "ADJUSTMENT" && it.transactionAmount == 150.0 })
    }

    @Test
    fun testCustomerIsolation() = runTest {
        repository.confirmOrder("A", listOf(CartItem("G", 100.0, 1)), "OWES_ME")
        repository.confirmOrder("B", listOf(CartItem("G", 200.0, 1)), "OWES_ME")
        
        val custA = dao.getCustomerByNormalizedName("a")!!.id
        val custB = dao.getCustomerByNormalizedName("b")!!.id
        
        repository.adjustCustomerBalance(custA, 50.0)
        
        assertEquals(50.0, repository.getCustomerBalanceById(custA))
        assertEquals(200.0, repository.getCustomerBalanceById(custB))
    }
}
