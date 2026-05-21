package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.tadiwaprintbuddy.app.BuildConfig

@Database(
    entities = [Order::class, OrderItem::class, Photo::class, DebtorCredit::class, PrinterReference::class, SettlementHistory::class, ExternalLedger::class, BeautyTransaction::class, CustomerEntity::class, Expense::class, StockItem::class],
    version = 24,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun printDao(): PrintDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "print_database"
                ).addMigrations(
                    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, 
                    MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, 
                    MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_24
                )

                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_19_24 = object : Migration(19, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Handle version jump and adding title to expenses
                db.execSQL("ALTER TABLE expenses ADD COLUMN title TEXT NOT NULL DEFAULT 'Manual Expense'")
            }
        }

        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 16 to 17 (Fixing Ledger Schema)")
                
                // 1. Detect existing columns to handle partial migration states
                val cursor = db.query("PRAGMA table_info(settlement_history)")
                val columns = mutableSetOf<String>()
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex != -1) {
                    while (cursor.moveToNext()) {
                        columns.add(cursor.getString(nameIndex))
                    }
                }
                cursor.close()

                // 2. Prepare for rebuild
                db.execSQL("DROP INDEX IF EXISTS idx_unique_order_post")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS settlement_history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        customerName TEXT NOT NULL,
                        previousBalance REAL NOT NULL,
                        settledAmount REAL NOT NULL,
                        remainingBalance REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        note TEXT NOT NULL,
                        customerId INTEGER NOT NULL,
                        transactionAmount REAL NOT NULL,
                        newBalance REAL NOT NULL,
                        originId INTEGER,
                        ledgerEntryType TEXT NOT NULL,
                        isShadowDuplicate INTEGER NOT NULL,
                        reconciliationStatus TEXT NOT NULL
                    )
                """.trimIndent())

                // 3. Build dynamic select for copying
                val originIdCol = if (columns.contains("originId")) "originId" else "NULL"
                val ledgerTypeCol = if (columns.contains("ledgerEntryType")) "ledgerEntryType" else 
                    "CASE WHEN type = 'ORDER' THEN 'ORDER_POST' WHEN type = 'PAYMENT' THEN 'PAYMENT' WHEN type = 'ADJUSTMENT' THEN 'ADJUSTMENT' ELSE 'PAYMENT' END"
                val shadowCol = if (columns.contains("isShadowDuplicate")) "isShadowDuplicate" else "0"
                val statusCol = if (columns.contains("reconciliationStatus")) "reconciliationStatus" else "'VERIFIED'"

                db.execSQL("""
                    INSERT INTO settlement_history_new (
                        id, customerName, previousBalance, settledAmount, remainingBalance, timestamp,
                        type, note, customerId, transactionAmount, newBalance,
                        originId, ledgerEntryType, isShadowDuplicate, reconciliationStatus
                    )
                    SELECT 
                        id, customerName, previousBalance, settledAmount, remainingBalance, timestamp,
                        type, note, customerId, transactionAmount, newBalance,
                        $originIdCol, $ledgerTypeCol, $shadowCol, $statusCol
                    FROM settlement_history
                """.trimIndent())

                // 4. Drop and Rename
                db.execSQL("DROP TABLE settlement_history")
                db.execSQL("ALTER TABLE settlement_history_new RENAME TO settlement_history")

                // 5. Recreate indices exactly as Room expects
                db.execSQL("CREATE INDEX IF NOT EXISTS index_settlement_history_customerId ON settlement_history (customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_settlement_history_originId ON settlement_history (originId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_settlement_history_originId_ledgerEntryType ON settlement_history (originId, ledgerEntryType)")

                Log.d("DatabaseMigration", "Migration 16 to 17 completed successfully")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 17 to 18 (Adding Expenses Table)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        amount REAL NOT NULL,
                        category TEXT NOT NULL,
                        note TEXT,
                        timestamp INTEGER NOT NULL,
                        paymentMethod TEXT NOT NULL DEFAULT 'CASH'
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 18 to 19 (Adding Stock Items Table)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS stock_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        currentQuantity INTEGER NOT NULL,
                        lowStockThreshold INTEGER NOT NULL DEFAULT 10,
                        unit TEXT NOT NULL DEFAULT 'pcs'
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 15 to 16 (Ledger Hardening Rebuild)")
                
                // 1. Create new table with EXACT schema Room expects
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS settlement_history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        customerName TEXT NOT NULL,
                        previousBalance REAL NOT NULL,
                        settledAmount REAL NOT NULL,
                        remainingBalance REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        note TEXT NOT NULL,
                        customerId INTEGER NOT NULL,
                        transactionAmount REAL NOT NULL,
                        newBalance REAL NOT NULL,
                        originId INTEGER,
                        ledgerEntryType TEXT NOT NULL,
                        isShadowDuplicate INTEGER NOT NULL,
                        reconciliationStatus TEXT NOT NULL
                    )
                """.trimIndent())

                // 2. Copy data and backfill new columns
                db.execSQL("""
                    INSERT INTO settlement_history_new (
                        id, customerName, previousBalance, settledAmount, remainingBalance, timestamp,
                        type, note, customerId, transactionAmount, newBalance,
                        originId, ledgerEntryType, isShadowDuplicate, reconciliationStatus
                    )
                    SELECT 
                        id, customerName, previousBalance, settledAmount, remainingBalance, timestamp,
                        type, note, customerId, transactionAmount, newBalance,
                        NULL,
                        CASE 
                            WHEN type = 'ORDER' THEN 'ORDER_POST'
                            WHEN type = 'PAYMENT' THEN 'PAYMENT'
                            WHEN type = 'ADJUSTMENT' THEN 'ADJUSTMENT'
                            ELSE 'PAYMENT'
                        END,
                        0,
                        'VERIFIED'
                    FROM settlement_history
                """.trimIndent())

                // 3. Drop and Rename
                db.execSQL("DROP TABLE settlement_history")
                db.execSQL("ALTER TABLE settlement_history_new RENAME TO settlement_history")

                // 4. Recreate indices exactly as Room expects
                db.execSQL("CREATE INDEX IF NOT EXISTS index_settlement_history_customerId ON settlement_history (customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_settlement_history_originId ON settlement_history (originId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_settlement_history_originId_ledgerEntryType ON settlement_history (originId, ledgerEntryType)")

                Log.d("DatabaseMigration", "Migration 15 to 16 completed successfully")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 14 to 15 (Balance Healing)")
                
                // Recalculate debtor_credits from the latest settlement history or unpaid orders.
                // This fixes the doubling caused by SUM() in MIGRATION_10_11 and 
                // double-accounting in MainActivity.
                db.execSQL("DELETE FROM debtor_credits")
                db.execSQL("""
                    INSERT INTO debtor_credits (customerId, customerName, amount, lastUpdated)
                    SELECT customerId, customerName, amount, lastUpdated FROM (
                        SELECT customerId, customerName, remainingBalance as amount, timestamp as lastUpdated
                        FROM settlement_history
                        WHERE id IN (SELECT MAX(id) FROM settlement_history GROUP BY customerId)
                        
                        UNION ALL
                        
                        SELECT customerId, customerName, SUM(totalAmount - paidAmount) as amount, MAX(date) as lastUpdated
                        FROM orders
                        WHERE customerId NOT IN (SELECT DISTINCT customerId FROM settlement_history)
                        GROUP BY customerId
                    )
                """.trimIndent())
                
                Log.d("DatabaseMigration", "Migration 14 to 15 completed successfully")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 13 to 14 (Adding Foreign Key Indices)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_OrderItem_orderId ON OrderItem (orderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_orderId ON photos (orderId)")
                Log.d("DatabaseMigration", "Migration 13 to 14 completed successfully")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 12 to 13 (Relative Path Healing)")
                
                // Heal printer_references
                val refCursor = db.query("SELECT id, imagePath FROM printer_references")
                while (refCursor.moveToNext()) {
                    val id = refCursor.getInt(0)
                    val path = refCursor.getString(1)
                    if (path != null && path.contains("/")) {
                        val filename = path.substringAfterLast("/")
                        db.execSQL("UPDATE printer_references SET imagePath = '$filename' WHERE id = $id")
                    }
                }
                refCursor.close()

                // Heal photos
                val photoCursor = db.query("SELECT id, filePath FROM photos")
                while (photoCursor.moveToNext()) {
                    val id = photoCursor.getInt(0)
                    val path = photoCursor.getString(1)
                    if (path != null && path.contains("/")) {
                        val filename = path.substringAfterLast("/")
                        db.execSQL("UPDATE photos SET filePath = '$filename' WHERE id = $id")
                    }
                }
                photoCursor.close()

                Log.d("DatabaseMigration", "Migration 12 to 13 completed successfully")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 11 to 12 (Transaction Clarity)")
                
                // 1. Orders
                db.execSQL("ALTER TABLE orders ADD COLUMN previousBalance REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE orders ADD COLUMN transactionAmount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE orders ADD COLUMN newBalance REAL NOT NULL DEFAULT 0.0")

                // 2. Settlement History
                db.execSQL("ALTER TABLE settlement_history ADD COLUMN transactionAmount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE settlement_history ADD COLUMN newBalance REAL NOT NULL DEFAULT 0.0")
                
                // Backfill settlement_history
                db.execSQL("UPDATE settlement_history SET newBalance = remainingBalance")
                db.execSQL("UPDATE settlement_history SET transactionAmount = remainingBalance - previousBalance")

                // 3. Beauty Transactions
                db.execSQL("ALTER TABLE beauty_transactions ADD COLUMN previousBalance REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE beauty_transactions ADD COLUMN transactionAmount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE beauty_transactions ADD COLUMN newBalance REAL NOT NULL DEFAULT 0.0")
                
                Log.d("DatabaseMigration", "Migration 11 to 12 completed successfully")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 10 to 11 (Customer Identity Refactor)")
                
                // 1. Create customers table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS customers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        phoneNumber TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_customers_normalizedName ON customers (normalizedName)")

                // 2. Insert unique customers from existing tables
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT OR IGNORE INTO customers (displayName, normalizedName, createdAt, updatedAt)
                    SELECT customerName, LOWER(TRIM(customerName)), $now, $now
                    FROM (
                        SELECT customerName FROM orders
                        UNION
                        SELECT customerName FROM settlement_history
                        UNION
                        SELECT customerName FROM debtor_credits
                        UNION
                        SELECT customerName FROM external_ledger WHERE customerName IS NOT NULL
                    )
                """.trimIndent())

                // 3. Add customerId column to existing tables
                db.execSQL("ALTER TABLE orders ADD COLUMN customerId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settlement_history ADD COLUMN customerId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE external_ledger ADD COLUMN customerId INTEGER")

                // 4. Map customerId from customers table
                Log.d("DatabaseMigration", "Mapping customer IDs in orders...")
                db.execSQL("""
                    UPDATE orders SET customerId = (
                        SELECT id FROM customers WHERE normalizedName = LOWER(TRIM(orders.customerName))
                    )
                """.trimIndent())
                
                Log.d("DatabaseMigration", "Mapping customer IDs in settlement_history...")
                db.execSQL("""
                    UPDATE settlement_history SET customerId = (
                        SELECT id FROM customers WHERE normalizedName = LOWER(TRIM(settlement_history.customerName))
                    )
                """.trimIndent())
                
                Log.d("DatabaseMigration", "Mapping customer IDs in external_ledger...")
                db.execSQL("""
                    UPDATE external_ledger SET customerId = (
                        SELECT id FROM customers WHERE normalizedName = LOWER(TRIM(external_ledger.customerName))
                    ) WHERE customerName IS NOT NULL
                """.trimIndent())

                // 5. Rebuild debtor_credits table to change Primary Key from customerName to customerId
                db.execSQL("""
                    CREATE TABLE debtor_credits_new (
                        customerId INTEGER PRIMARY KEY NOT NULL,
                        customerName TEXT NOT NULL,
                        amount REAL NOT NULL,
                        lastUpdated INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO debtor_credits_new (customerId, customerName, amount, lastUpdated)
                    SELECT c.id, dc.customerName, SUM(dc.amount), MAX(dc.lastUpdated)
                    FROM debtor_credits dc
                    JOIN customers c ON c.normalizedName = LOWER(TRIM(dc.customerName))
                    GROUP BY c.id
                """.trimIndent())

                db.execSQL("DROP TABLE debtor_credits")
                db.execSQL("ALTER TABLE debtor_credits_new RENAME TO debtor_credits")
                
                Log.d("DatabaseMigration", "Migration 10 to 11 completed successfully")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration from version 8 to 9 (Dynamic Rebuild)")
                try {
                    // 1. Get existing columns to detect if 'type' or 'note' already exist
                    val columns = mutableSetOf<String>()
                    val cursor = db.query("PRAGMA table_info(settlement_history)")
                    while (cursor.moveToNext()) {
                        val nameIndex = cursor.getColumnIndex("name")
                        if (nameIndex != -1) {
                            columns.add(cursor.getString(nameIndex))
                        }
                    }
                    cursor.close()

                    // 2. Create new table with correct schema
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS settlement_history_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            customerName TEXT NOT NULL,
                            previousBalance REAL NOT NULL,
                            settledAmount REAL NOT NULL,
                            remainingBalance REAL NOT NULL,
                            timestamp INTEGER NOT NULL,
                            type TEXT NOT NULL DEFAULT 'PAYMENT',
                            note TEXT NOT NULL DEFAULT ''
                        )
                    """.trimIndent())

                    // 3. Build dynamic SELECT based on existing columns
                    val typeSelect = if (columns.contains("type")) "COALESCE(type, 'PAYMENT')" else "'PAYMENT'"
                    val noteSelect = if (columns.contains("note")) "COALESCE(note, '')" else "''"

                    // 4. Copy data safely
                    db.execSQL("""
                        INSERT INTO settlement_history_new (
                            id, customerName, previousBalance, settledAmount, remainingBalance, timestamp, type, note
                        )
                        SELECT 
                            id,
                            customerName,
                            IFNULL(previousBalance, 0),
                            IFNULL(settledAmount, 0),
                            IFNULL(remainingBalance, 0),
                            timestamp,
                            $typeSelect,
                            $noteSelect
                        FROM settlement_history
                    """.trimIndent())

                    // 5. Drop and Rename
                    db.execSQL("DROP TABLE settlement_history")
                    db.execSQL("ALTER TABLE settlement_history_new RENAME TO settlement_history")
                    
                    Log.d("DatabaseMigration", "Migration from 8 to 9 completed successfully")
                } catch (e: Exception) {
                    Log.e("DatabaseMigration", "CRITICAL: Migration 8 to 9 failed", e)
                    throw e
                }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE debtor_credits ADD COLUMN lastUpdated INTEGER NOT NULL DEFAULT " + System.currentTimeMillis())
            }
        }
    }
}
