package com.tadiwaprintbuddy.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.sqlite.db.SupportSQLiteOpenHelper

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class ManualMigrationTest {

    @Test
    fun testManualMIGRATION_34_35() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("manual-migration-test")
            .callback(object : SupportSQLiteOpenHelper.Callback(34) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create v34 schema manually for all tables touched by MIGRATION_34_35
                    
                    // 1. orders
                    db.execSQL("""
                        CREATE TABLE `orders` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `totalAmount` REAL NOT NULL, 
                            `date` INTEGER NOT NULL, 
                            `customerName` TEXT NOT NULL, 
                            `paidAmount` REAL NOT NULL, 
                            `paymentMethod` TEXT NOT NULL, 
                            `customerId` INTEGER NOT NULL, 
                            `previousBalance` REAL NOT NULL, 
                            `transactionAmount` REAL NOT NULL, 
                            `newBalance` REAL NOT NULL, 
                            `paymentStatus` TEXT NOT NULL, 
                            `orderStatus` TEXT NOT NULL, 
                            `receivedAmount` REAL, 
                            `customerSyncId` TEXT NOT NULL, 
                            `syncId` TEXT NOT NULL, 
                            `updatedAt` INTEGER NOT NULL, 
                            `deletedAt` INTEGER, 
                            `syncStatus` TEXT NOT NULL
                        )
                    """)

                    // 2. OrderItem
                    db.execSQL("""
                        CREATE TABLE `OrderItem` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `orderId` INTEGER NOT NULL, 
                            `serviceName` TEXT NOT NULL, 
                            `price` REAL NOT NULL, 
                            `quantity` INTEGER NOT NULL, 
                            `orderSyncId` TEXT NOT NULL, 
                            `syncId` TEXT NOT NULL
                        )
                    """)

                    // 3. settlement_history
                    db.execSQL("""
                        CREATE TABLE `settlement_history` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `customerName` TEXT NOT NULL, 
                            `previousBalance` REAL NOT NULL, 
                            `settledAmount` REAL NOT NULL, 
                            `remainingBalance` REAL NOT NULL, 
                            `timestamp` INTEGER NOT NULL, 
                            `type` TEXT NOT NULL, 
                            `note` TEXT NOT NULL, 
                            `customerId` INTEGER NOT NULL, 
                            `transactionAmount` REAL NOT NULL, 
                            `newBalance` REAL NOT NULL, 
                            `originId` INTEGER, 
                            `ledgerEntryType` TEXT NOT NULL, 
                            `isShadowDuplicate` INTEGER NOT NULL, 
                            `reconciliationStatus` TEXT NOT NULL, 
                            `receivedAmount` REAL, 
                            `customerSyncId` TEXT NOT NULL, 
                            `originSyncId` TEXT, 
                            `syncId` TEXT NOT NULL, 
                            `updatedAt` INTEGER NOT NULL, 
                            `syncStatus` TEXT NOT NULL
                        )
                    """)

                    // 4. debtor_credits
                    db.execSQL("""
                        CREATE TABLE `debtor_credits` (
                            `customerId` INTEGER PRIMARY KEY NOT NULL, 
                            `customerName` TEXT NOT NULL, 
                            `amount` REAL NOT NULL, 
                            `lastUpdated` INTEGER NOT NULL, 
                            `phoneNumber` TEXT
                        )
                    """)

                    // 5. expenses
                    db.execSQL("""
                        CREATE TABLE `expenses` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `title` TEXT NOT NULL, 
                            `category` TEXT NOT NULL, 
                            `amount` REAL NOT NULL, 
                            `timestamp` INTEGER NOT NULL, 
                            `note` TEXT, 
                            `paymentMethod` TEXT NOT NULL, 
                            `syncId` TEXT NOT NULL, 
                            `updatedAt` INTEGER NOT NULL, 
                            `deletedAt` INTEGER, 
                            `syncStatus` TEXT NOT NULL
                        )
                    """)

                    // 6. beauty_transactions
                    db.execSQL("""
                        CREATE TABLE `beauty_transactions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `amount` REAL NOT NULL, 
                            `type` TEXT NOT NULL, 
                            `note` TEXT, 
                            `timestamp` INTEGER NOT NULL, 
                            `previousBalance` REAL NOT NULL, 
                            `transactionAmount` REAL NOT NULL, 
                            `newBalance` REAL NOT NULL, 
                            `syncId` TEXT NOT NULL, 
                            `updatedAt` INTEGER NOT NULL, 
                            `syncStatus` TEXT NOT NULL
                        )
                    """)

                    // 7. external_ledger
                    db.execSQL("""
                        CREATE TABLE `external_ledger` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                            `transactionType` TEXT NOT NULL, 
                            `amount` REAL NOT NULL, 
                            `timestamp` INTEGER NOT NULL, 
                            `customerName` TEXT, 
                            `customerId` INTEGER, 
                            `orderId` INTEGER, 
                            `note` TEXT, 
                            `accountHolder` TEXT NOT NULL, 
                            `upiId` TEXT NOT NULL, 
                            `customerSyncId` TEXT, 
                            `orderSyncId` TEXT, 
                            `syncId` TEXT NOT NULL, 
                            `updatedAt` INTEGER NOT NULL, 
                            `syncStatus` TEXT NOT NULL
                        )
                    """)
                    
                    // Insert sample v34 data
                    db.execSQL("""
                        INSERT INTO orders (
                            totalAmount, date, customerName, paidAmount, paymentMethod, 
                            customerId, previousBalance, transactionAmount, newBalance, 
                            paymentStatus, orderStatus, receivedAmount, customerSyncId, 
                            syncId, updatedAt, syncStatus
                        ) VALUES (123.45, 1000, 'Alice', 100.0, 'CASH', 1, 0.0, 123.45, 23.45, 'PARTIALLY_PAID', 'ACTIVE', 150.0, 'c1', 'o1', 1000, 'SYNCED')
                    """)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
            
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // 1. Run Migration
        AppDatabase.MIGRATION_34_35.migrate(db)

        // 2. Verify Result
        val cursor = db.query("SELECT * FROM orders")
        cursor.moveToFirst()
        
        // In v35, totalAmount and paidAmount are now TEXT
        val totalAmountStr = cursor.getString(cursor.getColumnIndex("totalAmount"))
        val paidAmountStr = cursor.getString(cursor.getColumnIndex("paidAmount"))
        
        // Precision check: values should be preserved
        assertEquals(123.45, totalAmountStr.toDouble(), 0.0001)
        assertEquals(100.0, paidAmountStr.toDouble(), 0.0001)
        
        cursor.close()
        db.close()
    }
}
