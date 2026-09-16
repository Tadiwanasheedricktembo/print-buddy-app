package com.tadiwaprintbuddy.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.sqlite.db.SupportSQLiteOpenHelper

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class ForensicMigrationChainTest {

    @Test
    fun verifyFullMigrationChain_8_to_37() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // 1. Establish Version 8 Baseline
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("forensic-migration-test")
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Reconstruct v8 tables based on forensic audit of AppDatabase.kt
                    
                    // orders (v8: REAL amounts, no customerId, no sync metadata)
                    db.execSQL("""
                        CREATE TABLE `orders` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `totalAmount` REAL NOT NULL, 
                            `date` INTEGER NOT NULL, 
                            `customerName` TEXT NOT NULL, 
                            `paidAmount` REAL NOT NULL, 
                            `paymentMethod` TEXT NOT NULL
                        )
                    """)

                    // OrderItem (v8: REAL price)
                    db.execSQL("""
                        CREATE TABLE `OrderItem` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `orderId` INTEGER NOT NULL, 
                            `serviceName` TEXT NOT NULL, 
                            `price` REAL NOT NULL, 
                            `quantity` INTEGER NOT NULL
                        )
                    """)

                    // settlement_history (v8: REAL amounts, no customerId, no type/note)
                    db.execSQL("""
                        CREATE TABLE `settlement_history` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `customerName` TEXT NOT NULL, 
                            `previousBalance` REAL NOT NULL, 
                            `settledAmount` REAL NOT NULL, 
                            `remainingBalance` REAL NOT NULL, 
                            `timestamp` INTEGER NOT NULL
                        )
                    """)

                    // debtor_credits (v8: customerName as PK, REAL amount)
                    db.execSQL("""
                        CREATE TABLE `debtor_credits` (
                            `customerName` TEXT PRIMARY KEY NOT NULL, 
                            `amount` REAL NOT NULL
                        )
                    """)

                    // photos
                    db.execSQL("""
                        CREATE TABLE `photos` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `orderId` INTEGER NOT NULL, 
                            `filePath` TEXT NOT NULL
                        )
                    """)

                    // printer_references
                    db.execSQL("""
                        CREATE TABLE `printer_references` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `title` TEXT NOT NULL, 
                            `notes` TEXT, 
                            `imagePath` TEXT NOT NULL, 
                            `timestamp` INTEGER NOT NULL
                        )
                    """)

                    // external_ledger
                    db.execSQL("""
                        CREATE TABLE `external_ledger` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `transactionType` TEXT NOT NULL, 
                            `amount` REAL NOT NULL, 
                            `timestamp` INTEGER NOT NULL, 
                            `customerName` TEXT, 
                            `accountHolder` TEXT NOT NULL, 
                            `upiId` TEXT NOT NULL,
                            `orderId` INTEGER,
                            `note` TEXT
                        )
                    """)

                    // beauty_transactions (Added to baseline)
                    db.execSQL("""
                        CREATE TABLE `beauty_transactions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `amount` REAL NOT NULL, 
                            `type` TEXT NOT NULL, 
                            `note` TEXT, 
                            `timestamp` INTEGER NOT NULL
                        )
                    """)

                    // Insert representative legacy data (Gate 1A)
                    // Precision targets: 0.1, 0.2, 0.3, 123.45, 999999999.99
                    db.execSQL("INSERT INTO orders (totalAmount, date, customerName, paidAmount, paymentMethod) VALUES (0.30, 1000, 'Alice', 0.10, 'CASH')")
                    db.execSQL("INSERT INTO orders (totalAmount, date, customerName, paidAmount, paymentMethod) VALUES (123.45, 1001, 'Bob', 123.45, 'UPI')")
                    db.execSQL("INSERT INTO orders (totalAmount, date, customerName, paidAmount, paymentMethod) VALUES (999999999.99, 1002, 'Charlie', 0.0, 'NONE')")
                    
                    db.execSQL("INSERT INTO OrderItem (orderId, serviceName, price, quantity) VALUES (1, 'Print', 0.15, 2)")
                    
                    db.execSQL("INSERT INTO settlement_history (customerName, previousBalance, settledAmount, remainingBalance, timestamp) VALUES ('Alice', 0.30, 0.10, 0.20, 1000)")
                    
                    db.execSQL("INSERT INTO debtor_credits (customerName, amount) VALUES ('Alice', 0.20)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
            
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // 2. Execute Migration Chain
        val migrations = arrayOf(
            AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, 
            AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, 
            AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, 
            AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_19_24,
            AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_25_26, AppDatabase.MIGRATION_26_27, 
            AppDatabase.MIGRATION_27_28, AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, 
            AppDatabase.MIGRATION_30_31, AppDatabase.MIGRATION_31_32, AppDatabase.MIGRATION_32_33,
            AppDatabase.MIGRATION_33_34, AppDatabase.MIGRATION_34_35, AppDatabase.MIGRATION_35_36, 
            AppDatabase.MIGRATION_36_37
        )

        for (migration in migrations) {
            migration.migrate(db)
        }

        // 3. Verify Financial Precision (Gate 1A)
        val orderCursor = db.query("SELECT * FROM orders ORDER BY id ASC")
        
        // Alice: 0.30, 0.10
        orderCursor.moveToFirst()
        assertEquals("0.3", orderCursor.getString(orderCursor.getColumnIndex("totalAmount")))
        assertEquals("0.1", orderCursor.getString(orderCursor.getColumnIndex("paidAmount")))
        
        // Bob: 123.45
        orderCursor.moveToNext()
        assertEquals("123.45", orderCursor.getString(orderCursor.getColumnIndex("totalAmount")))
        
        // Charlie: 999999999.99
        orderCursor.moveToNext()
        assertEquals("999999999.99", orderCursor.getString(orderCursor.getColumnIndex("totalAmount")))
        orderCursor.close()

        // Verify OrderItem (Gate 1A)
        val itemCursor = db.query("SELECT * FROM OrderItem")
        itemCursor.moveToFirst()
        assertEquals("0.15", itemCursor.getString(itemCursor.getColumnIndex("price")))
        itemCursor.close()

        // Verify Settlement (Gate 1A)
        val settlementCursor = db.query("SELECT * FROM settlement_history")
        settlementCursor.moveToFirst()
        assertEquals("0.3", settlementCursor.getString(settlementCursor.getColumnIndex("previousBalance")))
        assertEquals("0.1", settlementCursor.getString(settlementCursor.getColumnIndex("settledAmount")))
        assertEquals("0.2", settlementCursor.getString(settlementCursor.getColumnIndex("remainingBalance")))
        settlementCursor.close()

        // 4. Verify Final Schema (Gate 1B)
        // We'll check the 'orders' table as a proxy for the whole schema
        val tableInfo = getTableInfo(db, "orders")
        
        // Check core columns from v37
        assertTrue(tableInfo.containsKey("customerSyncId"))
        assertTrue(tableInfo.containsKey("syncId"))
        assertTrue(tableInfo.containsKey("updatedAt"))
        assertTrue(tableInfo.containsKey("deletedAt"))
        assertTrue(tableInfo.containsKey("syncStatus"))
        
        // Check types (should be TEXT for amounts)
        assertEquals("TEXT", tableInfo["totalAmount"]?.type)
        assertEquals("TEXT", tableInfo["paidAmount"]?.type)
        assertEquals("INTEGER", tableInfo["date"]?.type)
        
        // 5. Verify Indexes
        val indexCursor = db.query("PRAGMA index_list('orders')")
        val indexes = mutableSetOf<String>()
        while (indexCursor.moveToNext()) {
            indexes.add(indexCursor.getString(indexCursor.getColumnIndex("name")))
        }
        indexCursor.close()
        
        assertTrue(indexes.contains("idx_orders_date"))
        assertTrue(indexes.contains("idx_orders_payment_method"))

        // Verify OrderItem Foreign Key
        val fkCursor = db.query("PRAGMA foreign_key_list('OrderItem')")
        fkCursor.moveToFirst()
        assertEquals("orders", fkCursor.getString(fkCursor.getColumnIndex("table")))
        assertEquals("id", fkCursor.getString(fkCursor.getColumnIndex("to")))
        assertEquals("orderId", fkCursor.getString(fkCursor.getColumnIndex("from")))
        assertEquals("CASCADE", fkCursor.getString(fkCursor.getColumnIndex("on_delete")))
        fkCursor.close()

        // Verify settlement_history has all v37 columns
        val settlementInfo = getTableInfo(db, "settlement_history")
        assertTrue(settlementInfo.containsKey("receivedAmount"))
        assertTrue(settlementInfo.containsKey("customerSyncId"))
        assertTrue(settlementInfo.containsKey("originSyncId"))
        assertTrue(settlementInfo.containsKey("deletedAt"))
        assertEquals("TEXT", settlementInfo["newBalance"]?.type)

        db.close()
    }

    private fun getTableInfo(db: SupportSQLiteDatabase, tableName: String): Map<String, ColumnInfo> {
        val cursor = db.query("PRAGMA table_info('$tableName')")
        val columns = mutableMapOf<String, ColumnInfo>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndex("name"))
            val type = cursor.getString(cursor.getColumnIndex("type"))
            val notNull = cursor.getInt(cursor.getColumnIndex("notnull")) == 1
            columns[name] = ColumnInfo(name, type, notNull)
        }
        cursor.close()
        return columns
    }

    data class ColumnInfo(val name: String, val type: String, val notNull: Boolean)
}
