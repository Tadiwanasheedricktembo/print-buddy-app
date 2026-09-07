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
import java.math.BigDecimal
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
        repository.confirmOrder("Rahul", listOf(CartItem("General", BigDecimal("100.0"), 1)), "CASH", BigDecimal("20.0"))

        val order = dao.getAllOrders().first()
        assertEquals(0, BigDecimal("80.0").compareTo(order.paidAmount))
        assertEquals(0, BigDecimal("20.0").compareTo(order.transactionAmount))

        // Verify Settlement History
        val settlements = dao.getAllSettlements()
        assertEquals(2, settlements.size)
        assertTrue(settlements.any { it.ledgerEntryType == "ORDER_POST" && it.transactionAmount.compareTo(BigDecimal("100.0")) == 0 })
        assertTrue(settlements.any { it.ledgerEntryType == "PAYMENT" && it.transactionAmount.compareTo(BigDecimal("-80.0")) == 0 })

        // Verify Projection
        val customer = dao.getCustomerByNormalizedName("rahul")
        val projection = dao.getDebtorCreditById(customer!!.id)
        assertEquals(0, BigDecimal("20.0").compareTo(projection?.amount))
    }

    @Test
    fun testUniquenessEnforcement_OrderPost_Removed() = runTest {
        val customer = CustomerEntity(id = 1, displayName = "Rahul", normalizedName = "rahul")
        dao.insertCustomer(customer)

        val settlement = SettlementHistory(
            customerId = 1,
            customerName = "Rahul",
            balanceBefore = BigDecimal.ZERO,
            amountPaid = BigDecimal.ZERO,
            balanceAfter = BigDecimal("100.0"),
            timestamp = now,
            originId = 999,
            ledgerEntryType = "ORDER_POST",
            transactionAmount = BigDecimal("100.0")
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
        dao.insertOrder(Order(customerId = customerId, customerName = "Rahul", totalAmount = BigDecimal("100.0"), paidAmount = BigDecimal.ZERO, date = now, paymentStatus = "UNPAID"))
        dao.insertOrder(Order(customerId = customerId, customerName = "Rahul", totalAmount = BigDecimal("50.0"), paidAmount = BigDecimal.TEN, date = now + 1000, paymentStatus = "PARTIALLY_PAID"))

        // Rebuild
        dao.rebuildCustomerProjection(customerId)

        val projection = dao.getDebtorCreditById(customerId)
        assertEquals(0, BigDecimal("140.0").compareTo(projection?.amount))
    }
}
