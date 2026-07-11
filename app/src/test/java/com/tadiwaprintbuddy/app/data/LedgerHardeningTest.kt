package com.tadiwaprintbuddy.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.TestApplication
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class LedgerHardeningTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var repository: PrintRepository
    private val now = System.currentTimeMillis()

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
    fun testRecordCommercialOrder_CreatesLedgerEntry() = runTest {
        val customer = CustomerEntity(id = 1, displayName = "Rahul", normalizedName = "rahul")
        dao.insertCustomer(customer)

        val order = Order(
            customerId = 1,
            customerName = "Rahul",
            totalAmount = 100.0,
            paidAmount = 20.0,
            transactionAmount = 80.0,
            previousBalance = 0.0,
            newBalance = 80.0,
            date = now
        )

        dao.recordCommercialOrder(order, emptyList())

        // Verify Order
        val orders = dao.getAllOrders()
        assertEquals(1, orders.size)
        val orderId = orders[0].id

        // Verify Settlement History
        val settlements = dao.getAllSettlements()
        assertEquals(1, settlements.size)
        val settlement = settlements[0]
        assertEquals("ORDER_POST", settlement.ledgerEntryType)
        assertEquals(orderId, settlement.originId)
        assertEquals(80.0, settlement.transactionAmount)

        // Verify Projection
        val projection = dao.getDebtorCreditById(1)
        assertEquals(80.0, projection?.amount)
    }

    @Test
    fun testUniquenessEnforcement_OrderPost() = runTest {
        val customer = CustomerEntity(id = 1, displayName = "Rahul", normalizedName = "rahul")
        dao.insertCustomer(customer)

        val settlement = SettlementHistory(
            customerId = 1,
            customerName = "Rahul",
            balanceBefore = 0.0,
            amountPaid = 0.0,
            balanceAfter = 100.0,
            timestamp = now,
            originId = 999,
            ledgerEntryType = "ORDER_POST",
            transactionAmount = 100.0
        )

        dao.insertSettlement(settlement)

        // Try to insert same order post again
        try {
            dao.insertSettlement(settlement.copy(id = 0))
            assertTrue(false, "Should have thrown SQLiteConstraintException")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun testRebuildCustomerProjection() = runTest {
        val customer = CustomerEntity(id = 1, displayName = "Rahul", normalizedName = "rahul")
        dao.insertCustomer(customer)

        // Directly insert orders bypassing logic to simulate old state or inconsistency
        dao.insertOrder(Order(customerId = 1, customerName = "Rahul", totalAmount = 100.0, paidAmount = 0.0, date = now))
        dao.insertOrder(Order(customerId = 1, customerName = "Rahul", totalAmount = 50.0, paidAmount = 10.0, date = now + 1000))

        // Initial projection might be missing or wrong
        dao.insertOrUpdateDebtorCredit(DebtorCredit(1, "Rahul", 0.0, now))

        // Verify inconsistency
        assertFalse(dao.verifyCustomerBalance(1))

        // Rebuild
        dao.rebuildCustomerProjection(1)

        // Verify consistency
        assertTrue(dao.verifyCustomerBalance(1))
        val projection = dao.getDebtorCreditById(1)
        assertEquals(140.0, projection?.amount)
    }
}
