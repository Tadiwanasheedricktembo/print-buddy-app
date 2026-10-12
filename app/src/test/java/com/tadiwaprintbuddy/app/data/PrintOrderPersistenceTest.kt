package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.BackupUtils
import com.tadiwaprintbuddy.app.CartItem
import com.tadiwaprintbuddy.app.PrintLineType
import com.tadiwaprintbuddy.app.PrintOrderLine
import com.tadiwaprintbuddy.app.TestApplication
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class PrintOrderPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: AppDatabase? = null

    @After
    fun cleanUp() {
        database?.close()
        AppDatabase.closeDatabase()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun completedPrintOrdersSurviveRecreationAndRepeatedBackupRestore() = runTest {
        AppDatabase.closeDatabase()
        context.deleteDatabase(DATABASE_NAME)
        database = openDatabase()
        val repository = PrintRepository(database!!.printDao())
        val copies = 2
        val bwLine = PrintOrderLine(PrintLineType.BLACK_AND_WHITE, "Document A", 10, BigDecimal("2.00"))
        val colorLine = PrintOrderLine(PrintLineType.COLOR, "Document B", 2, BigDecimal("7.00"))
        val otherLine = PrintOrderLine(PrintLineType.OTHER, "Binding", 1, BigDecimal("12.50"))
        val firstResult = repository.confirmOrder(
            "Print Customer",
            listOf(
                CartItem(bwLine.toStoredServiceName(copies), bwLine.unitPrice, bwLine.quantity * copies),
                CartItem(colorLine.toStoredServiceName(copies), colorLine.unitPrice, colorLine.quantity * copies),
                CartItem(otherLine.toStoredServiceName(copies), otherLine.unitPrice, copies)
            ),
            paymentMethod = "CASH",
            explicitPrintCount = 24
        ) as OrderResult.Success
        val secondResult = repository.confirmOrder(
            "Print Customer",
            listOf(CartItem("General", BigDecimal("5.00"), 1)),
            paymentMethod = "CASH"
        ) as OrderResult.Success

        val firstOrder = database!!.printDao().getOrderById(firstResult.orderId)!!
        val firstItems = database!!.printDao().getItemsForOrder(firstResult.orderId)
        assertEquals(24, firstOrder.printCount)
        assertEquals(0, BigDecimal("93.00").compareTo(firstOrder.totalAmount))
        assertEquals(20, firstItems.first { PrintOrderLine.typeFromStoredServiceName(it.serviceName) == PrintLineType.BLACK_AND_WHITE }.quantity)
        assertEquals(4, firstItems.first { PrintOrderLine.typeFromStoredServiceName(it.serviceName) == PrintLineType.COLOR }.quantity)
        assertTrue(firstItems.all { PrintOrderLine.copiesFromStoredServiceName(it.serviceName) == copies })

        val pendingItems = database!!.syncDao().getPendingUploadsOnce()
            .filter { it.entityType == "ORDER_ITEM" }
        assertEquals(4, pendingItems.size)

        database!!.close()
        database = openDatabase()
        val reopenedDao = database!!.printDao()
        val reopenedFirst = reopenedDao.getOrderById(firstResult.orderId)!!
        val reopenedItems = reopenedDao.getItemsForOrder(firstResult.orderId)
        assertEquals(firstOrder.date, reopenedFirst.date)
        assertEquals(firstOrder.totalAmount, reopenedFirst.totalAmount)
        assertEquals(firstOrder.printCount, reopenedFirst.printCount)
        assertEquals(firstItems.map { it.serviceName to it.quantity }, reopenedItems.map { it.serviceName to it.quantity })
        assertEquals(2, reopenedDao.getAllOrders().size)
        assertEquals("General", reopenedDao.getItemsForOrder(secondResult.orderId).single().serviceName)

        database!!.close()
        database = null
        val backupFile = File(context.cacheDir, "print-order-history-test.zip")
        assertTrue(BackupUtils.createZipBackupToFile(context, backupFile))
        context.deleteDatabase(DATABASE_NAME)
        val backupUri = Uri.fromFile(backupFile)
        assertTrue(BackupUtils.restoreZipBackup(context, backupUri))
        database = openDatabase()
        assertEquals(2, database!!.printDao().getAllOrders().size)
        database!!.close()
        database = null

        assertTrue(BackupUtils.restoreZipBackup(context, backupUri))
        database = openDatabase()
        assertEquals(2, database!!.printDao().getAllOrders().size)
        assertEquals("93.00", database!!.printDao().getOrderById(firstResult.orderId)!!.totalAmount.toPlainString())
        backupFile.delete()
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        DATABASE_NAME
    ).allowMainThreadQueries().build()

    companion object {
        private const val DATABASE_NAME = "print_database"
    }
}