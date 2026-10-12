package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.BackupUtils
import com.tadiwaprintbuddy.app.CartItem
import com.tadiwaprintbuddy.app.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class PrintCountIntegrityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: PrintDao
    private lateinit var repository: PrintRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
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
    fun `test basic credit transaction with print count`() = runBlocking {
        repository.addOrUpdateDebtorCredit("Customer A", BigDecimal("500.00"), "Initial print job", printCount = 100)

        val debtorList = repository.getDebtorCreditList()
        assertEquals(1, debtorList.size)
        val debtor = debtorList.first()
        assertEquals("Customer A", debtor.customerName)
        assertEquals(0, BigDecimal("500.00").compareTo(debtor.amount))
        assertEquals(100, debtor.printCount)

        val totalPrints = repository.getTotalPrintsBetween(0, Long.MAX_VALUE)
        assertEquals(100, totalPrints)
    }

    @Test
    fun `test multiple transactions accumulate print counts`() = runBlocking {
        repository.addOrUpdateDebtorCredit("Customer B", BigDecimal("300.00"), "Job 1", printCount = 100)
        repository.addOrUpdateDebtorCredit("Customer B", BigDecimal("150.00"), "Job 2", printCount = 50)

        val customerPrints = repository.getCustomerTotalPrints(dao.getCustomerByNormalizedName("customer b")!!.id)
        assertEquals(150, customerPrints)

        val businessPrints = repository.getTotalPrintsBetween(0, Long.MAX_VALUE)
        assertEquals(150, businessPrints)
    }

    @Test
    fun `test payment against debt does not create additional prints`() = runBlocking {
        repository.addOrUpdateDebtorCredit("Customer C", BigDecimal("500.00"), "Initial job", printCount = 100)

        val customerId = dao.getCustomerByNormalizedName("customer c")!!.id
        assertEquals(100, repository.getCustomerTotalPrints(customerId))

        // Pay 200 towards debt
        repository.applyPaymentToCustomerId(customerId, BigDecimal("200.00"), "CASH")

        // Customer balance decreases to 300, but print count MUST remain 100
        assertEquals(0, BigDecimal("300.00").compareTo(repository.getCustomerBalanceById(customerId)))
        assertEquals(100, repository.getCustomerTotalPrints(customerId))
        assertEquals(100, repository.getTotalPrintsBetween(0, Long.MAX_VALUE))
    }

    @Test
    fun `test order creation with items derives print count from item quantity`() = runBlocking {
        val cartItems = listOf(
            CartItem("A4 Glossy Print", BigDecimal("10.00"), 30),
            CartItem("A3 Poster", BigDecimal("50.00"), 20)
        )
        val result = repository.confirmOrder("Customer D", cartItems, "OWES_ME")
        assertTrue(result is OrderResult.Success)

        val orders = repository.getAllOrders()
        assertEquals(1, orders.size)
        assertEquals(50, orders.first().printCount)

        val customerId = dao.getCustomerByNormalizedName("customer d")!!.id
        assertEquals(50, repository.getCustomerTotalPrints(customerId))
    }

    @Test
    fun `test deleting an order removes its prints from aggregates`() = runBlocking {
        val cartItems = listOf(CartItem("Banner", BigDecimal("100.00"), 80))
        val result = repository.confirmOrder("Customer E", cartItems, "OWES_ME")
        val orderId = (result as OrderResult.Success).orderId

        assertEquals(80, repository.getTotalPrintsBetween(0, Long.MAX_VALUE))

        dao.deleteOrderAndItems(dao.getOrderById(orderId)!!)

        assertEquals(0, repository.getTotalPrintsBetween(0, Long.MAX_VALUE))
    }

    @Test
    fun `test multiple customers keep separate print counts`() = runBlocking {
        repository.addOrUpdateDebtorCredit("Customer Alpha", BigDecimal("100.00"), "Job Alpha", printCount = 100)
        repository.addOrUpdateDebtorCredit("Customer Beta", BigDecimal("200.00"), "Job Beta", printCount = 50)

        val alphaId = dao.getCustomerByNormalizedName("customer alpha")!!.id
        val betaId = dao.getCustomerByNormalizedName("customer beta")!!.id

        assertEquals(100, repository.getCustomerTotalPrints(alphaId))
        assertEquals(50, repository.getCustomerTotalPrints(betaId))
        assertEquals(150, repository.getTotalPrintsBetween(0, Long.MAX_VALUE))
    }

    @Test
    fun `test backup and restore preserves print counts`() = runBlocking {
        val diskDbPath = context.getDatabasePath("print_database")
        diskDbPath.parentFile?.mkdirs()

        val diskDb = Room.databaseBuilder(context, AppDatabase::class.java, "print_database")
            .allowMainThreadQueries()
            .build()
        val diskDao = diskDb.printDao()
        val diskRepo = PrintRepository(diskDao)

        diskRepo.addOrUpdateDebtorCredit("Backup Customer", BigDecimal("750.00"), "Large print order", printCount = 250)
        val customerId = diskDao.getCustomerByNormalizedName("backup customer")!!.id
        assertEquals(250, diskRepo.getCustomerTotalPrints(customerId))

        diskDb.close()

        val backupFile = File(context.cacheDir, "print_count_backup.zip")
        assertTrue(BackupUtils.createZipBackupToFile(context, backupFile))

        context.getDatabasePath("print_database").delete()
        context.getDatabasePath("print_database-shm").delete()
        context.getDatabasePath("print_database-wal").delete()

        assertTrue(BackupUtils.restoreZipBackup(context, Uri.fromFile(backupFile)))

        val restoredDb = Room.databaseBuilder(context, AppDatabase::class.java, "print_database")
            .allowMainThreadQueries()
            .build()
        val restoredDao = restoredDb.printDao()
        val restoredRepo = PrintRepository(restoredDao)

        val restoredCustomer = restoredDao.getCustomerByNormalizedName("backup customer")
        assertNotNull(restoredCustomer)
        assertEquals(250, restoredRepo.getCustomerTotalPrints(restoredCustomer!!.id))
        assertEquals(250, restoredRepo.getTotalPrintsBetween(0, Long.MAX_VALUE))

        val debtorList = restoredRepo.getDebtorCreditList()
        assertEquals(1, debtorList.size)
        assertEquals(250, debtorList.first().printCount)

        restoredDb.close()
    }

    @Test
    fun `test legacy records default to zero print count without fabricating quantities`() = runBlocking {
        val legacyOrder = Order(
            totalAmount = BigDecimal("500.00"),
            date = System.currentTimeMillis(),
            customerName = "Legacy Customer",
            paidAmount = BigDecimal.ZERO,
            paymentMethod = "NONE",
            orderStatus = "ACTIVE",
            printCount = 0
        )
        dao.insertOrder(legacyOrder)

        val orders = repository.getAllOrders()
        assertEquals(1, orders.size)
        assertEquals(0, orders.first().printCount)
    }
}
