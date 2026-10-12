package com.tadiwaprintbuddy.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.TestApplication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class MigrationTest {
    private val TEST_DB = "migration-test"
    private val monetaryTables = listOf(
        "orders", "OrderItem", "photos", "settlement_history",
        "debtor_credits", "expenses", "beauty_transactions", "external_ledger"
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
    fun migrate34To35() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (affinity in listOf("TEXT", "REAL")) {
            val databaseName = "$TEST_DB-$affinity"
            context.deleteDatabase(databaseName)
            try {
                createVersion34Database(databaseName, affinity)
                val migrationHelper = openVersion35Database(databaseName)
                val db = migrationHelper.writableDatabase
                try {
                    assertEquals(35, queryInt(db, "PRAGMA user_version"))
                    assertMigratedData(db, affinity)
                    assertVersion35Schema(db)
                } finally {
                    migrationHelper.close()
                }

                val reopenedHelper = openVersion35Database(databaseName)
                val reopened = reopenedHelper.writableDatabase
                try {
                    assertEquals(35, queryInt(reopened, "PRAGMA user_version"))
                    assertMigratedData(reopened, affinity)
                    assertVersion35Schema(reopened)
                } finally {
                    reopenedHelper.close()
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun migrate34To35RejectsInvalidMoneyAndRollsBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "$TEST_DB-invalid-money"
        context.deleteDatabase(databaseName)
        try {
            createVersion34Database(databaseName, "TEXT")
            val version34 = openVersion34Database(databaseName)
            version34.writableDatabase.execSQL(
                "UPDATE `expenses` SET `amount` = 'not-a-decimal' WHERE `id` = 1"
            )
            version34.close()

            val migrationHelper = openVersion35Database(databaseName)
            try {
                migrationHelper.writableDatabase
                fail("Expected invalid monetary data to abort the migration")
            } catch (exception: RuntimeException) {
                val messages = generateSequence<Throwable>(exception) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" ")
                assertTrue(messages, messages.contains("expenses.amount"))
                assertTrue(messages, messages.contains("rowid 1"))
            } finally {
                migrationHelper.close()
            }

            val unchanged = openVersion34Database(databaseName)
            try {
                val db = unchanged.writableDatabase
                assertEquals(34, queryInt(db, "PRAGMA user_version"))
                assertEquals("not-a-decimal", queryString(db, "SELECT amount FROM expenses WHERE id = 1"))
                assertEquals(
                    1,
                    queryInt(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'orders'")
                )
                assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM `OrderItem` WHERE `orderId` = 1"))
            } finally {
                unchanged.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun createVersion34Database(databaseName: String, monetaryAffinity: String) {
        val fixture = openVersion34Database(databaseName)
        try {
            createVersion34Fixture(fixture.writableDatabase, monetaryAffinity)
        } finally {
            fixture.close()
        }
    }

    private fun openVersion34Database(databaseName: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext<Context>()
            )
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(34) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

    private fun openVersion35Database(databaseName: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext<Context>()
            )
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(35) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        throw AssertionError("Expected to upgrade the version-34 fixture")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(34, oldVersion)
                        assertEquals(35, newVersion)
                        AppDatabase.MIGRATION_34_35.migrate(db)
                    }
                })
                .build()
        )

    private fun assertMigratedData(db: SupportSQLiteDatabase, affinity: String) {
        val realStorage = affinity == "REAL"
        val expectedOrderNewBalance = "-2.75"
        val expectedPaidAmount = if (realStorage) "100.0" else "100.00"
        val expectedZero = if (realStorage) "0.0" else "0.00"
        val expectedReceivedAmount = if (realStorage) "150.0" else "150.00"
        val expectedFloatingEdge = "0.30000000000000004"
        val expectedPrecisionValue = "1.005"
        val expectedLedgerAmount = "10.12345678901234567890"

        assertEquals("123.45", queryString(db, "SELECT totalAmount FROM orders WHERE id = 1"))
        assertEquals(expectedPaidAmount, queryString(db, "SELECT paidAmount FROM orders WHERE id = 1"))
        assertEquals(expectedZero, queryString(db, "SELECT previousBalance FROM orders WHERE id = 1"))
        assertEquals(expectedPrecisionValue, queryString(db, "SELECT transactionAmount FROM orders WHERE id = 1"))
        assertEquals(expectedOrderNewBalance, queryString(db, "SELECT newBalance FROM orders WHERE id = 1"))
        assertEquals(expectedReceivedAmount, queryString(db, "SELECT receivedAmount FROM orders WHERE id = 1"))
        assertEquals(expectedPrecisionValue, queryString(db, "SELECT price FROM OrderItem WHERE id = 1"))
        assertEquals(expectedZero, queryString(db, "SELECT previousBalance FROM settlement_history WHERE id = 1"))
        assertEquals(expectedPrecisionValue, queryString(db, "SELECT settledAmount FROM settlement_history WHERE id = 1"))
        assertEquals(expectedOrderNewBalance, queryString(db, "SELECT remainingBalance FROM settlement_history WHERE id = 1"))
        assertEquals(expectedFloatingEdge, queryString(db, "SELECT transactionAmount FROM settlement_history WHERE id = 1"))
        assertEquals(expectedZero, queryString(db, "SELECT newBalance FROM settlement_history WHERE id = 1"))
        assertEquals("23.45", queryString(db, "SELECT amount FROM debtor_credits WHERE customerId = 1"))
        assertEquals("-2.75", queryString(db, "SELECT amount FROM expenses WHERE id = 1"))
        assertEquals(expectedFloatingEdge, queryString(db, "SELECT amount FROM beauty_transactions WHERE id = 1"))
        assertEquals(expectedOrderNewBalance, queryString(db, "SELECT previousBalance FROM beauty_transactions WHERE id = 1"))
        assertEquals(expectedPrecisionValue, queryString(db, "SELECT transactionAmount FROM beauty_transactions WHERE id = 1"))
        assertEquals(expectedZero, queryString(db, "SELECT newBalance FROM beauty_transactions WHERE id = 1"))
        assertEquals(expectedLedgerAmount, queryString(db, "SELECT amount FROM external_ledger WHERE id = 1"))

        for (table in monetaryTables) {
            assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM `$table`"))
        }
        assertEquals(1, queryInt(db, "SELECT id FROM orders WHERE id = 1"))
        assertEquals(1, queryInt(db, "SELECT id FROM OrderItem WHERE id = 1 AND orderId = 1"))
        assertEquals(1, queryInt(db, "SELECT id FROM photos WHERE id = 1 AND orderId = 1"))
        assertEquals("/photos/order.jpg", queryString(db, "SELECT filePath FROM photos WHERE id = 1"))
        assertEquals(expectedReceivedAmount, queryString(db, "SELECT receivedAmount FROM settlement_history WHERE id = 1"))
        assertEquals("settlement-sync", queryString(db, "SELECT syncId FROM settlement_history WHERE id = 1"))
        assertEquals("expense-sync", queryString(db, "SELECT syncId FROM expenses WHERE id = 1"))
        assertEquals("wallet-sync", queryString(db, "SELECT syncId FROM beauty_transactions WHERE id = 1"))
        assertEquals("ledger-sync", queryString(db, "SELECT syncId FROM external_ledger WHERE id = 1"))
        assertEquals(0, queryInt(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    private fun assertVersion35Schema(db: SupportSQLiteDatabase) {
        val schema = version35Schema()
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        for (entity in (0 until entities.length()).map { entities.getJSONObject(it) }
            .filter { it.getString("tableName") in monetaryTables }) {
            val table = entity.getString("tableName")
            val expectedColumns = entity.getJSONArray("fields")
            val actualColumns = mutableMapOf<String, Triple<String, Boolean, Int>>()
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    actualColumns[cursor.getString(cursor.getColumnIndexOrThrow("name"))] = Triple(
                        cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                        cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                    )
                }
            }
            assertEquals(expectedColumns.length(), actualColumns.size)
            for (index in 0 until expectedColumns.length()) {
                val field = expectedColumns.getJSONObject(index)
                val name = field.getString("columnName")
                assertEquals(
                    Triple(
                        field.getString("affinity"),
                        field.optBoolean("notNull", false),
                        if (entity.getJSONObject("primaryKey").getJSONArray("columnNames").let { keys ->
                                (0 until keys.length()).any { keys.getString(it) == name }
                            }) 1 else 0
                    ),
                    actualColumns[name]
                )
            }

            val expectedIndices = entity.optJSONArray("indices") ?: JSONArray()
            val expectedIndexNames = (0 until expectedIndices.length())
                .map { expectedIndices.getJSONObject(it).getString("name") }
                .toSet()
            val actualIndexNames = mutableSetOf<String>()
            db.query("PRAGMA index_list(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    actualIndexNames += name
                    val expected = (0 until expectedIndices.length())
                        .map { expectedIndices.getJSONObject(it) }
                        .firstOrNull { it.getString("name") == name }
                    assertNotNull("Unexpected index $name on $table", expected)
                    assertEquals(
                        expected!!.getBoolean("unique"),
                        cursor.getInt(cursor.getColumnIndexOrThrow("unique")) != 0
                    )
                }
            }
            assertEquals(expectedIndexNames, actualIndexNames)
            for (index in 0 until expectedIndices.length()) {
                val expected = expectedIndices.getJSONObject(index)
                val indexName = expected.getString("name")
                val expectedIndexColumns = expected.getJSONArray("columnNames")
                val actualIndexColumns = mutableListOf<String>()
                db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        actualIndexColumns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    }
                }
                assertEquals(
                    (0 until expectedIndexColumns.length()).map { expectedIndexColumns.getString(it) },
                    actualIndexColumns
                )
            }

            val expectedForeignKeys = entity.optJSONArray("foreignKeys") ?: JSONArray()
            val actualForeignKeys = mutableListOf<List<String>>()
            db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    actualForeignKeys += listOf(
                        cursor.getString(cursor.getColumnIndexOrThrow("table")),
                        cursor.getString(cursor.getColumnIndexOrThrow("from")),
                        cursor.getString(cursor.getColumnIndexOrThrow("to")),
                        cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                        cursor.getString(cursor.getColumnIndexOrThrow("on_update"))
                    )
                }
            }
            val expectedForeignKeyValues = (0 until expectedForeignKeys.length()).map { index ->
                val foreignKey = expectedForeignKeys.getJSONObject(index)
                listOf(
                    foreignKey.getString("table"),
                    foreignKey.getJSONArray("columns").getString(0),
                    foreignKey.getJSONArray("referencedColumns").getString(0),
                    foreignKey.getString("onDelete"),
                    foreignKey.getString("onUpdate")
                )
            }
            assertEquals(expectedForeignKeyValues, actualForeignKeys)
        }
    }

    private fun version35Schema(): JSONObject {
        val relativePath =
            "schemas/com.tadiwaprintbuddy.app.data.AppDatabase/35.json"
        val schemaFile = listOf(
            File(System.getProperty("user.dir"), relativePath),
            File(System.getProperty("user.dir"), "app/$relativePath")
        ).firstOrNull { it.isFile }
        assertNotNull("Missing checked-in v35 Room schema: $relativePath", schemaFile)
        return JSONObject(schemaFile!!.readText())
    }

    private fun createVersion34Fixture(db: SupportSQLiteDatabase, monetaryAffinity: String) {
        // v34 has no exported schema. This focused fixture combines its historical entity fields
        // with the REAL affinities explicitly introduced by old migrations.
        val realColumnsByTable = mapOf(
            "orders" to setOf(
                "totalAmount", "paidAmount", "previousBalance", "transactionAmount",
                "newBalance", "receivedAmount"
            ),
            "settlement_history" to setOf(
                "previousBalance", "settledAmount", "remainingBalance", "transactionAmount",
                "newBalance", "receivedAmount"
            ),
            "debtor_credits" to setOf("amount"),
            "expenses" to setOf("amount"),
            "beauty_transactions" to setOf(
                "previousBalance", "transactionAmount", "newBalance"
            )
        )
        fun usesRealAffinity(table: String, column: String): Boolean =
            monetaryAffinity == "REAL" && column in realColumnsByTable[table].orEmpty()

        fun money(table: String, column: String, value: String): String =
            if (usesRealAffinity(table, column)) "CAST('$value' AS REAL)" else "'$value'"

        fun createTable(sql: String) {
            val tableName = Regex("CREATE TABLE `([^`]+)`").find(sql)
                ?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Missing table name in v34 fixture")
            val declaration = Regex("`([A-Za-z]+)` TEXT")
            val schema = declaration.replace(sql) { match ->
                if (usesRealAffinity(tableName, match.groupValues[1])) {
                    "`${match.groupValues[1]}` $monetaryAffinity"
                } else {
                    match.value
                }
            }
            db.execSQL(schema)
        }

        createTable("""
            CREATE TABLE `orders` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `totalAmount` TEXT NOT NULL,
                `date` INTEGER NOT NULL, `customerName` TEXT NOT NULL, `paidAmount` TEXT NOT NULL,
                `paymentMethod` TEXT NOT NULL, `customerId` INTEGER NOT NULL, `previousBalance` TEXT NOT NULL,
                `transactionAmount` TEXT NOT NULL, `newBalance` TEXT NOT NULL, `paymentStatus` TEXT NOT NULL,
                `orderStatus` TEXT NOT NULL, `receivedAmount` TEXT, `customerSyncId` TEXT NOT NULL,
                `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                `syncStatus` TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX `idx_orders_date` ON `orders` (`date`)")
        db.execSQL("CREATE INDEX `idx_orders_payment_method` ON `orders` (`paymentMethod`)")
        createTable("""
            CREATE TABLE `OrderItem` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                `serviceName` TEXT NOT NULL, `price` TEXT NOT NULL, `quantity` INTEGER NOT NULL,
                `orderSyncId` TEXT NOT NULL, `syncId` TEXT NOT NULL,
                FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX `index_OrderItem_orderId` ON `OrderItem` (`orderId`)")
        createTable("""
            CREATE TABLE `photos` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                `filePath` TEXT NOT NULL, `syncId` TEXT NOT NULL,
                FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX `index_photos_orderId` ON `photos` (`orderId`)")
        createTable("""
            CREATE TABLE `settlement_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `customerName` TEXT NOT NULL,
                `previousBalance` TEXT NOT NULL, `settledAmount` TEXT NOT NULL, `remainingBalance` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `note` TEXT NOT NULL,
                `customerId` INTEGER NOT NULL, `transactionAmount` TEXT NOT NULL, `newBalance` TEXT NOT NULL,
                `originId` INTEGER, `ledgerEntryType` TEXT NOT NULL, `isShadowDuplicate` INTEGER NOT NULL,
                `reconciliationStatus` TEXT NOT NULL, `receivedAmount` TEXT, `customerSyncId` TEXT NOT NULL,
                `originSyncId` TEXT, `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                `syncStatus` TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX `index_settlement_history_customerId` ON `settlement_history` (`customerId`)")
        db.execSQL("CREATE INDEX `index_settlement_history_originId` ON `settlement_history` (`originId`)")
        db.execSQL("CREATE INDEX `index_settlement_history_originId_ledgerEntryType` ON `settlement_history` (`originId`, `ledgerEntryType`)")
        db.execSQL("CREATE INDEX `idx_settlement_timestamp` ON `settlement_history` (`timestamp`)")
        createTable("""
            CREATE TABLE `debtor_credits` (
                `customerId` INTEGER NOT NULL, `customerName` TEXT NOT NULL, `amount` TEXT NOT NULL,
                `lastUpdated` INTEGER NOT NULL, `phoneNumber` TEXT, PRIMARY KEY(`customerId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX `idx_debtor_updated` ON `debtor_credits` (`lastUpdated`)")
        createTable("""
            CREATE TABLE `expenses` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL,
                `amount` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `note` TEXT, `paymentMethod` TEXT NOT NULL,
                `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncStatus` TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX `idx_expenses_timestamp` ON `expenses` (`timestamp`)")
        createTable("""
            CREATE TABLE `beauty_transactions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `amount` TEXT NOT NULL, `type` TEXT NOT NULL,
                `note` TEXT, `timestamp` INTEGER NOT NULL, `previousBalance` TEXT NOT NULL,
                `transactionAmount` TEXT NOT NULL, `newBalance` TEXT NOT NULL, `syncId` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX `idx_beauty_timestamp` ON `beauty_transactions` (`timestamp`)")
        createTable("""
            CREATE TABLE `external_ledger` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transactionType` TEXT NOT NULL,
                `amount` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `customerName` TEXT, `customerId` INTEGER,
                `orderId` INTEGER, `note` TEXT, `accountHolder` TEXT NOT NULL, `upiId` TEXT NOT NULL,
                `customerSyncId` TEXT, `orderSyncId` TEXT, `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                `syncStatus` TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `orders` VALUES
                (1, ${money("orders", "totalAmount", "123.45")}, 1000, 'Test Customer',
                 ${money("orders", "paidAmount", "100.00")}, 'CASH', 1,
                 ${money("orders", "previousBalance", "0.00")},
                 ${money("orders", "transactionAmount", "1.005")},
                 ${money("orders", "newBalance", "-2.75")}, 'PARTIALLY_PAID',
                 'ACTIVE', ${money("orders", "receivedAmount", "150.00")}, 'customer-sync', 'order-sync',
                 1000, NULL, 'SYNCED')
        """.trimIndent())
        db.execSQL("INSERT INTO `OrderItem` VALUES (1, 1, 'A4 Print', ${money("OrderItem", "price", "1.005")}, 2, 'order-sync', 'item-sync')")
        db.execSQL("INSERT INTO `photos` VALUES (1, 1, '/photos/order.jpg', 'photo-sync')")
        db.execSQL("""
            INSERT INTO `settlement_history` VALUES
                (1, 'Test Customer', ${money("settlement_history", "previousBalance", "0.00")},
                 ${money("settlement_history", "settledAmount", "1.005")},
                 ${money("settlement_history", "remainingBalance", "-2.75")},
                 1000, 'ORDER', 'Order payment', 1,
                 ${money("settlement_history", "transactionAmount", "0.30000000000000004")},
                 ${money("settlement_history", "newBalance", "0.00")}, 1, 'ORDER_POST', 0,
                 'VERIFIED', ${money("settlement_history", "receivedAmount", "150.00")},
                 'customer-sync', 'order-sync', 'settlement-sync', 1000, 'SYNCED')
        """.trimIndent())
        db.execSQL("INSERT INTO `debtor_credits` VALUES (1, 'Test Customer', ${money("debtor_credits", "amount", "23.45")}, 1000, NULL)")
        db.execSQL("INSERT INTO `expenses` VALUES (1, 'Paper', 'Supplies', ${money("expenses", "amount", "-2.75")}, 1000, NULL, 'CASH', 'expense-sync', 1000, NULL, 'SYNCED')")
        db.execSQL("INSERT INTO `beauty_transactions` VALUES (1, '0.30000000000000004', 'ADD', 'Wallet receipt', 1000, ${money("beauty_transactions", "previousBalance", "-2.75")}, ${money("beauty_transactions", "transactionAmount", "1.005")}, ${money("beauty_transactions", "newBalance", "0.00")}, 'wallet-sync', 1000, 'SYNCED')")
        db.execSQL("INSERT INTO `external_ledger` VALUES (1, 'TRANSFER', '10.12345678901234567890', 1000, 'Test Customer', 1, 1, 'Transfer', 'Owner', 'owner@upi', 'customer-sync', 'order-sync', 'ledger-sync', 1000, 'SYNCED')")
    }

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String {
        val cursor = db.query(sql)
        try {
            assertTrue(cursor.moveToFirst())
            return cursor.getString(0)
        } finally {
            cursor.close()
        }
    }

    private fun queryInt(db: SupportSQLiteDatabase, sql: String): Int {
        val cursor = db.query(sql)
        try {
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        } finally {
            cursor.close()
        }
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
