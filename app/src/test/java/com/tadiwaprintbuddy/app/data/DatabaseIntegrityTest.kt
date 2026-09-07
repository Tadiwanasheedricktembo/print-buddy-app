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
import java.math.BigDecimal
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
        repository.confirmOrder(" Rahul ", listOf(CartItem("G", BigDecimal("100.0"), 1)), "OWES_ME")
        repository.confirmOrder("rahul", listOf(CartItem("G", BigDecimal("50.0"), 1)), "OWES_ME")
        repository.confirmOrder("RAHUL", listOf(CartItem("G", BigDecimal("75.0"), 1)), "CASH", BigDecimal("50.0"))

        val customers = dao.getAllCustomers()
        assertEquals(1, customers.size)
        assertEquals("Rahul", customers.single().displayName)
        assertEquals(0, BigDecimal("200.0").compareTo(repository.getCustomerBalance(" rahul ")))
    }

    @Test
    fun newCustomerBalanceFallsBackToUnpaidOrdersWhenNoSettlementExists() = runTest {
        val customerId = dao.insertCustomer(CustomerEntity(displayName = "Maya", normalizedName = "maya"))
        dao.insertOrder(
            Order(customerId = customerId, customerName = "Maya", totalAmount = BigDecimal("120.0"), paidAmount = BigDecimal("20.0"), date = now, paymentStatus = "PARTIALLY_PAID")
        )
        dao.insertOrder(
            Order(customerId = customerId, customerName = "Maya", totalAmount = BigDecimal("80.0"), paidAmount = BigDecimal.ZERO, date = now + 1000, paymentStatus = "UNPAID")
        )

        assertEquals(0, BigDecimal("180.0").compareTo(repository.getCustomerBalanceById(customerId)))
    }

    @Test
    fun paymentIsAllocatedOldestDebtFirst() = runTest {
        repository.confirmOrder("Nia", listOf(CartItem("G", BigDecimal("100.0"), 1)), "OWES_ME")
        repository.confirmOrder("nia", listOf(CartItem("G", BigDecimal("50.0"), 1)), "OWES_ME")
        repository.confirmOrder(" NIA ", listOf(CartItem("G", BigDecimal("30.0"), 1)), "OWES_ME")

        val customerId = dao.getCustomerByNormalizedName("nia")!!.id
        repository.applyPaymentToCustomerId(customerId, BigDecimal("120.0"))

        val unpaid = dao.getUnpaidOrdersForCustomer(customerId)
        assertEquals(listOf(BigDecimal("30.0"), BigDecimal("30.0")), unpaid.map { it.totalAmount.subtract(it.paidAmount) })
        assertEquals(0, BigDecimal("60.0").compareTo(repository.getCustomerBalanceById(customerId)))
    }
}
