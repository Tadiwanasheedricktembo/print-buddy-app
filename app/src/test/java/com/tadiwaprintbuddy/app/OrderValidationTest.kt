package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.data.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class OrderValidationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var repository: PrintRepository

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.printDao()
        repository = PrintRepository(dao)
    }

    @After
    fun teardown() {
        kotlinx.coroutines.Dispatchers.resetMain()
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
        val cartItems = listOf(CartItem("Service", BigDecimal.ZERO, 1))
        val result = repository.confirmOrder("Customer", cartItems)
        assertTrue(result is OrderResult.ValidationError)
        assertTrue((result as OrderResult.ValidationError).message == "Enter a valid amount greater than ₹0")
    }

    @Test
    fun `test confirmOrder with zero quantity returns ValidationError`() = runBlocking {
        val cartItems = listOf(CartItem("Service", BigDecimal.TEN, 0))
        val result = repository.confirmOrder("Customer", cartItems)
        assertTrue(result is OrderResult.ValidationError)
    }

    @Test
    fun `test confirmOrder with negative price returns ValidationError`() = runBlocking {
        val cartItems = listOf(CartItem("Service", BigDecimal("-10.0"), 1))
        val result = repository.confirmOrder("Customer", cartItems)
        assertTrue(result is OrderResult.ValidationError)
    }

    @Test
    fun `test confirmOrder with valid cash order succeeds`() = runBlocking {
        val cartItems = listOf(CartItem("Service", BigDecimal("100.0"), 1))
        val result = repository.confirmOrder("Customer", cartItems, "CASH")
        assertTrue(result is OrderResult.Success)
        
        val orders = dao.getAllOrders()
        assertTrue(orders.size == 1)
        assertTrue(orders[0].paymentStatus == "PAID")
    }

    @Test
    fun `test confirmOrder with valid unpaid credit order succeeds`() = runBlocking {
        val cartItems = listOf(CartItem("Service", BigDecimal("100.0"), 1))
        val result = repository.confirmOrder("Customer", cartItems, "OWES_ME")
        assertTrue(result is OrderResult.Success)
        
        val orders = dao.getAllOrders()
        assertTrue(orders.size == 1)
        assertTrue(orders[0].paymentStatus == "UNPAID")
        assertTrue(orders[0].paymentMethod == "NONE")
    }

    @Test
    fun `test confirmOrder with valid upi order succeeds`() = runBlocking {
        val cartItems = listOf(CartItem("Service", BigDecimal("100.0"), 1))
        val result = repository.confirmOrder("Customer", cartItems, "UPI")
        assertTrue(result is OrderResult.Success)
        
        val orders = dao.getAllOrders()
        assertTrue(orders.size == 1)
        assertTrue(orders[0].paymentMethod == "UPI")
        assertTrue(orders[0].paidAmount.compareTo(BigDecimal("100.0")) == 0)
    }

    @Test
    fun `test beauty account manual upi transaction records settlement history`() = runBlocking {
        val before = dao.getAllSettlements()

        repository.insertBeautyTransaction(BigDecimal("150.0"), "ADD", "Manual top up")

        val settlements = dao.getAllSettlements()
        assertTrue(settlements.size == before.size + 1)
        assertTrue(settlements.last().customerName == "UPI Account")
        assertTrue(settlements.last().amountPaid.compareTo(BigDecimal("150.0")) == 0)
        assertTrue(settlements.last().ledgerEntryType == "UPI_ACCOUNT_TOPUP")
    }

    @Test
    fun `test atomic transaction rollback on failed stock deduction`() = runBlocking {
        // First add a stock item with 5 quantity
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 5))
        
        val cartItems = listOf(CartItem("Paper", BigDecimal.TEN, 10)) // Requesting 10 but only 5 available
        val result = repository.confirmOrder("Customer", cartItems)
        
        assertTrue(result is OrderResult.InsufficientStock)
        
        // Verify no order was created
        val orders = dao.getAllOrders()
        assertTrue(orders.isEmpty())
        
        // Verify no settlement record was created
        val settlements = dao.getAllSettlements()
        assertTrue(settlements.isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `test MainViewModel automatic order saving for Cash, UPI, and Credit`() = kotlinx.coroutines.test.runTest {
        val mainViewModel = MainViewModel(repository)

        // 1. Cash Order
        mainViewModel.onCustomerNameChanged("Cash Customer")
        mainViewModel.onPriceChanged(BigDecimal("100.00"))
        mainViewModel.onQuantityChanged(2)
        advanceUntilIdle()
        val cashCompletion = async(start = CoroutineStart.UNDISPATCHED) { mainViewModel.events.first() }
        mainViewModel.completeOrder("Paid", "CASH")
        org.junit.Assert.assertEquals(MainViewModel.MainEvent.OrderCompleted, cashCompletion.await())

        var orders = dao.getAllOrders().sortedBy { it.date }
        org.junit.Assert.assertEquals(1, orders.size)
        org.junit.Assert.assertEquals("PAID", orders[0].paymentStatus)
        org.junit.Assert.assertEquals("CASH", orders[0].paymentMethod)

        // 2. UPI Order
        mainViewModel.onCustomerNameChanged("UPI Customer")
        mainViewModel.onPriceChanged(BigDecimal("150.00"))
        mainViewModel.onQuantityChanged(1)
        advanceUntilIdle()
        val upiCompletion = async(start = CoroutineStart.UNDISPATCHED) { mainViewModel.events.first() }
        mainViewModel.completeOrder("Paid", "UPI")
        org.junit.Assert.assertEquals(MainViewModel.MainEvent.OrderCompleted, upiCompletion.await())

        orders = dao.getAllOrders().sortedBy { it.date }
        org.junit.Assert.assertEquals(2, orders.size)
        org.junit.Assert.assertEquals("PAID", orders[1].paymentStatus)
        org.junit.Assert.assertEquals("UPI", orders[1].paymentMethod)

        // 3. Credit Order
        mainViewModel.onCustomerNameChanged("Credit Customer")
        mainViewModel.onPriceChanged(BigDecimal("200.00"))
        mainViewModel.onQuantityChanged(1)
        advanceUntilIdle()
        val creditCompletion = async(start = CoroutineStart.UNDISPATCHED) { mainViewModel.events.first() }
        mainViewModel.completeOrder("Credit", "NONE")
        org.junit.Assert.assertEquals(MainViewModel.MainEvent.OrderCompleted, creditCompletion.await())

        orders = dao.getAllOrders().sortedBy { it.date }
        org.junit.Assert.assertEquals(3, orders.size)
        org.junit.Assert.assertEquals("UNPAID", orders[2].paymentStatus)
        org.junit.Assert.assertEquals("NONE", orders[2].paymentMethod)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `test MainViewModel prevents duplicate saving during in-progress save`() = kotlinx.coroutines.test.runTest {
        val mainViewModel = MainViewModel(repository)
        mainViewModel.onCustomerNameChanged("Duplicate Test Customer")
        mainViewModel.onPriceChanged(BigDecimal("50.00"))
        mainViewModel.onQuantityChanged(1)
        advanceUntilIdle()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { mainViewModel.events.first() }

        // Concurrent/repeated calls
        mainViewModel.completeOrder("Paid", "CASH")
        mainViewModel.completeOrder("Paid", "CASH")
        org.junit.Assert.assertEquals(MainViewModel.MainEvent.OrderCompleted, completion.await())

        val orders = dao.getAllOrders()
        org.junit.Assert.assertEquals(1, orders.size)
    }

    @Test
    fun calculatorOrderCopiesPersistMatchingAmountAndPageCounts() = kotlinx.coroutines.test.runTest {
        val mainViewModel = MainViewModel(repository)
        val bwLine = PrintOrderLine(PrintLineType.BLACK_AND_WHITE, "B&W", 3, BigDecimal("2.00"))
        val colorLine = PrintOrderLine(PrintLineType.COLOR, "Color", 2, BigDecimal("5.00"))
        mainViewModel.onCustomerNameChanged("Copy Customer")
        mainViewModel.onPriceChanged(BigDecimal("16.00"))
        mainViewModel.onQuantityChanged(2)
        mainViewModel.onCalculatorOrderSelected(
            listOf(
                CartItem(bwLine.toStoredServiceName(), bwLine.unitPrice, bwLine.quantity),
                CartItem(colorLine.toStoredServiceName(), colorLine.unitPrice, colorLine.quantity)
            ),
            totalPages = 5
        )
        advanceUntilIdle()

        val completion = async(start = CoroutineStart.UNDISPATCHED) { mainViewModel.events.first() }
        mainViewModel.completeOrder("Paid", "CASH")
        org.junit.Assert.assertEquals(MainViewModel.MainEvent.OrderCompleted, completion.await())

        val order = dao.getAllOrders().single()
        val items = dao.getItemsForOrder(order.id)
        org.junit.Assert.assertEquals(10, order.printCount)
        org.junit.Assert.assertEquals(0, BigDecimal("32.00").compareTo(order.totalAmount))
        org.junit.Assert.assertEquals(6, items.single { PrintOrderLine.typeFromStoredServiceName(it.serviceName) == PrintLineType.BLACK_AND_WHITE }.quantity)
        org.junit.Assert.assertEquals(4, items.single { PrintOrderLine.typeFromStoredServiceName(it.serviceName) == PrintLineType.COLOR }.quantity)
        org.junit.Assert.assertTrue(items.all { PrintOrderLine.copiesFromStoredServiceName(it.serviceName) == 2 })
    }
}
