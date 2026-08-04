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
    fun testRecordOrderAtomic_CreatesLedgerEntry() = runTest {
        val result = repository.confirmOrder("Rahul", listOf(CartItem("General", 100.0, 1)), "CASH", 20.0)

        assertTrue(result is OrderResult.Success)
        val orderId = (result as OrderResult.Success).orderId

        // Verify Order
        val order = dao.getOrderById(orderId)
        assertEquals(80.0, order?.paidAmount)
        assertEquals(20.0, order?.transactionAmount)

        // Verify Settlement History
        val settlements = dao.getAllSettlements()
        // Now creates 2 entries: ORDER_POST (+100) and PAYMENT (-80)
        assertEquals(2, settlements.size)
        assertTrue(settlements.any { it.ledgerEntryType == "ORDER_POST" && it.transactionAmount == 100.0 })
        assertTrue(settlements.any { it.ledgerEntryType == "PAYMENT" && it.transactionAmount == -80.0 })

        // Verify Projection
        val customer = dao.getCustomerByNormalizedName("rahul")
        val projection = dao.getDebtorCreditById(customer!!.id)
        assertEquals(20.0, projection?.amount)
    }

    @Test
    fun testUniquenessEnforcement_OrderPost_Removed() = runTest {
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

        // Try to insert same order post again - SHOULD SUCCEED NOW as we allowed partial payments
        dao.insertSettlement(settlement.copy(id = 0))
        val all = dao.getAllSettlements()
        assertEquals(2, all.size)
    }

    @Test
    fun testRebuildCustomerProjection() = runTest {
        val customerId = dao.insertCustomer(CustomerEntity(displayName = "Rahul", normalizedName = "rahul"))

        // Directly insert orders bypassing logic
        dao.insertOrder(Order(customerId = customerId, customerName = "Rahul", totalAmount = 100.0, paidAmount = 0.0, date = now, paymentStatus = "UNPAID"))
        dao.insertOrder(Order(customerId = customerId, customerName = "Rahul", totalAmount = 50.0, paidAmount = 10.0, date = now + 1000, paymentStatus = "PARTIALLY_PAID"))

        // Rebuild
        dao.rebuildCustomerProjection(customerId)

        val projection = dao.getDebtorCreditById(customerId)
        assertEquals(140.0, projection?.amount)
    }
}
