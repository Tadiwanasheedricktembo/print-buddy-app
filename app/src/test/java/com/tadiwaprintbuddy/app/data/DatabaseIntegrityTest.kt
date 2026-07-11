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

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class DatabaseIntegrityTest {

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
    fun customerNamesAreNormalizedToOneIdentity() = runTest {
        repository.confirmOrder(" Rahul ", listOf(CartItem("G", 100.0, 1)), "OWES_ME")
        repository.confirmOrder("rahul", listOf(CartItem("G", 50.0, 1)), "OWES_ME")
        // To get paidAmount = 25.0 for a 75.0 order, we apply 50.0 credit (or vice versa depending on logic)
        // Here confirmOrder logic is: paidAmount = total - appliedCredit
        repository.confirmOrder("RAHUL", listOf(CartItem("G", 75.0, 1)), "CASH", 50.0)

        val customers = dao.getAllCustomers()
        assertEquals(1, customers.size)
        assertEquals("Rahul", customers.single().displayName)
        assertEquals(200.0, repository.getCustomerBalance(" rahul "), 0.1)
    }

    @Test
    fun newCustomerBalanceFallsBackToUnpaidOrdersWhenNoSettlementExists() = runTest {
        val customerId = dao.insertCustomer(CustomerEntity(displayName = "Maya", normalizedName = "maya"))
        dao.insertOrder(
            Order(customerId = customerId, customerName = "Maya", totalAmount = 120.0, paidAmount = 20.0, date = now, paymentStatus = "PARTIALLY_PAID")
        )
        dao.insertOrder(
            Order(customerId = customerId, customerName = "Maya", totalAmount = 80.0, paidAmount = 0.0, date = now + 1000, paymentStatus = "UNPAID")
        )

        assertEquals(180.0, repository.getCustomerBalanceById(customerId))
    }

    @Test
    fun paymentIsAllocatedOldestDebtFirst() = runTest {
        repository.confirmOrder("Nia", listOf(CartItem("G", 100.0, 1)), "OWES_ME")
        repository.confirmOrder("nia", listOf(CartItem("G", 50.0, 1)), "OWES_ME")
        repository.confirmOrder(" NIA ", listOf(CartItem("G", 30.0, 1)), "OWES_ME")

        val customerId = dao.getCustomerByNormalizedName("nia")!!.id
        repository.applyPaymentToCustomerId(customerId, 120.0)

        val unpaid = dao.getUnpaidOrdersForCustomer(customerId)
        assertEquals(listOf(30.0, 30.0), unpaid.map { it.totalAmount - it.paidAmount })
        assertEquals(60.0, repository.getCustomerBalanceById(customerId))
    }
}
