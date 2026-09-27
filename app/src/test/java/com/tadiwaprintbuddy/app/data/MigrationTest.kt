package com.tadiwaprintbuddy.app.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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

    private fun createWalletMigrationDatabase(name: String, includeCanonicalTable: Boolean): SupportSQLiteOpenHelper {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(38) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE `beauty_transactions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `amount` TEXT NOT NULL, `type` TEXT NOT NULL, `note` TEXT,
                            `timestamp` INTEGER NOT NULL, `previousBalance` TEXT NOT NULL,
                            `transactionAmount` TEXT NOT NULL, `newBalance` TEXT NOT NULL,
                            `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                            `deletedAt` INTEGER, `syncStatus` TEXT NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE `settlement_history` (
                            `id` INTEGER PRIMARY KEY NOT NULL,
                            `customerId` INTEGER NOT NULL,
                            `settledAmount` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `ledgerEntryType` TEXT NOT NULL,
                            `paymentMethod` TEXT,
                            `note` TEXT NOT NULL,
                            `originId` INTEGER,
                            `originSyncId` TEXT,
                            `isShadowDuplicate` INTEGER NOT NULL DEFAULT 0,
                            `deletedAt` INTEGER
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE `orders` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `totalAmount` TEXT NOT NULL, `date` INTEGER NOT NULL,
                            `customerName` TEXT NOT NULL, `paidAmount` TEXT NOT NULL,
                            `paymentMethod` TEXT NOT NULL, `customerId` INTEGER NOT NULL,
                            `previousBalance` TEXT NOT NULL, `transactionAmount` TEXT NOT NULL,
                            `newBalance` TEXT NOT NULL, `paymentStatus` TEXT NOT NULL,
                            `orderStatus` TEXT NOT NULL, `receivedAmount` TEXT,
                            `customerSyncId` TEXT NOT NULL, `syncId` TEXT NOT NULL,
                            `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                            `syncStatus` TEXT NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE `sync_outbox` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `entityType` TEXT NOT NULL, `entitySyncId` TEXT NOT NULL,
                            `operation` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                            `attemptCount` INTEGER NOT NULL, `lastError` TEXT,
                            `idempotencyKey` TEXT NOT NULL, `status` TEXT NOT NULL
                        )
                    """.trimIndent())
                    if (includeCanonicalTable) {
                        db.execSQL("""
                            CREATE TABLE `upi_account_transactions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `amount` TEXT NOT NULL, `type` TEXT NOT NULL, `note` TEXT,
                                `timestamp` INTEGER NOT NULL, `previousBalance` TEXT NOT NULL,
                                `transactionAmount` TEXT NOT NULL, `newBalance` TEXT NOT NULL,
                                `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                                `deletedAt` INTEGER, `syncStatus` TEXT NOT NULL
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX `idx_upi_account_timestamp` ON `upi_account_transactions` (`timestamp`)")
                    }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

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

    @Test
    @Throws(IOException::class)
    fun migrate38LegacyWalletRowsToCanonicalTable() {
        val openHelper = createWalletMigrationDatabase("migration-38-legacy", includeCanonicalTable = false)
        val db = openHelper.writableDatabase
        db.execSQL("""
            INSERT INTO `beauty_transactions` (
                id, amount, type, note, timestamp, previousBalance, transactionAmount,
                newBalance, syncId, updatedAt, deletedAt, syncStatus
            ) VALUES (1, '125.50', 'ADD', 'legacy wallet', 1000, '0', '125.50',
                '125.50', 'legacy-wallet-sync', 1000, NULL, 'LOCAL_ONLY')
        """.trimIndent())
        AppDatabase.MIGRATION_38_39.migrate(db)
        val cursor = db.query(
            "SELECT amount, type, timestamp, syncId FROM `upi_account_transactions` WHERE syncId = 'legacy-wallet-sync'"
        )
        try {
            assertEquals(true, cursor.moveToFirst())
            assertEquals("125.50", cursor.getString(0))
            assertEquals("ADD", cursor.getString(1))
            assertEquals(1000L, cursor.getLong(2))
            assertEquals("legacy-wallet-sync", cursor.getString(3))
            assertEquals(false, cursor.moveToNext())
        } finally {
            cursor.close()
            openHelper.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate38SkipsExistingCanonicalSyncIdsAndResolvesIdCollisions() {
        val openHelper = createWalletMigrationDatabase("migration-38-partial", includeCanonicalTable = true)
        val db = openHelper.writableDatabase
        db.execSQL("""
            INSERT INTO `upi_account_transactions` (
                id, amount, type, note, timestamp, previousBalance, transactionAmount,
                newBalance, syncId, updatedAt, deletedAt, syncStatus
            ) VALUES (1, '7.00', 'ADD', 'canonical', 1000, '0', '7.00',
                '7.00', 'different-sync-id', 1000, NULL, 'SYNCED')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `upi_account_transactions` (
                id, amount, type, note, timestamp, previousBalance, transactionAmount,
                newBalance, syncId, updatedAt, deletedAt, syncStatus
            ) VALUES (8, '8.00', 'ADD', 'already copied', 1000, '0', '8.00',
                '8.00', 'shared-sync-id', 1000, NULL, 'SYNCED')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `beauty_transactions` (
                id, amount, type, note, timestamp, previousBalance, transactionAmount,
                newBalance, syncId, updatedAt, deletedAt, syncStatus
            ) VALUES (1, '125.50', 'ADD', 'legacy collision', 1000, '0', '125.50',
                '125.50', 'legacy-collision-sync', 1000, NULL, 'LOCAL_ONLY')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO `beauty_transactions` (
                id, amount, type, note, timestamp, previousBalance, transactionAmount,
                newBalance, syncId, updatedAt, deletedAt, syncStatus
            ) VALUES (2, '8.00', 'ADD', 'duplicate legacy copy', 1000, '0', '8.00',
                '8.00', 'shared-sync-id', 1000, NULL, 'LOCAL_ONLY')
        """.trimIndent())
        AppDatabase.MIGRATION_38_39.migrate(db)
        val cursor = db.query("""
            SELECT COUNT(*), COUNT(DISTINCT syncId),
                SUM(CASE WHEN syncId = 'legacy-collision-sync' AND amount = '125.50' THEN 1 ELSE 0 END),
                SUM(CASE WHEN syncId = 'shared-sync-id' THEN 1 ELSE 0 END)
            FROM `upi_account_transactions`
        """.trimIndent())
        try {
            assertEquals(true, cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
        } finally {
            cursor.close()
            openHelper.close()
        }
    }

    @Test
    fun migrate39MarksOnlyPreviouslyMirroredUpiReceiptsAsShadowDuplicates() {
        val openHelper = createWalletMigrationDatabase("migration-39-income", includeCanonicalTable = true)
        val db = openHelper.writableDatabase
        db.execSQL("""
            INSERT INTO settlement_history (
                id, customerId, settledAmount, timestamp, ledgerEntryType, paymentMethod,
                note, originId, isShadowDuplicate, deletedAt
            ) VALUES
                (1, 0, '125.00', 10000, 'UPI_ACCOUNT_TOPUP', 'UPI', 'Direct Pay - Order #7', NULL, 0, NULL),
                (2, 7, '125.00', 10000, 'PAYMENT', 'UPI', 'Payment for Order #7 via UPI', 7, 0, NULL),
                (3, 0, '125.00', 10000, 'UPI_ACCOUNT_TOPUP', 'UPI', 'Manual business receipt', NULL, 0, NULL),
                (4, 0, '30.00', 20005, 'UPI_ACCOUNT_TOPUP', 'UPI', 'Debt Settlement', NULL, 0, NULL),
                (5, 9, '30.00', 20000, 'PAYMENT', 'UPI', 'Debt Payment', 9, 0, NULL)
        """.trimIndent())

        AppDatabase.MIGRATION_39_40.migrate(db)
        val cursor = db.query("SELECT id, isShadowDuplicate FROM settlement_history WHERE id IN (1, 3, 4) ORDER BY id")
        try {
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1, cursor.getInt(1))
            assertEquals(true, cursor.moveToNext())
            assertEquals(3, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(true, cursor.moveToNext())
            assertEquals(1, cursor.getInt(1))
            assertEquals(false, cursor.moveToNext())
        } finally {
            cursor.close()
            openHelper.close()
        }
    }

    @Test
    fun migrate40ProjectsOnlyManualUpiReceiptsIntoOrderHistory() {
        val openHelper = createWalletMigrationDatabase("migration-40-orders", includeCanonicalTable = true)
        val db = openHelper.writableDatabase
        db.execSQL("""
            INSERT INTO upi_account_transactions (
                id, amount, type, note, timestamp, previousBalance, transactionAmount,
                newBalance, syncId, updatedAt, deletedAt, syncStatus
            ) VALUES
                (1, '1000.00', 'ADD', 'Manual receipt', 10000, '0.00', '1000.00', '1000.00', 'manual-sync', 10000, NULL, 'LOCAL_ONLY'),
                (2, '1000.00', 'ADD', 'Direct Pay - Order #22', 10000, '0.00', '1000.00', '1000.00', 'home-sync', 10000, NULL, 'LOCAL_ONLY')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO settlement_history (
                id, customerId, settledAmount, timestamp, ledgerEntryType, paymentMethod,
                note, originId, originSyncId, isShadowDuplicate, deletedAt
            ) VALUES
                (1, 0, '1000.00', 10000, 'UPI_ACCOUNT_TOPUP', 'UPI', 'Manual receipt', NULL, 'manual-sync', 0, NULL),
                (2, 0, '1000.00', 10000, 'UPI_ACCOUNT_TOPUP', 'UPI', 'Direct Pay - Order #22', NULL, 'home-sync', 1, NULL)
        """.trimIndent())

        AppDatabase.MIGRATION_40_41.migrate(db)
        val orders = db.query("SELECT customerName, totalAmount, paidAmount, paymentMethod, previousBalance, transactionAmount, newBalance, syncId FROM orders")
        try {
            assertEquals(true, orders.moveToFirst())
            assertEquals("UPI Account", orders.getString(0))
            assertEquals("1000.00", orders.getString(1))
            assertEquals("1000.00", orders.getString(2))
            assertEquals("UPI", orders.getString(3))
            assertEquals("0", orders.getString(4))
            assertEquals("0", orders.getString(5))
            assertEquals("0", orders.getString(6))
            assertEquals("manual-sync", orders.getString(7))
            assertEquals(false, orders.moveToNext())
        } finally {
            orders.close()
        }

        val linkedSettlement = db.query("SELECT originId FROM settlement_history WHERE id = 1")
        try {
            assertEquals(true, linkedSettlement.moveToFirst())
            assertEquals(1, linkedSettlement.getInt(0))
        } finally {
            linkedSettlement.close()
        }
        val outbox = db.query("SELECT entityType, entitySyncId, operation FROM sync_outbox")
        try {
            assertEquals(true, outbox.moveToFirst())
            assertEquals("ORDER", outbox.getString(0))
            assertEquals("manual-sync", outbox.getString(1))
            assertEquals("CREATE", outbox.getString(2))
            assertEquals(false, outbox.moveToNext())
        } finally {
            outbox.close()
            openHelper.close()
        }
    }
}
