package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class OrderValidationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var repository: PrintRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.printDao()
        repository = PrintRepository(dao)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `test confirmOrder with empty cart returns ValidationError`() = runBlocking {
        val result = repository.confirmOrder("Customer", emptyList())
        assertTrue(result is OrderResult.ValidationError)
        assertTrue((result as OrderResult.ValidationError).message == "Add at least one item")
    }

    @Test
    fun `test confirmOrder with zero total returns ValidationError`() = runBlocking {
        val cartItems = listOf(CartItem("Service", 0.0, 1))
        val result = repository.confirmOrder("Customer", cartItems)
        assertTrue(result is OrderResult.ValidationError)
        assertTrue((result as OrderResult.ValidationError).message == "Enter a valid amount greater than ₹0")
    }

    @Test
    fun `test confirmOrder with zero quantity returns ValidationError`() = runBlocking {
        val cartItems = listOf(CartItem("Service", 10.0, 0))
        val result = repository.confirmOrder("Customer", cartItems)
        assertTrue(result is OrderResult.ValidationError)
    }

    @Test
    fun `test confirmOrder with negative price returns ValidationError`() = runBlocking {
        val cartItems = listOf(CartItem("Service", -10.0, 1))
        val result = repository.confirmOrder("Customer", cartItems)
        assertTrue(result is OrderResult.ValidationError)
    }

    @Test
    fun `test confirmOrder with valid cash order succeeds`() = runBlocking {
        val cartItems = listOf(CartItem("Service", 100.0, 1))
        val result = repository.confirmOrder("Customer", cartItems, "CASH")
        assertTrue(result is OrderResult.Success)
        
        val orders = dao.getAllOrders()
        assertTrue(orders.size == 1)
        assertTrue(orders[0].paymentStatus == "PAID")
    }

    @Test
    fun `test confirmOrder with valid unpaid credit order succeeds`() = runBlocking {
        val cartItems = listOf(CartItem("Service", 100.0, 1))
        val result = repository.confirmOrder("Customer", cartItems, "OWES_ME")
        assertTrue(result is OrderResult.Success)
        
        val orders = dao.getAllOrders()
        assertTrue(orders.size == 1)
        assertTrue(orders[0].paymentStatus == "UNPAID")
        assertTrue(orders[0].paymentMethod == "NONE")
    }

    @Test
    fun `test confirmOrder with valid upi order succeeds`() = runBlocking {
        val cartItems = listOf(CartItem("Service", 100.0, 1))
        val result = repository.confirmOrder("Customer", cartItems, "UPI")
        assertTrue(result is OrderResult.Success)
        
        val orders = dao.getAllOrders()
        assertTrue(orders.size == 1)
        assertTrue(orders[0].paymentMethod == "UPI")
        assertTrue(orders[0].paidAmount == 100.0)
    }

    @Test
    fun `test atomic transaction rollback on failed stock deduction`() = runBlocking {
        // First add a stock item with 5 quantity
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 5))
        
        val cartItems = listOf(CartItem("Paper", 10.0, 10)) // Requesting 10 but only 5 available
        val result = repository.confirmOrder("Customer", cartItems)
        
        assertTrue(result is OrderResult.InsufficientStock)
        
        // Verify no order was created
        val orders = dao.getAllOrders()
        assertTrue(orders.isEmpty())
        
        // Verify no settlement record was created
        val settlements = dao.getAllSettlements()
        assertTrue(settlements.isEmpty())
    }
}
