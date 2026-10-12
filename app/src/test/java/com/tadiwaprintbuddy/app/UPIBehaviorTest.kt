package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class UPIBehaviorTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var repository: PrintRepository
    private lateinit var syncRepository: NetworkSyncRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.printDao()
        repository = PrintRepository(dao)
        val testPrefs = context.getSharedPreferences("upi_sync_test", Context.MODE_PRIVATE)
        syncRepository = NetworkSyncRepository(
            context,
            AuthRepository.getTestInstance(context, testPrefs),
            db
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `Home UPI sale creates one Order and a shadow wallet projection`() = runBlocking {
        dao.insertStockItem(StockItem(name = "Service", currentQuantity = 100))

        val cartItems = listOf(CartItem("Service", BigDecimal("100.0"), 1))
        val result = repository.confirmOrder("UPI Customer", cartItems, "UPI")
        assertTrue(result is OrderResult.Success)

        val orders = dao.getAllOrders()
        assertEquals(1, orders.size)
        val order = orders[0]
        assertEquals("UPI", order.paymentMethod)
        assertEquals(0, order.paidAmount.compareTo(BigDecimal("100.0")))
        assertEquals(1, repository.getSettledOrderCountByMethodBetween(0L, Long.MAX_VALUE, "UPI"))
        assertEquals(0, BigDecimal("100.0").compareTo(repository.getSettledRevenueByMethodBetween(0L, Long.MAX_VALUE, "UPI")))

        val upiTransactions = dao.getAllUpiAccountTransactions()
        assertTrue(upiTransactions.isNotEmpty())
        val lastTransaction = upiTransactions.last()
        assertEquals(0, lastTransaction.amount.compareTo(BigDecimal("100.0")))
        assertEquals(0, lastTransaction.type.compareTo("ADD"))

        val walletBalance = dao.getAuthoritativeWalletBalance()
        assertEquals(0, walletBalance.compareTo(BigDecimal("100.0")))

        val settlements = dao.getAllSettlements()
        assertTrue(settlements.size >= 2)
        val hasOrderPost = settlements.any { it.ledgerEntryType == "ORDER_POST" }
        val hasPayment = settlements.any { it.ledgerEntryType == "PAYMENT" }
        assertTrue(hasOrderPost)
        assertTrue(hasPayment)
        assertTrue(settlements.single { it.ledgerEntryType == "UPI_ACCOUNT_TOPUP" }.isShadowDuplicate)
    }

    @Test
    fun `manual UPI account receipt creates one paid Order linked to canonical UPI transaction`() = runBlocking {
        repository.insertUpiAccountTransaction(BigDecimal("1000.0"), "ADD", "Manual top-up", createOrderHistoryEntry = true)

        // OrdersActivity loads this exact DAO list.
        val orders = dao.getAllOrders()
        assertEquals(1, orders.size)
        val order = orders.single()
        assertEquals("UPI Account", order.customerName)
        assertEquals(0L, order.customerId)
        assertEquals("UPI", order.paymentMethod)
        assertEquals("PAID", order.paymentStatus)
        assertEquals(0, order.totalAmount.compareTo(BigDecimal("1000.0")))
        assertEquals(0, order.paidAmount.compareTo(BigDecimal("1000.0")))
        assertEquals(0, order.previousBalance.compareTo(BigDecimal.ZERO))
        assertEquals(0, order.transactionAmount.compareTo(BigDecimal.ZERO))
        assertEquals(0, order.newBalance.compareTo(BigDecimal.ZERO))
        assertTrue(dao.getItemsForOrder(order.id).isEmpty())

        val upiTransactions = dao.getAllUpiAccountTransactions()
        assertEquals(1, upiTransactions.size)
        assertEquals(0, upiTransactions[0].amount.compareTo(BigDecimal("1000.0")))
        assertEquals(upiTransactions[0].syncId, order.syncId)
        assertEquals(order.id, dao.upsertManualUpiOrderHistory(upiTransactions[0]))
        assertEquals(1, dao.getAllOrders().size)

        val linkedSettlement = dao.getAllSettlements().firstOrNull {
            it.customerName == "UPI Account" &&
                it.ledgerEntryType == "UPI_ACCOUNT_TOPUP"
        }
        assertTrue(linkedSettlement != null)
        assertTrue(linkedSettlement?.isShadowDuplicate == false)
        assertEquals(order.id, linkedSettlement?.originId)
        assertEquals(order.syncId, linkedSettlement?.originSyncId)
        val start = System.currentTimeMillis() - 60_000
        val end = System.currentTimeMillis() + 60_000
        assertEquals(0, BigDecimal("1000.0").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "UPI")))
        assertEquals(1, repository.getSettledOrderCountByMethodBetween(start, end, "UPI"))
        assertEquals(0, BigDecimal.ZERO.compareTo(repository.getTotalReceivables()))
        assertEquals(0, repository.getCustomerBalanceTotals().debtorsCount)
    }

    @Test
    fun `manual UPI account inflow is not treated as customer amount due in ledger history`() = runBlocking {
        repository.insertUpiAccountTransaction(BigDecimal("250.0"), "ADD", "Manual top-up")

        val settlements = dao.getAllSettlements()
        val group = SettlementHistoryViewModel(repository).groupAndSort(
            settlements = settlements,
            customers = dao.getAllCustomers(),
            order = TransactionSortOrder.NEWEST_FIRST,
            query = "",
            expandedIds = emptySet()
        ).firstOrNull { it.customerName == "UPI Account" }

        assertTrue(group != null)
        assertEquals(0, group!!.totalOwed.compareTo(BigDecimal.ZERO))
        assertTrue(group.events.any { it is BusinessEvent.WalletAdjustment && it.isInflow })
        assertEquals(0, repository.getCustomerBalanceTotals().debtorsCount)
    }

    @Test
    fun `dashboard reads canonical UPI ledger for wallet and flow metrics`() = runBlocking {
        repository.insertUpiAccountTransaction(BigDecimal("250.0"), "ADD", "UPI Account screen")
        dao.insertStockItem(StockItem(name = "Service", currentQuantity = 100))
        val orderResult = repository.confirmOrder("Home User", listOf(CartItem("Service", BigDecimal("75.0"), 1)), "UPI")
        assertTrue(orderResult is OrderResult.Success)

        val start = System.currentTimeMillis() - 60_000
        val end = System.currentTimeMillis() + 60_000

        val balance = repository.getCurrentUpiAccountBalance()
        val inflow = repository.getUpiAccountReceivedBetween(start, end)
        val outflow = repository.getUpiAccountReturnedBetween(start, end)
        val count = repository.getUpiAccountTransactionCountBetween(start, end)

        assertEquals(0, balance.compareTo(BigDecimal("325.0")))
        assertEquals(0, inflow.compareTo(BigDecimal("325.0")))
        assertEquals(0, outflow.compareTo(BigDecimal("0.0")))
        assertTrue(count >= 2)
    }

    @Test
    fun `sync pull restores customerless UPI Account Order and its linked settlement`() = runBlocking {
        val syncId = "manual-upi-history-sync"
        val timestamp = System.currentTimeMillis()

        syncRepository.applyRemoteEventSync(com.tadiwaprintbuddy.app.api.SyncEventRequest(
            entityType = "UPI_ACCOUNT_TRANSACTION",
            entitySyncId = syncId,
            operation = "CREATE",
            data = mapOf(
                "amount" to "1000.00",
                "type" to "ADD",
                "note" to "Remote business receipt",
                "timestamp" to timestamp,
                "previousBalance" to "0.00",
                "transactionAmount" to "1000.00",
                "newBalance" to "1000.00",
                "syncId" to syncId,
                "updatedAt" to timestamp
            ),
            timestamp = timestamp,
            idempotencyKey = "wallet-event"
        ))
        syncRepository.applyRemoteEventSync(com.tadiwaprintbuddy.app.api.SyncEventRequest(
            entityType = "ORDER",
            entitySyncId = syncId,
            operation = "CREATE",
            data = mapOf(
                "totalAmount" to "1000.00",
                "date" to timestamp,
                "customerName" to "UPI Account",
                "paidAmount" to "1000.00",
                "paymentMethod" to "UPI",
                "customerId" to 0,
                "customerSyncId" to "",
                "previousBalance" to "0.00",
                "transactionAmount" to "0.00",
                "newBalance" to "0.00",
                "paymentStatus" to "PAID",
                "orderStatus" to "ACTIVE",
                "syncId" to syncId,
                "updatedAt" to timestamp
            ),
            timestamp = timestamp,
            idempotencyKey = "order-event"
        ))
        syncRepository.applyRemoteEventSync(com.tadiwaprintbuddy.app.api.SyncEventRequest(
            entityType = "SETTLEMENT",
            entitySyncId = "manual-upi-history-settlement",
            operation = "CREATE",
            data = mapOf(
                "customerName" to "UPI Account",
                "customerSyncId" to "",
                "balanceBefore" to "0.00",
                "amountPaid" to "1000.00",
                "balanceAfter" to "1000.00",
                "timestamp" to timestamp,
                "type" to "UPI_ACCOUNT",
                "note" to "Remote business receipt",
                "customerId" to 0,
                "transactionAmount" to "1000.00",
                "newBalance" to "1000.00",
                "originId" to null,
                "originSyncId" to syncId,
                "ledgerEntryType" to "UPI_ACCOUNT_TOPUP",
                "isShadowDuplicate" to false,
                "reconciliationStatus" to "VERIFIED",
                "paymentMethod" to "UPI",
                "syncId" to "manual-upi-history-settlement",
                "updatedAt" to timestamp
            ),
            timestamp = timestamp,
            idempotencyKey = "settlement-event"
        ))

        val orders = dao.getAllOrders()
        assertEquals(1, orders.size)
        assertEquals("UPI Account", orders.single().customerName)
        assertEquals(syncId, orders.single().syncId)
        assertEquals(0, orders.single().transactionAmount.compareTo(BigDecimal.ZERO))
        assertEquals(0, orders.single().paidAmount.compareTo(BigDecimal("1000.00")))

        val settlement = dao.getAllSettlements().single { it.ledgerEntryType == "UPI_ACCOUNT_TOPUP" }
        assertEquals(orders.single().id, settlement.originId)
        assertEquals(0L, settlement.customerId)
        assertEquals(0, repository.getCustomerBalanceTotals().debtorsCount)
    }
}
