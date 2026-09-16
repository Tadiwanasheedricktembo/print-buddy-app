package com.tadiwaprintbuddy.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tadiwaprintbuddy.app.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class MigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate34To35() {
        // 1. Create v34 database
        var db = helper.createDatabase(TEST_DB, 34)

        // 2. Insert test data with REAL values (Simulation of v34)
        db.execSQL("""
            INSERT INTO orders (
                totalAmount, date, customerName, paidAmount, paymentMethod, 
                customerId, previousBalance, transactionAmount, newBalance, 
                paymentStatus, orderStatus, receivedAmount, customerSyncId, 
                syncId, updatedAt, syncStatus
            ) VALUES (
                123.45, 1000, 'Test Customer', 100.0, 'CASH', 
                1, 0.0, 123.45, 23.45, 
                'PARTIALLY_PAID', 'ACTIVE', 150.0, 'cust-sync-1', 
                'order-sync-1', 1000, 'SYNCED'
            )
        """)

        db.execSQL("""
            INSERT INTO debtor_credits (customerId, customerName, amount, lastUpdated)
            VALUES (1, 'Test Customer', 23.45, 1000)
        """)

        db.close()

        // 3. Migrate to v35
        db = helper.runMigrationsAndValidate(TEST_DB, 35, true, AppDatabase.MIGRATION_34_35)

        // 4. Verify data preservation and type conversion
        val cursor = db.query("SELECT * FROM orders WHERE syncId = 'order-sync-1'")
        cursor.moveToFirst()
        
        // In v35, these should be TEXT
        val totalAmount = cursor.getString(cursor.getColumnIndex("totalAmount"))
        val paidAmount = cursor.getString(cursor.getColumnIndex("paidAmount"))
        val receivedAmount = cursor.getString(cursor.getColumnIndex("receivedAmount"))
        
        // Note: SQLite REAL to TEXT conversion might vary (e.g. "123.45" or "123.45000000000000284")
        // But we want to ensure it's at least a valid representation of the number.
        assertEquals(123.45, totalAmount.toDouble(), 0.001)
        assertEquals(100.0, paidAmount.toDouble(), 0.001)
        assertEquals(150.0, receivedAmount.toDouble(), 0.001)
        
        cursor.close()

        val debtorCursor = db.query("SELECT * FROM debtor_credits WHERE customerId = 1")
        debtorCursor.moveToFirst()
        val debtorAmount = debtorCursor.getString(debtorCursor.getColumnIndex("amount"))
        assertEquals(23.45, debtorAmount.toDouble(), 0.001)
        debtorCursor.close()
    }
}
