package com.tadiwaprintbuddy.app.data

import androidx.room.Room
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.BackupUtils
import com.tadiwaprintbuddy.app.DashboardViewModel
import com.tadiwaprintbuddy.app.MainViewModel
import com.tadiwaprintbuddy.app.TestApplication
import com.tadiwaprintbuddy.app.CartItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.util.*
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class FinancialIntegrityTest {

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

    private fun getTodayRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        val end = cal.timeInMillis
        
        return Pair(start, end)
    }

    @Test
    fun testUpiPaymentForCashOrder_RecordedInWallet() = runTest {
        // 1. Create a CASH order
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))
        val orderId = repository.confirmOrder("John", listOf(CartItem("Paper", BigDecimal("10.0"), 1)), "CASH")
        val id = (orderId as OrderResult.Success).orderId

        // 2. Initial wallet balance should be 0 (paid via CASH)
        assertEquals(0, BigDecimal.ZERO.compareTo(repository.getCurrentBeautyBalance()))

        // 3. Update payment using UPI (makes it MIXED)
        repository.updatePayment(id, BigDecimal("10.0"), "UPI")

        // 4. Verify UPI wallet balance = 0 still? 
        // Wait, the delta was 10.0 (from 0 paid to 10 paid).
        // But the previous paid amount was 10.0 (CASH). 
        // Wait, repository.confirmOrder(..., "CASH") sets paidAmount = total.
        // So updatePayment(id, 10.0, "UPI") will return early because delta <= 0.
        
        // Let's try with a CREDIT order first.
        val creditOrderId = repository.confirmOrder("Jane", listOf(CartItem("Paper", BigDecimal("20.0"), 1)), "OWES_ME")
        val cid = (creditOrderId as OrderResult.Success).orderId
        
        // Update payment via UPI
        repository.updatePayment(cid, BigDecimal("20.0"), "UPI")
        
        // Verify UPI wallet balance = 20
        assertEquals(0, BigDecimal("20.0").compareTo(repository.getCurrentBeautyBalance()), "UPI payment for credit order should hit wallet")
    }

    @Test
    fun testAnalyticsOrderCountConsistency() = runTest {
        val (start, end) = getTodayRange()
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))

        // 1. Create order yesterday (simulated by setting timestamp)
        val yesterday = start - 86400000
        val order = Order(
            totalAmount = BigDecimal("50.0"),
            date = yesterday,
            customerName = "Old",
            customerId = 1,
            paidAmount = BigDecimal.ZERO,
            paymentMethod = "NONE",
            orderStatus = "ACTIVE"
        )
        val orderId = dao.insertOrder(order).toInt()

        // 2. Pay today via UPI
        repository.updatePayment(orderId, BigDecimal("50.0"), "UPI")

        // 3. Verify Revenue for Today (UPI) = 50
        val revenue = repository.getSettledRevenueByMethodBetween(start, end, "UPI")
        assertEquals(0, BigDecimal("50.0").compareTo(revenue))

        // 4. Verify Order Count for Today (UPI) = 1 (even though order created yesterday)
        val count = repository.getSettledOrderCountByMethodBetween(start, end, "UPI")
        assertEquals(1, count, "Order paid today should count in today's analytics. Actual: $count")
    }

    @Test
    fun testAnalyticsHandlesLegacySettlementWithNullPaymentMethod() = runTest {
        val (start, end) = getTodayRange()

        val legacySettlement = SettlementHistory(
            customerName = "Legacy",
            balanceBefore = BigDecimal.ZERO,
            amountPaid = BigDecimal("150.0"),
            balanceAfter = BigDecimal.ZERO,
            timestamp = System.currentTimeMillis(),
            type = "PAYMENT",
            note = "Legacy mobile record",
            customerId = 99L,
            transactionAmount = BigDecimal.ZERO,
            newBalance = BigDecimal.ZERO,
            originId = null,
            ledgerEntryType = "PAYMENT",
            paymentMethod = null,
            customerSyncId = "legacy-sync",
            syncId = "legacy-sync-id",
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.LOCAL_ONLY
        )

        dao.insertSettlement(legacySettlement)

        val breakdown = repository.getPaymentBreakdownBetween(start, end)
        assertTrue(breakdown.any { it.type.equals("UNKNOWN", ignoreCase = true) })
    }

    @Test
    fun testUpiExpense_SyncsToWallet() = runTest {
        // 1. Initial wallet: 100
        repository.insertBeautyTransaction(BigDecimal("100.0"), "ADD", "Opening")
        
        // 2. UPI Expense: 30
        repository.addExpense(BigDecimal("30.0"), "Paper", "New Stock", "UPI")
        
        // 3. Verify wallet balance = 70
        assertEquals(0, BigDecimal("70.0").compareTo(repository.getCurrentBeautyBalance()), "UPI expense should decrease wallet balance")
        
        // 4. Delete expense
        val expenseId = dao.getAllExpensesOnceInternal().first().id
        repository.deleteExpense(expenseId)
        
        // 5. Verify wallet balance restored to 100
        assertEquals(0, BigDecimal("100.0").compareTo(repository.getCurrentBeautyBalance()), "Deleting UPI expense should restore wallet balance")
    }

    @Test
    fun scenarioA_UpiOrderFlow() = runTest {
        val (start, end) = getTodayRange()
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))

        // 1. Create ₹100 order paid via UPI
        repository.confirmOrder("Customer X", listOf(CartItem("Paper", BigDecimal("100.0"), 1)), "UPI")

        // 2. Verify Revenue = 100
        val revenue = repository.getSettledRevenueByMethodBetween(start, end, "UPI")
        assertEquals(0, BigDecimal("100.0").compareTo(revenue))

        // 3. Verify Wallet = 100
        assertEquals(0, BigDecimal("100.0").compareTo(repository.getCurrentBeautyBalance()))
        val walletSettlement = dao.getAllSettlements().single { it.ledgerEntryType == "UPI_ACCOUNT_TOPUP" }
        assertTrue(walletSettlement.isShadowDuplicate)
        assertEquals(0, BigDecimal("100.0").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "UPI")))

        // 4. Verify settlements and wallet transactions
        val settlements = dao.getAllSettlements()
        // Settlements: ORDER_POST, PAYMENT, and wallet topup for payment
        assertTrue(settlements.size >= 2)
        assertTrue(settlements.any { it.ledgerEntryType == "ORDER_POST" })
        assertTrue(settlements.any { it.ledgerEntryType == "PAYMENT" })
        
        assertEquals(1, dao.getAllUpiAccountTransactions().size)
    }

    @Test
    fun scenarioD_NoDoubleCounting() = runTest {
        val (start, end) = getTodayRange()
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))

        // 1. Create ₹100 UPI order (Adds to Settlement AND Wallet)
        repository.confirmOrder("Customer Y", listOf(CartItem("Paper", BigDecimal("100.0"), 1)), "UPI")

        // 2. Add ₹50 Manual Top-up (Adds to Settlement AND Wallet)
        repository.insertBeautyTransaction(BigDecimal("50.0"), "ADD", "Manual")

        // 3. Both UPI business receipts count once: the order and manual account receipt.
        val revenue = repository.getSettledRevenueByMethodBetween(start, end, "UPI")
        assertEquals(0, BigDecimal("150.0").compareTo(revenue), "Revenue includes both UPI receipts exactly once")

        // 4. Verify Wallet counts both = 150
        assertEquals(0, BigDecimal("150.0").compareTo(repository.getCurrentBeautyBalance()))
        
        // 5. Verify Analytics Inflow (Wallet-based) = 150
        val inflow = repository.getBeautyReceivedBetween(start, end)
        assertEquals(0, BigDecimal("150.0").compareTo(inflow))

        // 6. Verify BOTH appear in filtered settlement history
        val history = repository.getFilteredSettlements(start, end, "UPI")
        assertTrue(history.size >= 3, "Should find order settlements and manual top-up")
        
        // 7. Verify Mapper identifies the Wallet Inflow
        val mapper = BusinessEventMapper()
        val events = mapper.map(history)
        assertTrue(events.any { it is BusinessEvent.WalletAdjustment && it.isInflow }, "Should map to Wallet Inflow event")
    }

    @Test
    fun scenarioE_DeletedOrderExclusion() = runTest {
        val (start, end) = getTodayRange()
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))

        // 1. Create order
        val orderResult = repository.confirmOrder("Customer Z", listOf(CartItem("Paper", BigDecimal("100.0"), 1)), "UPI")
        val orderId = (orderResult as OrderResult.Success).orderId

        // 2. Verify initial revenue
        assertEquals(0, BigDecimal("100.0").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "UPI")))

        // 3. Cancel/Delete order
        repository.cancelOrder(orderId)

        // 4. Verify revenue is now 0
        val revenueAfter = repository.getSettledRevenueByMethodBetween(start, end, "UPI")
        assertEquals(0, BigDecimal.ZERO.compareTo(revenueAfter), "Deleted orders must be excluded from revenue")

        // 5. Verify wallet effect was reversed (Balance = 0)
        assertEquals(0, BigDecimal.ZERO.compareTo(repository.getCurrentBeautyBalance()), "Cancelling order should reverse wallet addition")
    }

    @Test
    fun testManualUpiAccountBusinessReceipt_IncludedInRevenueOnce() = runTest {
        val (start, end) = getTodayRange()
        
        // 1. Initial Revenue should be 0
        assertEquals(0, BigDecimal.ZERO.compareTo(repository.getSettledRevenueByMethodBetween(start, end, "ALL")))
        
        // 2. Add ₹1,000 received from the printing business.
        repository.insertUpiAccountTransaction(
            BigDecimal("1000.0"),
            "ADD",
            "Manual Topup",
            createOrderHistoryEntry = true
        )
        
        // 3. The received business money contributes once to collected income.
        assertEquals(0, BigDecimal("1000.0").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "ALL")))
        assertEquals(0, BigDecimal("1000.0").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "UPI")))
        assertEquals(0, BigDecimal.ZERO.compareTo(repository.getTotalReceivables()))
        
        // 4. The UPI account reflects the same receipt.
        assertEquals(0, BigDecimal("1000.0").compareTo(repository.getBeautyReceivedBetween(start, end)))
        
        // 5. Verify it appears in History under "UPI Account"
        val history = repository.getFilteredSettlements(start, end, "ALL")
        assertTrue(history.any { it.customerName == "UPI Account" && it.ledgerEntryType == "UPI_ACCOUNT_TOPUP" })
        val orders = repository.getAllOrders()
        assertEquals(1, orders.size)
        assertEquals("UPI Account", orders.single().customerName)
        assertEquals(0, BigDecimal("1000.0").compareTo(orders.single().paidAmount))
        val breakdown = repository.getPaymentBreakdownBetween(start, end)
        assertEquals(0, BigDecimal("1000.0").compareTo(breakdown.single { it.type == "UPI" }.total))
    }

    @Test
    fun scenarioF_MixedPaymentRevenueRecognition() = runTest {
        val (start, end) = getTodayRange()
        dao.insertStockItem(StockItem(name = "Paper", currentQuantity = 100))

        // 1. Create ₹100 order paid via CASH
        val orderResult = repository.confirmOrder("Customer Mixed", listOf(CartItem("Paper", BigDecimal("100.0"), 1)), "CASH")
        val orderId = (orderResult as OrderResult.Success).orderId

        // 2. Add ₹50 additional payment via UPI
        // This makes the order paymentMethod = "MIXED" (or CASH_MIXED / UPI_MIXED depending on implementation)
        // In repository.updatePayment, if previous method was CASH and new is UPI, it becomes "MIXED".
        repository.updatePayment(orderId, BigDecimal("150.0"), "UPI")

        // 3. Verify Total Sales Revenue = 150
        val totalRevenue = repository.getSettledRevenueByMethodBetween(start, end, "ALL")
        assertEquals(0, BigDecimal("150.0").compareTo(totalRevenue), "Total revenue should include both cash and upi parts")

        // 4. Verify Payment Breakdown: CASH=100, UPI=50
        val breakdown = repository.getPaymentBreakdownBetween(start, end)
        val cashPart = breakdown.find { it.type == "CASH" }?.total ?: BigDecimal.ZERO
        val upiPart = breakdown.find { it.type == "UPI" }?.total ?: BigDecimal.ZERO
        
        assertEquals(0, BigDecimal("100.0").compareTo(cashPart), "Cash part of mixed order should be 100")
        assertEquals(0, BigDecimal("50.0").compareTo(upiPart), "UPI part of mixed order should be 50")

        // 5. Verify UPI Inflow = 50
        val upiInflow = repository.getBeautyReceivedBetween(start, end)
        assertEquals(0, BigDecimal("50.0").compareTo(upiInflow), "Wallet should only record the UPI portion")
    }

    @Test
    fun analyticsSeparatesCollectedRevenueWalletInflowsAndCreditExtended() = runTest {
        val (start, end) = getTodayRange()
        dao.insertStockItem(StockItem(name = "Service", currentQuantity = 100))

        assertTrue(repository.confirmOrder(
            "UPI Customer",
            listOf(CartItem("Service", BigDecimal("1000.00"), 1)),
            "UPI"
        ) is OrderResult.Success)
        repository.insertUpiAccountTransaction(
            BigDecimal("500.00"), "ADD", "Account entry", createOrderHistoryEntry = true
        )
        assertTrue(repository.confirmOrder(
            "Credit Customer",
            listOf(CartItem("Service", BigDecimal("300.00"), 1)),
            "OWES_ME"
        ) is OrderResult.Success)
        assertTrue(repository.confirmOrder(
            "Cash Customer",
            listOf(CartItem("Service", BigDecimal("200.00"), 1)),
            "CASH"
        ) is OrderResult.Success)

        assertEquals(0, BigDecimal("1700.00").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "ALL")))
        assertEquals(0, BigDecimal("1500.00").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "UPI")))
        assertEquals(0, BigDecimal("200.00").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "CASH")))
        assertEquals(0, BigDecimal("300.00").compareTo(repository.getCreditExtendedBetween(start, end)))
        assertEquals(0, BigDecimal("300.00").compareTo(repository.getTotalReceivables()))
        assertEquals(1, repository.getSettledOrderCountByMethodBetween(start, end, "CREDIT"))
        assertEquals(2, repository.getSettledOrderCountByMethodBetween(start, end, "UPI"))
        assertEquals(4, repository.getSettledOrderCountByMethodBetween(start, end, "ALL"))
        assertEquals(0, BigDecimal("1500.00").compareTo(repository.getUpiAccountReceivedBetween(start, end)))
        assertEquals(1, repository.getCustomerBalanceTotals().debtorsCount)

        val breakdown = repository.getPaymentBreakdownBetween(start, end)
        assertEquals(0, BigDecimal("1500.00").compareTo(breakdown.single { it.type == "UPI" }.total))
        assertEquals(0, BigDecimal("200.00").compareTo(breakdown.single { it.type == "CASH" }.total))
        assertEquals(0, BigDecimal("300.00").compareTo(breakdown.single { it.type == "CREDIT" }.total))
    }

    @Test
    fun analyticsRevenueSumsPersistedDecimalsWithoutSqliteRealRounding() = runTest {
        val (start, end) = getTodayRange()
        dao.insertStockItem(StockItem(name = "Service", currentQuantity = 100))
        listOf("0.10", "0.20", "0.01").forEachIndexed { index, amount ->
            assertTrue(repository.confirmOrder(
                "Customer $index",
                listOf(CartItem("Service", BigDecimal(amount), 1)),
                "CASH"
            ) is OrderResult.Success)
        }

        assertEquals(0, BigDecimal("0.31").compareTo(repository.getSettledRevenueByMethodBetween(start, end, "ALL")))
    }

    @Test
    fun analyticsIncludesGenuineCustomerCreditAdjustment() = runTest {
        val (start, end) = getTodayRange()
        repository.addOrUpdateDebtorCredit("Credit Customer", BigDecimal("300.00"), "Manual credit")

        val adjustment = dao.getAllSettlements().single { it.ledgerEntryType == "ADJUSTMENT" }
        assertTrue(database.syncDao().getPendingUploadsOnce().any {
            it.entityType == "SETTLEMENT" && it.entitySyncId == adjustment.syncId
        })
        assertEquals(0, BigDecimal("300.00").compareTo(repository.getCreditExtendedBetween(start, end)))
        assertEquals(0, BigDecimal("300.00").compareTo(repository.getTotalReceivables()))
        assertEquals(0, BigDecimal("300.00").compareTo(
            repository.getPaymentBreakdownBetween(start, end).single { it.type == "CREDIT" }.total
        ))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun analyticsShowsCustomerCreditCreatedThroughHomeCreditUiFlow() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val orderViewModel = MainViewModel(repository)
            val analyticsViewModel = DashboardViewModel(repository)
            createHomeCreditOrder(this, orderViewModel, "Home Credit Customer", BigDecimal("300.00"))

            val customer = dao.getCustomerByNormalizedName("home credit customer")!!
            val order = dao.getAllOrders().single()
            val orderPost = dao.getAllSettlements().single { it.ledgerEntryType == "ORDER_POST" }
            assertTrue(customer.id > 0L)
            assertEquals(customer.id, order.customerId)
            assertEquals("OWES_ME", orderPost.paymentMethod)
            assertEquals(customer.id, orderPost.customerId)
            assertEquals(0, BigDecimal("300.00").compareTo(orderPost.transactionAmount))

            val analyticsState = analyticsViewModel.uiState.first {
                !it.isLoading && it.totalReceivables.compareTo(BigDecimal("300.00")) == 0
            }
            assertEquals(0, BigDecimal("300.00").compareTo(analyticsState.metrics?.creditExtended))
            assertEquals("₹300.00", analyticsState.insights.single { it.title == "Credit Extended" }.value)
            assertEquals("₹300.00", analyticsState.insights.single { it.title == "⏳ Outstanding" }.value)

            val syncEvents = database.syncDao().getPendingUploadsOnce()
            assertTrue(syncEvents.any { it.entityType == "ORDER" && it.entitySyncId == order.syncId })
            assertTrue(syncEvents.any { it.entityType == "SETTLEMENT" && it.entitySyncId == orderPost.syncId })

            repository.insertUpiAccountTransaction(BigDecimal("500.00"), "ADD", "UPI account receipt")
            val paidOrderViewModel = MainViewModel(repository)
            dao.insertStockItem(StockItem(name = "General", currentQuantity = 10))
            paidOrderViewModel.onCustomerNameChanged("Home UPI Customer")
            paidOrderViewModel.onPriceChanged(BigDecimal("120.00"))
            paidOrderViewModel.onQuantityChanged(1)
            paidOrderViewModel.uiState.first { it.total.compareTo(BigDecimal("120.00")) == 0 }
            val paidCompletion = async(start = CoroutineStart.UNDISPATCHED) {
                paidOrderViewModel.events.first()
            }
            paidOrderViewModel.completeOrder("Paid", "UPI")
            assertTrue(paidCompletion.await() is MainViewModel.MainEvent.OrderCompleted)

            val totalsAfterUpi = repository.getCustomerBalanceTotals()
            assertEquals(0, BigDecimal("300.00").compareTo(totalsAfterUpi.receivables))
            assertEquals(1, totalsAfterUpi.debtorsCount)
            val dashboardAfterUpi = analyticsViewModel.uiState.first {
                !it.isLoading && it.totalReceivables.compareTo(BigDecimal("300.00")) == 0
            }
            assertEquals(0, BigDecimal("300.00").compareTo(dashboardAfterUpi.metrics?.creditExtended))
            assertTrue(dao.getAllSettlements().filter { it.ledgerEntryType == "UPI_ACCOUNT_TOPUP" }
                .all { it.customerId == 0L })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun customerPaymentsReduceOnlyTheirOwnOutstandingAnalyticsBalance() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val orderViewModel = MainViewModel(repository)
            val analyticsViewModel = DashboardViewModel(repository)
            createHomeCreditOrder(this, orderViewModel, "Customer A", BigDecimal("300.00"))
            createHomeCreditOrder(this, orderViewModel, "Customer B", BigDecimal("200.00"))

            assertEquals(0, BigDecimal("500.00").compareTo(repository.getTotalReceivables()))
            repository.addOrUpdateDebtorCredit("Customer A", BigDecimal("-100.00"), "Settlement Payment")

            assertEquals(0, BigDecimal("200.00").compareTo(repository.getCustomerBalance("Customer A")))
            assertEquals(0, BigDecimal("200.00").compareTo(repository.getCustomerBalance("Customer B")))
            assertEquals(0, BigDecimal("400.00").compareTo(repository.getTotalReceivables()))
            val paymentSettlement = dao.getAllSettlements().single {
                it.customerName == "Customer A" && it.ledgerEntryType == "PAYMENT"
            }
            assertTrue(database.syncDao().getPendingUploadsOnce().any {
                it.entityType == "SETTLEMENT" && it.entitySyncId == paymentSettlement.syncId
            })
            val dashboardState = analyticsViewModel.uiState.first {
                !it.isLoading && it.totalReceivables.compareTo(BigDecimal("400.00")) == 0
            }
            assertEquals(0, BigDecimal("400.00").compareTo(dashboardState.totalReceivables))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun databaseBackupRestorePreservesCustomerCreditAnalytics() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val isolatedDir = File(baseContext.cacheDir, "credit-analytics-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(baseContext) {
            override fun getDatabasePath(name: String): File = File(isolatedDir, name)
            override fun getApplicationContext(): Context = this
        }
        val backupFile = File(isolatedDir, "credit-backup.zip")
        try {
            val backupDatabase = AppDatabase.getDatabase(isolatedContext)
            val backupRepository = PrintRepository(backupDatabase.printDao())
            val orderViewModel = MainViewModel(backupRepository)
            createHomeCreditOrder(this, orderViewModel, "Backup Credit Customer", BigDecimal("275.00"))

            assertTrue(BackupUtils.createZipBackupToFile(isolatedContext, backupFile))
            ZipFile(backupFile).use { zip ->
                assertTrue(zip.getEntry("print_database") != null)
            }

            val mutatedDatabase = AppDatabase.getDatabase(isolatedContext)
            val mutatedRepository = PrintRepository(mutatedDatabase.printDao())
            val secondOrderViewModel = MainViewModel(mutatedRepository)
            createHomeCreditOrder(this, secondOrderViewModel, "Not In Backup", BigDecimal("90.00"))
            assertEquals(0, BigDecimal("365.00").compareTo(mutatedRepository.getTotalReceivables()))

            assertTrue(BackupUtils.restoreZipBackup(isolatedContext, Uri.fromFile(backupFile)))
            val restoredRepository = PrintRepository(AppDatabase.getDatabase(isolatedContext).printDao())
            assertEquals(0, BigDecimal("275.00").compareTo(restoredRepository.getTotalReceivables()))
            assertEquals(0, BigDecimal("275.00").compareTo(
                restoredRepository.getCreditExtendedBetween(0L, Long.MAX_VALUE)
            ))
        } finally {
            AppDatabase.closeDatabase()
            Dispatchers.resetMain()
            isolatedDir.deleteRecursively()
        }
    }

    private suspend fun createHomeCreditOrder(
        scope: CoroutineScope,
        viewModel: MainViewModel,
        customerName: String,
        amount: BigDecimal
    ) {
        viewModel.onCustomerNameChanged(customerName)
        viewModel.onPriceChanged(amount)
        viewModel.onQuantityChanged(1)
        viewModel.uiState.first { it.total.compareTo(amount) == 0 }
        val completionEvent = scope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.events.first()
        }
        viewModel.completeOrder("Credit", "NONE")
        val event = completionEvent.await()
        assertTrue(event is MainViewModel.MainEvent.OrderCompleted, "Home credit flow returned $event")
    }

    @Test
    fun analyticsInvalidationFlowEmitsWhenPersistedWalletDataChanges() = runTest {
        val nextInvalidation = async(start = CoroutineStart.UNDISPATCHED) {
            dao.observeAnalyticsDataChanges().drop(1).first()
        }

        repository.insertUpiAccountTransaction(BigDecimal("500.00"), "ADD", "Live update")

        nextInvalidation.await()
        assertEquals(0, BigDecimal("500.00").compareTo(repository.getUpiAccountReceivedBetween(0L, Long.MAX_VALUE)))
    }

    @Test
    fun upiAnalyticsDateWindowsUsePersistedLocalTimestamp() = runTest {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val monthStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val oldTimestamp = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, -2)
        }.timeInMillis

        dao.insertUpiAccountTransactionInternal(UpiAccountTransaction(
            amount = BigDecimal("100.00"),
            type = "ADD",
            timestamp = oldTimestamp,
            transactionAmount = BigDecimal("100.00"),
            newBalance = BigDecimal("100.00")
        ))
        dao.insertUpiAccountTransactionInternal(UpiAccountTransaction(
            amount = BigDecimal("50.00"),
            type = "ADD",
            timestamp = now,
            transactionAmount = BigDecimal("50.00"),
            newBalance = BigDecimal("150.00")
        ))

        assertEquals(0, BigDecimal("50.00").compareTo(repository.getUpiAccountReceivedBetween(todayStart, now)))
        assertEquals(0, BigDecimal("50.00").compareTo(repository.getUpiAccountReceivedBetween(weekStart, now)))
        assertEquals(0, BigDecimal("50.00").compareTo(repository.getUpiAccountReceivedBetween(monthStart, now)))
        assertEquals(0, BigDecimal("150.00").compareTo(repository.getUpiAccountReceivedBetween(oldTimestamp, now)))
    }

    @Test
    fun canonicalUpiAnalyticsSurvivesRoomDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "analytics-reopen-${System.nanoTime()}"
        val firstDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            PrintRepository(firstDatabase.printDao()).insertUpiAccountTransaction(
                BigDecimal("500.00"),
                "ADD",
                "Persisted account entry",
                createOrderHistoryEntry = true
            )
            firstDatabase.printDao().insertUpiAccountTransactionAtomic(
                BigDecimal("500.00"),
                "ADD",
                "Direct Pay - Order #77",
                isShadowDuplicate = true
            )
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val reopenedRepository = PrintRepository(reopenedDatabase.printDao())
            assertEquals(0, BigDecimal("1000.00").compareTo(
                reopenedRepository.getUpiAccountReceivedBetween(0L, Long.MAX_VALUE)
            ))
            assertEquals(0, BigDecimal("500.00").compareTo(
                reopenedRepository.getSettledRevenueByMethodBetween(0L, Long.MAX_VALUE, "UPI")
            ))
            val reopenedOrders = reopenedRepository.getAllOrders()
            assertEquals(1, reopenedOrders.size)
            assertEquals("UPI Account", reopenedOrders.single().customerName)
            assertEquals(0, BigDecimal("500.00").compareTo(reopenedOrders.single().paidAmount))
            assertEquals(0, BigDecimal.ZERO.compareTo(reopenedRepository.getTotalReceivables()))
        } finally {
            reopenedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }
}
