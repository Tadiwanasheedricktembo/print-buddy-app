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
    entities = [Order::class, OrderItem::class, Photo::class, DebtorCredit::class, PrinterReference::class, SettlementHistory::class, ExternalLedger::class, BeautyTransaction::class, CustomerEntity::class, Expense::class, StockItem::class, Note::class, SyncOutbox::class, DeferredSync::class],
    version = 37,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun printDao(): PrintDao
    abstract fun integrityCheckDao(): IntegrityCheckDao
    abstract fun noteDao(): NoteDao
    abstract fun syncDao(): SyncDao
    abstract fun deferredSyncDao(): DeferredSyncDao

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
                    MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_24,
                    MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28,
                    MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33,
                    MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37
                )

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 36 to 37 (Adding DeferredSync table)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `deferred_sync` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `entityType` TEXT NOT NULL, 
                        `entitySyncId` TEXT NOT NULL, 
                        `operation` TEXT NOT NULL, 
                        `data` TEXT NOT NULL, 
                        `timestamp` INTEGER NOT NULL, 
                        `serverUpdatedAt` TEXT, 
                        `serverId` INTEGER, 
                        `idempotencyKey` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 35 to 36 (Sync Metadata Hardening)")
                
                // 1. OrderItem: Add updatedAt and deletedAt
                database.execSQL("ALTER TABLE `OrderItem` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `OrderItem` ADD COLUMN `deletedAt` INTEGER")
                database.execSQL("UPDATE `OrderItem` SET `updatedAt` = " + System.currentTimeMillis())

                // 2. settlement_history: Add deletedAt
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `deletedAt` INTEGER")

                // 3. beauty_transactions: Add deletedAt
                database.execSQL("ALTER TABLE `beauty_transactions` ADD COLUMN `deletedAt` INTEGER")

                // 4. external_ledger: Add deletedAt
                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `deletedAt` INTEGER")
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 34 to 35 (Forensic Precision Hardening - REAL to TEXT)")

                // 1. orders
                database.execSQL("""
                    CREATE TABLE `orders_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `totalAmount` TEXT NOT NULL, 
                        `date` INTEGER NOT NULL, 
                        `customerName` TEXT NOT NULL, 
                        `paidAmount` TEXT NOT NULL, 
                        `paymentMethod` TEXT NOT NULL, 
                        `customerId` INTEGER NOT NULL, 
                        `previousBalance` TEXT NOT NULL, 
                        `transactionAmount` TEXT NOT NULL, 
                        `newBalance` TEXT NOT NULL, 
                        `paymentStatus` TEXT NOT NULL, 
                        `orderStatus` TEXT NOT NULL, 
                        `receivedAmount` TEXT, 
                        `customerSyncId` TEXT NOT NULL, 
                        `syncId` TEXT NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `deletedAt` INTEGER, 
                        `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `orders_new` (
                        id, totalAmount, date, customerName, paidAmount, paymentMethod, 
                        customerId, previousBalance, transactionAmount, newBalance, 
                        paymentStatus, orderStatus, receivedAmount, customerSyncId, 
                        syncId, updatedAt, deletedAt, syncStatus
                    ) 
                    SELECT 
                        id, CAST(totalAmount AS TEXT), date, customerName, CAST(paidAmount AS TEXT), paymentMethod, 
                        customerId, CAST(previousBalance AS TEXT), CAST(transactionAmount AS TEXT), CAST(newBalance AS TEXT), 
                        paymentStatus, orderStatus, CAST(receivedAmount AS TEXT), customerSyncId, 
                        syncId, updatedAt, deletedAt, syncStatus 
                    FROM `orders`
                """.trimIndent())
                database.execSQL("DROP TABLE `orders`")
                database.execSQL("ALTER TABLE `orders_new` RENAME TO `orders`")
                database.execSQL("CREATE INDEX `idx_orders_date` ON `orders` (`date`)")
                database.execSQL("CREATE INDEX `idx_orders_payment_method` ON `orders` (`paymentMethod`)")

                // 2. OrderItem
                database.execSQL("""
                    CREATE TABLE `OrderItem_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `orderId` INTEGER NOT NULL, 
                        `serviceName` TEXT NOT NULL, 
                        `price` TEXT NOT NULL, 
                        `quantity` INTEGER NOT NULL, 
                        `orderSyncId` TEXT NOT NULL, 
                        `syncId` TEXT NOT NULL, 
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `OrderItem_new` (id, orderId, serviceName, price, quantity, orderSyncId, syncId)
                    SELECT id, orderId, serviceName, CAST(price AS TEXT), quantity, orderSyncId, syncId FROM `OrderItem`
                """.trimIndent())
                database.execSQL("DROP TABLE `OrderItem`")
                database.execSQL("ALTER TABLE `OrderItem_new` RENAME TO `OrderItem`")
                database.execSQL("CREATE INDEX `index_OrderItem_orderId` ON `OrderItem` (`orderId`)")

                // 3. settlement_history
                database.execSQL("""
                    CREATE TABLE `settlement_history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `customerName` TEXT NOT NULL, 
                        `previousBalance` TEXT NOT NULL, 
                        `settledAmount` TEXT NOT NULL, 
                        `remainingBalance` TEXT NOT NULL, 
                        `timestamp` INTEGER NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `note` TEXT NOT NULL, 
                        `customerId` INTEGER NOT NULL, 
                        `transactionAmount` TEXT NOT NULL, 
                        `newBalance` TEXT NOT NULL, 
                        `originId` INTEGER, 
                        `ledgerEntryType` TEXT NOT NULL, 
                        `isShadowDuplicate` INTEGER NOT NULL, 
                        `reconciliationStatus` TEXT NOT NULL, 
                        `receivedAmount` TEXT, 
                        `customerSyncId` TEXT NOT NULL, 
                        `originSyncId` TEXT, 
                        `syncId` TEXT NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `settlement_history_new` (
                        id, customerName, previousBalance, settledAmount, remainingBalance, timestamp, 
                        type, note, customerId, transactionAmount, newBalance, originId, 
                        ledgerEntryType, isShadowDuplicate, reconciliationStatus, receivedAmount, 
                        customerSyncId, originSyncId, syncId, updatedAt, syncStatus
                    )
                    SELECT 
                        id, customerName, CAST(previousBalance AS TEXT), CAST(settledAmount AS TEXT), CAST(remainingBalance AS TEXT), timestamp, 
                        type, note, customerId, CAST(transactionAmount AS TEXT), CAST(newBalance AS TEXT), originId, 
                        ledgerEntryType, isShadowDuplicate, reconciliationStatus, CAST(receivedAmount AS TEXT), 
                        customerSyncId, originSyncId, syncId, updatedAt, syncStatus 
                    FROM `settlement_history`
                """.trimIndent())
                database.execSQL("DROP TABLE `settlement_history`")
                database.execSQL("ALTER TABLE `settlement_history_new` RENAME TO `settlement_history`")
                database.execSQL("CREATE INDEX `index_settlement_history_customerId` ON `settlement_history` (`customerId`)")
                database.execSQL("CREATE INDEX `index_settlement_history_originId` ON `settlement_history` (`originId`)")
                database.execSQL("CREATE INDEX `index_settlement_history_originId_ledgerEntryType` ON `settlement_history` (`originId`, `ledgerEntryType`)")
                database.execSQL("CREATE INDEX `idx_settlement_timestamp` ON `settlement_history` (`timestamp`)")

                // 4. debtor_credits
                database.execSQL("""
                    CREATE TABLE `debtor_credits_new` (
                        `customerId` INTEGER PRIMARY KEY NOT NULL, 
                        `customerName` TEXT NOT NULL, 
                        `amount` TEXT NOT NULL, 
                        `lastUpdated` INTEGER NOT NULL, 
                        `phoneNumber` TEXT
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `debtor_credits_new` (customerId, customerName, amount, lastUpdated, phoneNumber)
                    SELECT customerId, customerName, CAST(amount AS TEXT), lastUpdated, phoneNumber FROM `debtor_credits`
                """.trimIndent())
                database.execSQL("DROP TABLE `debtor_credits`")
                database.execSQL("ALTER TABLE `debtor_credits_new` RENAME TO `debtor_credits`")
                database.execSQL("CREATE INDEX `idx_debtor_updated` ON `debtor_credits` (`lastUpdated`)")

                // 5. expenses
                database.execSQL("""
                    CREATE TABLE `expenses_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `amount` TEXT NOT NULL, 
                        `timestamp` INTEGER NOT NULL, 
                        `note` TEXT, 
                        `paymentMethod` TEXT NOT NULL, 
                        `syncId` TEXT NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `deletedAt` INTEGER, 
                        `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `expenses_new` (id, title, category, amount, timestamp, note, paymentMethod, syncId, updatedAt, deletedAt, syncStatus)
                    SELECT id, title, category, CAST(amount AS TEXT), timestamp, note, paymentMethod, syncId, updatedAt, deletedAt, syncStatus FROM `expenses`
                """.trimIndent())
                database.execSQL("DROP TABLE `expenses`")
                database.execSQL("ALTER TABLE `expenses_new` RENAME TO `expenses`")
                database.execSQL("CREATE INDEX `idx_expenses_timestamp` ON `expenses` (`timestamp`)")

                // 6. beauty_transactions
                database.execSQL("""
                    CREATE TABLE `beauty_transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `amount` TEXT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `note` TEXT, 
                        `timestamp` INTEGER NOT NULL, 
                        `previousBalance` TEXT NOT NULL, 
                        `transactionAmount` TEXT NOT NULL, 
                        `newBalance` TEXT NOT NULL, 
                        `syncId` TEXT NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `beauty_transactions_new` (
                        id, amount, type, note, timestamp, previousBalance, transactionAmount, newBalance, syncId, updatedAt, syncStatus
                    )
                    SELECT 
                        id, CAST(amount AS TEXT), type, note, timestamp, CAST(previousBalance AS TEXT), CAST(transactionAmount AS TEXT), CAST(newBalance AS TEXT), syncId, updatedAt, syncStatus 
                    FROM `beauty_transactions`
                """.trimIndent())
                database.execSQL("DROP TABLE `beauty_transactions`")
                database.execSQL("ALTER TABLE `beauty_transactions_new` RENAME TO `beauty_transactions`")
                database.execSQL("CREATE INDEX `idx_beauty_timestamp` ON `beauty_transactions` (`timestamp`)")

                // 7. external_ledger
                database.execSQL("""
                    CREATE TABLE `external_ledger_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `transactionType` TEXT NOT NULL, 
                        `amount` TEXT NOT NULL, 
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
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `external_ledger_new` (
                        id, transactionType, amount, timestamp, customerName, customerId, orderId, note, 
                        accountHolder, upiId, customerSyncId, orderSyncId, syncId, updatedAt, syncStatus
                    )
                    SELECT 
                        id, transactionType, CAST(amount AS TEXT), timestamp, customerName, customerId, orderId, note, 
                        accountHolder, upiId, customerSyncId, orderSyncId, syncId, updatedAt, syncStatus 
                    FROM `external_ledger`
                """.trimIndent())
                database.execSQL("DROP TABLE `external_ledger`")
                database.execSQL("ALTER TABLE `external_ledger_new` RENAME TO `external_ledger`")

                Log.d("DatabaseMigration", "Migration 34 to 35 completed successfully. Monetary columns are now TEXT with explicit CAST and column mapping.")
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Create Sync Outbox
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `entityType` TEXT NOT NULL, 
                        `entitySyncId` TEXT NOT NULL, 
                        `operation` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `attemptCount` INTEGER NOT NULL, 
                        `lastError` TEXT, 
                        `idempotencyKey` TEXT NOT NULL, 
                        `status` TEXT NOT NULL
                    )
                """.trimIndent())

                // 2. Add columns to synchronizable entities
                val tablesWithMetadata = listOf("customers", "orders", "expenses", "stock_items", "notes", "printer_references")
                for (table in tablesWithMetadata) {
                    database.execSQL("ALTER TABLE `$table` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE `$table` ADD COLUMN `deletedAt` INTEGER")
                    database.execSQL("ALTER TABLE `$table` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                }

                // Tables with partial metadata (Append-only or linked)
                database.execSQL("ALTER TABLE `OrderItem` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `photos` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")

                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")

                database.execSQL("ALTER TABLE `beauty_transactions` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `beauty_transactions` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `beauty_transactions` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")

                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
            }
        }

        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `customerSyncId` TEXT")
                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `orderSyncId` TEXT")
            }
        }

        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Orders -> customerSyncId
                database.execSQL("ALTER TABLE `orders` ADD COLUMN `customerSyncId` TEXT NOT NULL DEFAULT ''")
                
                // 2. OrderItem -> orderSyncId
                database.execSQL("ALTER TABLE `OrderItem` ADD COLUMN `orderSyncId` TEXT NOT NULL DEFAULT ''")
                
                // 3. SettlementHistory -> customerSyncId, originSyncId
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `customerSyncId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `originSyncId` TEXT")
            }
        }

        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `content` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `debtor_credits` ADD COLUMN `phoneNumber` TEXT")
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Version 29 was a placeholder bump in a previous edit, keeping it empty to maintain chain
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `orders` ADD COLUMN `receivedAmount` REAL")
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `receivedAmount` REAL")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add columns to orders
                database.execSQL("ALTER TABLE `orders` ADD COLUMN `paymentStatus` TEXT NOT NULL DEFAULT 'PAID'")
                database.execSQL("ALTER TABLE `orders` ADD COLUMN `orderStatus` TEXT NOT NULL DEFAULT 'ACTIVE'")
                
                // Update paymentStatus for existing orders
                database.execSQL("UPDATE `orders` SET `paymentStatus` = 'UNPAID' WHERE `paidAmount` = 0")
                database.execSQL("UPDATE `orders` SET `paymentStatus` = 'PARTIALLY_PAID' WHERE `paidAmount` > 0 AND `paidAmount` < `totalAmount`")
                
                // Fix paymentMethod for legacy credit orders marked as CASH
                database.execSQL("UPDATE `orders` SET `paymentMethod` = 'NONE' WHERE `paidAmount` = 0 AND `paymentMethod` = 'CASH'")

                // Convert unique index to non-unique on settlement_history
                database.execSQL("DROP INDEX IF EXISTS `index_settlement_history_originId_ledgerEntryType`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_settlement_history_originId_ledgerEntryType` ON `settlement_history` (`originId`, `ledgerEntryType`)")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild orders table to match Room expectations (No defaults, Correct indices)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS orders_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        totalAmount REAL NOT NULL,
                        date INTEGER NOT NULL,
                        customerName TEXT NOT NULL,
                        paidAmount REAL NOT NULL,
                        paymentMethod TEXT NOT NULL,
                        customerId INTEGER NOT NULL,
                        previousBalance REAL NOT NULL,
                        transactionAmount REAL NOT NULL,
                        newBalance REAL NOT NULL
                    )
                """.trimIndent())
                
                db.execSQL("""
                    INSERT INTO orders_new (id, totalAmount, date, customerName, paidAmount, paymentMethod, customerId, previousBalance, transactionAmount, newBalance)
                    SELECT id, totalAmount, date, customerName, paidAmount, paymentMethod, customerId, previousBalance, transactionAmount, newBalance FROM orders
                """.trimIndent())
                
                db.execSQL("DROP TABLE orders")
                db.execSQL("ALTER TABLE orders_new RENAME TO orders")
                
                // Recreate Indices
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_date ON orders(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_payment_method ON orders(paymentMethod)")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // PART 2 - DATABASE LAYER: Add Indexes
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_date ON orders(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_payment_method ON orders(paymentMethod)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_beauty_timestamp ON beauty_transactions(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_expenses_timestamp ON expenses(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_debtor_updated ON debtor_credits(lastUpdated)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlement_timestamp ON settlement_history(timestamp)")
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
