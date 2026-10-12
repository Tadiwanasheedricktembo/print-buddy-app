package com.tadiwaprintbuddy.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.HomeOrderDraft
import com.tadiwaprintbuddy.app.data.PrintRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class HomeOrderDraftTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        AppDatabase.closeDatabase()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
        context.deleteDatabase(DATABASE_NAME)
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        Dispatchers.resetMain()
    }

    @Test
    fun quickOrderDebouncesToRoomRestoresAfterBackupAndCompletesOnlyOnce() = runBlocking<Unit> {
        val database = AppDatabase.getDatabase(context)
        val repository = PrintRepository(database.printDao())
        val viewModel = MainViewModel(repository)
        viewModel.uiState.first { it.isDraftLoaded }

        viewModel.onCustomerNameChanged("Draft Customer")
        viewModel.onQuantityInputChanged("2")
        viewModel.onPriceInputChanged("50")
        viewModel.onPaymentMethodChanged("UPI")
        delay(400)

        val firstDraft = repository.getHomeOrderDraft()!!
        assertEquals("Draft Customer", firstDraft.customerName)
        assertEquals("2", firstDraft.quantityInput)
        assertEquals("50", firstDraft.priceInput)
        assertEquals("UPI", firstDraft.paymentMethod)
        assertTrue(database.printDao().getAllOrders().isEmpty())
        assertTrue(database.printDao().getAllSettlements().isEmpty())
        assertTrue(database.printDao().getAllUpiAccountTransactions().isEmpty())
        assertTrue(database.syncDao().getPendingUploadsOnce().isEmpty())

        val backup = File(context.cacheDir, "quick-order-draft-test.zip")
        assertTrue(BackupUtils.createZipBackupToFile(context, backup))
        context.deleteDatabase(DATABASE_NAME)
        assertTrue(BackupUtils.restoreZipBackup(context, Uri.fromFile(backup)))

        val restoredDatabase = AppDatabase.getDatabase(context)
        val restoredRepository = PrintRepository(restoredDatabase.printDao())
        val restoredViewModel = MainViewModel(restoredRepository)
        val restoredState = withTimeout(3_000) {
            restoredViewModel.uiState.first { it.isDraftLoaded && it.total > BigDecimal.ZERO }
        }
        assertEquals("Draft Customer", restoredState.customerName)
        assertEquals("2", restoredState.quantityInput)
        assertEquals("50", restoredState.priceInput)
        assertEquals("UPI", restoredState.paymentMethod)
        assertTrue(restoredDatabase.printDao().getAllOrders().isEmpty())

        restoredViewModel.onPriceInputChanged("75")
        restoredViewModel.onPriceInputChanged("80")
        delay(400)
        val latestDraft = restoredRepository.getHomeOrderDraft()!!
        assertEquals(firstDraft.id, latestDraft.id)
        assertEquals("80", latestDraft.priceInput)
        assertTrue(restoredDatabase.printDao().getAllOrders().isEmpty())
        assertTrue(restoredDatabase.printDao().getAllSettlements().isEmpty())
        assertTrue(restoredDatabase.syncDao().getPendingUploadsOnce().isEmpty())

        val completion = async(start = CoroutineStart.UNDISPATCHED) { restoredViewModel.events.first() }
        restoredViewModel.completeOrder("Paid", "UPI")
        restoredViewModel.completeOrder("Paid", "UPI")
        assertEquals(MainViewModel.MainEvent.OrderCompleted, completion.await())
        val savedOrders = restoredDatabase.printDao().getAllOrders()
        assertEquals(1, savedOrders.size)
        assertEquals(0, BigDecimal("160").compareTo(savedOrders.single().totalAmount))
        assertEquals("UPI", savedOrders.single().paymentMethod)
        assertNull(restoredRepository.getHomeOrderDraft())
        backup.delete()
    }

    @Test
    fun migrationAddsCashDefaultToExistingDraft() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(MIGRATION_DATABASE_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(43) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE `home_order_draft` (
                            `id` INTEGER NOT NULL,
                            `customerName` TEXT NOT NULL,
                            `quantityInput` TEXT NOT NULL,
                            `priceInput` TEXT NOT NULL,
                            `receivedAmount` TEXT,
                            `calculatorItemsJson` TEXT NOT NULL,
                            `calculatorTotalPages` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )""".trimIndent()
                    )
                    db.execSQL("INSERT INTO `home_order_draft` VALUES (1, 'Existing', '1', '10', NULL, '[]', 0, 123)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_43_44.migrate(db)
        val cursor = db.query("SELECT paymentMethod FROM home_order_draft WHERE id = 1")
        try {
            assertTrue(cursor.moveToFirst())
            assertEquals("CASH", cursor.getString(0))
        } finally {
            cursor.close()
            helper.close()
        }
    }

    @Test
    fun quickOrderAmountFieldSavesAndRestoresAfterActivityRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = PrintRepository(AppDatabase.getDatabase(context).printDao())
        repository.saveHomeOrderDraft(HomeOrderDraft(priceInput = "10"))
        val firstActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val firstViewModel = ViewModelProvider(firstActivity)[MainViewModel::class.java]
        awaitMainCondition("initial draft state") {
            firstViewModel.uiState.value.isDraftLoaded && firstViewModel.uiState.value.priceInput == "10"
        }
        val firstPrice = firstActivity.findViewById<EditText>(R.id.editPrice)
        awaitMainCondition("initial draft text") {
            firstPrice.text.toString() == "10"
        }
        shadowOf(Looper.getMainLooper()).idle()
        firstActivity.runOnUiThread { firstPrice.setText("50") }
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        awaitMainCondition("updated price state") {
            firstViewModel.uiState.value.priceInput == "50"
        }
        firstViewModel.flushDraft()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("50", repository.getHomeOrderDraft()?.priceInput)
        assertTrue(repository.getHomeOrderDraft() != null)
        assertTrue(AppDatabase.getDatabase(context).printDao().getAllOrders().isEmpty())

        firstActivity.runOnUiThread { firstActivity.finish() }
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        AppDatabase.closeDatabase()
        val reopenedActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val reopenedViewModel = ViewModelProvider(reopenedActivity)[MainViewModel::class.java]
        runCurrent()
        awaitMainCondition("restored draft state") {
            reopenedViewModel.uiState.value.isDraftLoaded && reopenedViewModel.uiState.value.priceInput == "50"
        }
        val reopenedPrice = reopenedActivity.findViewById<EditText>(R.id.editPrice)
        awaitMainCondition("restored draft text") {
            reopenedPrice.text.toString() == "50"
        }
        assertEquals(0, AppDatabase.getDatabase(context).printDao().getAllOrders().size)
        reopenedActivity.runOnUiThread { reopenedActivity.finish() }
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private suspend fun TestScope.awaitMainCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for $description")
            }
            kotlinx.coroutines.withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    @Test
    fun quickOrderUpiButtonRecordsOnePaymentAndClearsDraft() = runBlocking<Unit> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
        viewModel.uiState.first { it.isDraftLoaded }

        activity.runOnUiThread {
            activity.findViewById<android.widget.AutoCompleteTextView>(R.id.editCustomerName)
                .setText("Draft Customer", false)
            activity.findViewById<EditText>(R.id.editPrice).setText("50")
        }
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.uiState.first {
            it.customerName == "Draft Customer" && it.total.compareTo(BigDecimal("50")) == 0
        }
        val completion = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
        activity.runOnUiThread {
            activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUpi).performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MainViewModel.MainEvent.OrderCompleted, completion.await())

        val dao = AppDatabase.getDatabase(context).printDao()
        val orders = dao.getAllOrders()
        assertEquals(1, orders.size)
        assertEquals(0, BigDecimal("50").compareTo(orders.single().totalAmount))
        assertEquals(0, BigDecimal("50").compareTo(orders.single().paidAmount))
        assertEquals("PAID", orders.single().paymentStatus)
        assertEquals("UPI", orders.single().paymentMethod)
        assertEquals(1, orders.single().printCount)
        assertEquals(1, dao.getAllUpiAccountTransactions().size)
        assertEquals(0, BigDecimal("50").compareTo(dao.getAllUpiAccountTransactions().single().amount))
        assertEquals(1, dao.getAllSettlements().count { it.ledgerEntryType == "PAYMENT" && it.paymentMethod == "UPI" })
        val repository = PrintRepository(dao)
        assertNull(repository.getHomeOrderDraft())
        activity.runOnUiThread { activity.finish() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun quickOrderCashButtonSavesWhenCashIsAlreadySelected() = runBlocking<Unit> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
        viewModel.uiState.first { it.isDraftLoaded }

        activity.runOnUiThread {
            activity.findViewById<android.widget.AutoCompleteTextView>(R.id.editCustomerName)
                .setText("Cash Customer", false)
            activity.findViewById<EditText>(R.id.editPrice).setText("25")
        }
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.uiState.first {
            it.customerName == "Cash Customer" && it.total.compareTo(BigDecimal("25")) == 0
        }

        val completion = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
        activity.runOnUiThread {
            activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCash).performClick()
            activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCash).performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MainViewModel.MainEvent.OrderCompleted, completion.await())

        val dao = AppDatabase.getDatabase(context).printDao()
        val orders = dao.getAllOrders()
        assertEquals(1, orders.size)
        assertEquals(0, BigDecimal("25").compareTo(orders.single().totalAmount))
        assertEquals(0, BigDecimal("25").compareTo(orders.single().paidAmount))
        assertEquals("PAID", orders.single().paymentStatus)
        assertEquals("CASH", orders.single().paymentMethod)
        assertEquals(1, orders.single().printCount)
        assertEquals(0, BigDecimal.ZERO.compareTo(dao.getAuthoritativeCustomerBalance(orders.single().customerId)))
        assertEquals(2, dao.getSettlementsForOrder(orders.single().id).size)
        assertEquals(1, dao.getAllSettlements().count { it.ledgerEntryType == "ORDER_POST" })
        assertEquals(1, dao.getAllSettlements().count { it.ledgerEntryType == "PAYMENT" })
        activity.runOnUiThread { activity.finish() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        private const val DATABASE_NAME = "print_database"
        private const val MIGRATION_DATABASE_NAME = "home-order-draft-migration-test"
    }
}
