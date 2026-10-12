package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.tadiwaprintbuddy.app.BuildConfig
import java.math.BigDecimal

@Database(
    entities = [Order::class, OrderItem::class, Photo::class, DebtorCredit::class, PrinterReference::class, SettlementHistory::class, ExternalLedger::class, UpiAccountTransaction::class, BeautyTransaction::class, CustomerEntity::class, Expense::class, StockItem::class, Note::class, SyncOutbox::class, DeferredSync::class, HomeOrderDraft::class],
    version = 44,
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
                    MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37,
                        MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41,
                        MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44
                )

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 41 to 42 (Adding printCount to orders, settlement_history, debtor_credits)")
                database.execSQL("ALTER TABLE `orders` ADD COLUMN `printCount` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `printCount` INTEGER")
                database.execSQL("ALTER TABLE `debtor_credits` ADD COLUMN `printCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `home_order_draft` (
                        `id` INTEGER NOT NULL,
                        `customerName` TEXT NOT NULL,
                        `quantityInput` TEXT NOT NULL,
                        `priceInput` TEXT NOT NULL,
                        `receivedAmount` TEXT,
                        `calculatorItemsJson` TEXT NOT NULL,
                        `calculatorTotalPages` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `home_order_draft` ADD COLUMN `paymentMethod` TEXT NOT NULL DEFAULT 'CASH'")
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 37 to 38 (Adding paymentMethod to settlement_history)")
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `paymentMethod` TEXT")
                
                // Optional: Backfill based on existing note hints
                database.execSQL("UPDATE `settlement_history` SET `paymentMethod` = 'UPI' WHERE `note` LIKE '%UPI%'")
                database.execSQL("UPDATE `settlement_history` SET `paymentMethod` = 'CASH' WHERE `note` LIKE '%CASH%'")
                database.execSQL("UPDATE `settlement_history` SET `paymentMethod` = 'CREDIT' WHERE `ledgerEntryType` = 'CREDIT'")
            }
        }

        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 38 to 39 (Beauty -> UPI Account table copy)")

                // 1. Create the canonical upi_account_transactions table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `upi_account_transactions` (
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
                        `deletedAt` INTEGER,
                        `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())

                // 2. Copy legacy rows once, preserving their IDs unless a collision exists.
                database.execSQL("""
                    INSERT INTO `upi_account_transactions` (id, amount, type, note, timestamp, previousBalance, transactionAmount, newBalance, syncId, updatedAt, deletedAt, syncStatus)
                    SELECT
                        CASE WHEN EXISTS (
                            SELECT 1 FROM `upi_account_transactions` existing WHERE existing.id = legacy.id
                        ) THEN NULL ELSE legacy.id END,
                        legacy.amount, legacy.type, legacy.note, legacy.timestamp,
                        legacy.previousBalance, legacy.transactionAmount, legacy.newBalance,
                        legacy.syncId, legacy.updatedAt, legacy.deletedAt, legacy.syncStatus
                    FROM `beauty_transactions` legacy
                    WHERE NOT EXISTS (
                        SELECT 1 FROM `upi_account_transactions` existing WHERE existing.syncId = legacy.syncId
                    )
                """.trimIndent())

                // 3. Create index on timestamp
                database.execSQL("CREATE INDEX IF NOT EXISTS `idx_upi_account_timestamp` ON `upi_account_transactions` (`timestamp`)")
                // Verification: compare row counts and report mismatches
                var upiCount: Long = -1
                var beautyCount: Long = -1
                var missingInUpi: Long = -1
                var cursor = database.query("SELECT COUNT(*) FROM `upi_account_transactions`")
                try {
                    if (cursor.moveToFirst()) upiCount = cursor.getLong(0)
                } finally {
                    cursor.close()
                }
                cursor = database.query("SELECT COUNT(*) FROM `beauty_transactions`")
                try {
                    if (cursor.moveToFirst()) beautyCount = cursor.getLong(0)
                } finally {
                    cursor.close()
                }
                cursor = database.query("SELECT COUNT(*) FROM `beauty_transactions` b LEFT JOIN `upi_account_transactions` u ON b.syncId = u.syncId WHERE u.syncId IS NULL")
                try {
                    if (cursor.moveToFirst()) missingInUpi = cursor.getLong(0)
                } finally {
                    cursor.close()
                }

                Log.d("DatabaseMigration", "Migration 38 to 39 completed: upi_account_transactions created and data copied from beauty_transactions. upiCount=$upiCount, beautyCount=$beautyCount, missingInUpi=$missingInUpi")

                if (missingInUpi > 0) {
                    Log.e("DatabaseMigration", "Migration WARNING: $missingInUpi rows from beauty_transactions are missing in upi_account_transactions. Investigate before releasing this migration to production.")
                }
            }
        }

                val MIGRATION_39_40 = object : Migration(39, 40) {
                        override fun migrate(database: SupportSQLiteDatabase) {
                                Log.d("DatabaseMigration", "Starting migration 39 to 40 (UPI income duplicate classification)")
                                database.execSQL("""
                                        UPDATE `settlement_history`
                                        SET `isShadowDuplicate` = 1
                                        WHERE `settlement_history`.`ledgerEntryType` = 'UPI_ACCOUNT_TOPUP'
                                            AND `settlement_history`.`isShadowDuplicate` = 0
                                            AND (
                                                (
                                                    (`settlement_history`.`note` LIKE 'Direct Pay - Order #%'
                                                     OR `settlement_history`.`note` LIKE 'Payment Order #%'
                                                     OR `settlement_history`.`note` LIKE 'Overpayment Order #%')
                                                    AND EXISTS (
                                                        SELECT 1
                                                        FROM `settlement_history` payment
                                                        WHERE payment.`originId` = CAST(SUBSTR(`settlement_history`.`note`, INSTR(`settlement_history`.`note`, '#') + 1) AS INTEGER)
                                                            AND payment.`ledgerEntryType` = CASE
                                                                WHEN `settlement_history`.`note` LIKE 'Overpayment Order #%' THEN 'CREDIT'
                                                                ELSE 'PAYMENT'
                                                            END
                                                            AND payment.`paymentMethod` = 'UPI'
                                                            AND payment.`settledAmount` = `settlement_history`.`settledAmount`
                                                            AND payment.`deletedAt` IS NULL
                                                    )
                                                )
                                                OR (
                                                    `settlement_history`.`note` = 'Debt Settlement'
                                                    AND (
                                                        SELECT ROUND(SUM(CAST(payment.`settledAmount` AS REAL)), 2)
                                                        FROM `settlement_history` payment
                                                        WHERE payment.`ledgerEntryType` IN ('PAYMENT', 'CREDIT')
                                                            AND payment.`paymentMethod` = 'UPI'
                                                            AND ABS(payment.`timestamp` - `settlement_history`.`timestamp`) <= 10000
                                                            AND payment.`customerId` != 0
                                                            AND payment.`deletedAt` IS NULL
                                                    ) = ROUND(CAST(`settlement_history`.`settledAmount` AS REAL), 2)
                                                )
                                            )
                                """.trimIndent())
                        }
                }

                val MIGRATION_40_41 = object : Migration(40, 41) {
                    override fun migrate(database: SupportSQLiteDatabase) {
                        Log.d("DatabaseMigration", "Starting migration 40 to 41 (project UPI account receipts into Order History)")
                        database.execSQL("""
                            INSERT INTO `orders` (
                                totalAmount, date, customerName, paidAmount, paymentMethod, customerId,
                                previousBalance, transactionAmount, newBalance, paymentStatus, orderStatus,
                                receivedAmount, customerSyncId, syncId, updatedAt, deletedAt, syncStatus
                            )
                            SELECT
                                wallet.amount, wallet.timestamp, 'UPI Account', wallet.amount, 'UPI', 0,
                                '0', '0', '0', 'PAID', 'ACTIVE', NULL, '', wallet.syncId,
                                wallet.updatedAt, NULL, wallet.syncStatus
                            FROM `upi_account_transactions` wallet
                            INNER JOIN `settlement_history` receipt
                                ON receipt.originSyncId = wallet.syncId
                               AND receipt.ledgerEntryType = 'UPI_ACCOUNT_TOPUP'
                               AND receipt.isShadowDuplicate = 0
                               AND receipt.deletedAt IS NULL
                            WHERE wallet.type = 'ADD'
                              AND wallet.deletedAt IS NULL
                              AND NOT EXISTS (
                                  SELECT 1 FROM `orders` existing WHERE existing.syncId = wallet.syncId
                              )
                        """.trimIndent())

                        database.execSQL("""
                            UPDATE `settlement_history`
                            SET originId = (
                                SELECT projected.id FROM `orders` projected
                                WHERE projected.syncId = settlement_history.originSyncId
                                  AND projected.customerName = 'UPI Account'
                                  AND projected.deletedAt IS NULL
                            )
                            WHERE ledgerEntryType = 'UPI_ACCOUNT_TOPUP'
                              AND isShadowDuplicate = 0
                              AND deletedAt IS NULL
                              AND originId IS NULL
                              AND EXISTS (
                                  SELECT 1 FROM `orders` projected
                                  WHERE projected.syncId = settlement_history.originSyncId
                                    AND projected.customerName = 'UPI Account'
                                    AND projected.deletedAt IS NULL
                              )
                        """.trimIndent())

                        database.execSQL("""
                            INSERT INTO `sync_outbox` (
                                entityType, entitySyncId, operation, createdAt, attemptCount,
                                lastError, idempotencyKey, status
                            )
                            SELECT 'ORDER', projected.syncId, 'CREATE', projected.updatedAt, 0,
                                   NULL, projected.syncId || ':ORDER:CREATE:UPI_ACCOUNT_HISTORY', 'PENDING_UPLOAD'
                            FROM `orders` projected
                            INNER JOIN `upi_account_transactions` wallet ON wallet.syncId = projected.syncId
                            WHERE projected.customerName = 'UPI Account'
                              AND wallet.type = 'ADD'
                              AND EXISTS (
                                  SELECT 1 FROM `settlement_history` receipt
                                  WHERE receipt.originSyncId = wallet.syncId
                                    AND receipt.ledgerEntryType = 'UPI_ACCOUNT_TOPUP'
                                    AND receipt.isShadowDuplicate = 0
                                    AND receipt.deletedAt IS NULL
                              )
                              AND NOT EXISTS (
                                  SELECT 1 FROM `sync_outbox` queued
                                  WHERE queued.entityType = 'ORDER'
                                    AND queued.entitySyncId = projected.syncId
                                    AND queued.operation IN ('CREATE', 'UPDATE')
                              )
                        """.trimIndent())
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
                val monetaryColumns = linkedMapOf(
                    "orders" to listOf(
                        "totalAmount", "paidAmount", "previousBalance", "transactionAmount",
                        "newBalance", "receivedAmount"
                    ),
                    "OrderItem" to listOf("price"),
                    "photos" to emptyList(),
                    "settlement_history" to listOf(
                        "previousBalance", "settledAmount", "remainingBalance", "transactionAmount",
                        "newBalance", "receivedAmount"
                    ),
                    "debtor_credits" to listOf("amount"),
                    "expenses" to listOf("amount"),
                    "beauty_transactions" to listOf(
                        "amount", "previousBalance", "transactionAmount", "newBalance"
                    ),
                    "external_ledger" to listOf("amount")
                )
                val snapshots = monetaryColumns.mapValues { (table, columns) ->
                    val snapshot = "_migration_34_35_$table"
                    val normalizedColumns = columns.joinToString { column ->
                        "NULL AS `_migration_money_$column`"
                    }
                    val projection = listOf("rowid AS `_migration_rowid`", "*", normalizedColumns)
                        .filter { it.isNotEmpty() }
                        .joinToString()
                    database.execSQL(
                        "CREATE TEMP TABLE `$snapshot` AS SELECT $projection FROM `$table`"
                    )
                    snapshot
                }

                fun convertMonetaryValue(
                    table: String,
                    column: String,
                    rowId: Long,
                    cursor: Cursor
                ): String? {
                    val valueType = cursor.getType(1)
                    return when (valueType) {
                        Cursor.FIELD_TYPE_NULL -> {
                            if (column != "receivedAmount") {
                                throw IllegalStateException(
                                    "Cannot migrate NULL monetary value in $table.$column at rowid $rowId"
                                )
                            }
                            null
                        }
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(1).toString()
                        Cursor.FIELD_TYPE_FLOAT -> {
                            val value = cursor.getDouble(1)
                            if (value.isNaN() || value.isInfinite()) {
                                throw IllegalStateException(
                                    "Cannot migrate non-finite monetary value in $table.$column at rowid $rowId"
                                )
                            }
                            BigDecimal.valueOf(value).toPlainString()
                        }
                        Cursor.FIELD_TYPE_STRING -> {
                            val value = cursor.getString(1)
                            try {
                                BigDecimal(value)
                            } catch (exception: NumberFormatException) {
                                throw IllegalStateException(
                                    "Cannot migrate invalid decimal text in $table.$column at rowid $rowId",
                                    exception
                                )
                            }
                            value
                        }
                        else -> throw IllegalStateException(
                            "Cannot migrate unsupported SQLite storage type $valueType " +
                                "for $table.$column at rowid $rowId"
                        )
                    }
                }

                Log.d(
                    "DatabaseMigration",
                    "Starting migration 34 to 35 (preserving decimal text and canonicalizing finite numeric values)"
                )
                for ((table, columns) in monetaryColumns) {
                    val snapshot = snapshots.getValue(table)
                    for (column in columns) {
                        val cursor = database.query(
                            "SELECT `_migration_rowid`, `$column` FROM `$snapshot`"
                        )
                        try {
                            while (cursor.moveToNext()) {
                                val rowId = cursor.getLong(0)
                                val normalized = convertMonetaryValue(table, column, rowId, cursor)
                                if (normalized != null) {
                                    database.execSQL(
                                        "UPDATE `$snapshot` SET `_migration_money_$column` = ? " +
                                            "WHERE `_migration_rowid` = ?",
                                        arrayOf<Any?>(normalized, rowId)
                                    )
                                }
                            }
                        } finally {
                            cursor.close()
                        }
                    }
                }

                database.execSQL("DROP TABLE `OrderItem`")
                database.execSQL("DROP TABLE `photos`")

                database.execSQL("""
                    CREATE TABLE `orders_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `totalAmount` TEXT NOT NULL,
                        `date` INTEGER NOT NULL, `customerName` TEXT NOT NULL, `paidAmount` TEXT NOT NULL,
                        `paymentMethod` TEXT NOT NULL, `customerId` INTEGER NOT NULL, `previousBalance` TEXT NOT NULL,
                        `transactionAmount` TEXT NOT NULL, `newBalance` TEXT NOT NULL, `paymentStatus` TEXT NOT NULL,
                        `orderStatus` TEXT NOT NULL, `receivedAmount` TEXT, `customerSyncId` TEXT NOT NULL,
                        `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                        `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `orders_new` (
                        id, totalAmount, date, customerName, paidAmount, paymentMethod, customerId,
                        previousBalance, transactionAmount, newBalance, paymentStatus, orderStatus,
                        receivedAmount, customerSyncId, syncId, updatedAt, deletedAt, syncStatus
                    )
                    SELECT id, `_migration_money_totalAmount`, date, customerName,
                        `_migration_money_paidAmount`, paymentMethod, customerId,
                        `_migration_money_previousBalance`, `_migration_money_transactionAmount`,
                        `_migration_money_newBalance`, paymentStatus, orderStatus,
                        `_migration_money_receivedAmount`, customerSyncId, syncId, updatedAt,
                        deletedAt, syncStatus FROM `${snapshots.getValue("orders")}`
                """.trimIndent())
                database.execSQL("DROP TABLE `orders`")
                database.execSQL("ALTER TABLE `orders_new` RENAME TO `orders`")
                database.execSQL("CREATE INDEX `idx_orders_date` ON `orders` (`date`)")
                database.execSQL("CREATE INDEX `idx_orders_payment_method` ON `orders` (`paymentMethod`)")

                database.execSQL("""
                    CREATE TABLE `OrderItem` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                        `serviceName` TEXT NOT NULL, `price` TEXT NOT NULL, `quantity` INTEGER NOT NULL,
                        `orderSyncId` TEXT NOT NULL, `syncId` TEXT NOT NULL,
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `OrderItem` (id, orderId, serviceName, price, quantity, orderSyncId, syncId)
                    SELECT id, orderId, serviceName, `_migration_money_price`, quantity, orderSyncId, syncId
                    FROM `${snapshots.getValue("OrderItem")}`
                """.trimIndent())
                database.execSQL("CREATE INDEX `index_OrderItem_orderId` ON `OrderItem` (`orderId`)")

                database.execSQL("""
                    CREATE TABLE `photos` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL, `syncId` TEXT NOT NULL,
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `photos` (id, orderId, filePath, syncId)
                    SELECT id, orderId, filePath, syncId FROM `${snapshots.getValue("photos")}`
                """.trimIndent())
                database.execSQL("CREATE INDEX `index_photos_orderId` ON `photos` (`orderId`)")

                database.execSQL("""
                    CREATE TABLE `settlement_history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `customerName` TEXT NOT NULL,
                        `previousBalance` TEXT NOT NULL, `settledAmount` TEXT NOT NULL,
                        `remainingBalance` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL,
                        `note` TEXT NOT NULL, `customerId` INTEGER NOT NULL, `transactionAmount` TEXT NOT NULL,
                        `newBalance` TEXT NOT NULL, `originId` INTEGER, `ledgerEntryType` TEXT NOT NULL,
                        `isShadowDuplicate` INTEGER NOT NULL, `reconciliationStatus` TEXT NOT NULL,
                        `receivedAmount` TEXT, `customerSyncId` TEXT NOT NULL, `originSyncId` TEXT,
                        `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `settlement_history_new` (
                        id, customerName, previousBalance, settledAmount, remainingBalance, timestamp, type,
                        note, customerId, transactionAmount, newBalance, originId, ledgerEntryType,
                        isShadowDuplicate, reconciliationStatus, receivedAmount, customerSyncId, originSyncId,
                        syncId, updatedAt, syncStatus
                    )
                    SELECT id, customerName, `_migration_money_previousBalance`,
                        `_migration_money_settledAmount`, `_migration_money_remainingBalance`,
                        timestamp, type, note, customerId, `_migration_money_transactionAmount`,
                        `_migration_money_newBalance`, originId, ledgerEntryType, isShadowDuplicate,
                        reconciliationStatus, `_migration_money_receivedAmount`, customerSyncId,
                        originSyncId, syncId, updatedAt, syncStatus
                    FROM `${snapshots.getValue("settlement_history")}`
                """.trimIndent())
                database.execSQL("DROP TABLE `settlement_history`")
                database.execSQL("ALTER TABLE `settlement_history_new` RENAME TO `settlement_history`")
                database.execSQL("CREATE INDEX `index_settlement_history_customerId` ON `settlement_history` (`customerId`)")
                database.execSQL("CREATE INDEX `index_settlement_history_originId` ON `settlement_history` (`originId`)")
                database.execSQL("CREATE INDEX `index_settlement_history_originId_ledgerEntryType` ON `settlement_history` (`originId`, `ledgerEntryType`)")
                database.execSQL("CREATE INDEX `idx_settlement_timestamp` ON `settlement_history` (`timestamp`)")

                database.execSQL("""
                    CREATE TABLE `debtor_credits_new` (
                        `customerId` INTEGER NOT NULL, `customerName` TEXT NOT NULL, `amount` TEXT NOT NULL,
                        `lastUpdated` INTEGER NOT NULL, `phoneNumber` TEXT, PRIMARY KEY(`customerId`)
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `debtor_credits_new` (customerId, customerName, amount, lastUpdated, phoneNumber)
                    SELECT customerId, customerName, `_migration_money_amount`, lastUpdated, phoneNumber
                    FROM `${snapshots.getValue("debtor_credits")}`
                """.trimIndent())
                database.execSQL("DROP TABLE `debtor_credits`")
                database.execSQL("ALTER TABLE `debtor_credits_new` RENAME TO `debtor_credits`")
                database.execSQL("CREATE INDEX `idx_debtor_updated` ON `debtor_credits` (`lastUpdated`)")

                database.execSQL("""
                    CREATE TABLE `expenses_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL,
                        `category` TEXT NOT NULL, `amount` TEXT NOT NULL, `timestamp` INTEGER NOT NULL,
                        `note` TEXT, `paymentMethod` TEXT NOT NULL, `syncId` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `expenses_new` (
                        id, title, category, amount, timestamp, note, paymentMethod, syncId, updatedAt,
                        deletedAt, syncStatus
                    )
                    SELECT id, title, category, `_migration_money_amount`, timestamp, note, paymentMethod,
                        syncId, updatedAt, deletedAt, syncStatus FROM `${snapshots.getValue("expenses")}`
                """.trimIndent())
                database.execSQL("DROP TABLE `expenses`")
                database.execSQL("ALTER TABLE `expenses_new` RENAME TO `expenses`")
                database.execSQL("CREATE INDEX `idx_expenses_timestamp` ON `expenses` (`timestamp`)")

                database.execSQL("""
                    CREATE TABLE `beauty_transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `amount` TEXT NOT NULL,
                        `type` TEXT NOT NULL, `note` TEXT, `timestamp` INTEGER NOT NULL,
                        `previousBalance` TEXT NOT NULL, `transactionAmount` TEXT NOT NULL,
                        `newBalance` TEXT NOT NULL, `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `beauty_transactions_new` (
                        id, amount, type, note, timestamp, previousBalance, transactionAmount, newBalance,
                        syncId, updatedAt, syncStatus
                    )
                    SELECT id, `_migration_money_amount`, type, note, timestamp,
                        `_migration_money_previousBalance`, `_migration_money_transactionAmount`,
                        `_migration_money_newBalance`, syncId, updatedAt, syncStatus
                    FROM `${snapshots.getValue("beauty_transactions")}`
                """.trimIndent())
                database.execSQL("DROP TABLE `beauty_transactions`")
                database.execSQL("ALTER TABLE `beauty_transactions_new` RENAME TO `beauty_transactions`")
                database.execSQL("CREATE INDEX `idx_beauty_timestamp` ON `beauty_transactions` (`timestamp`)")

                database.execSQL("""
                    CREATE TABLE `external_ledger_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transactionType` TEXT NOT NULL,
                        `amount` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `customerName` TEXT,
                        `customerId` INTEGER, `orderId` INTEGER, `note` TEXT, `accountHolder` TEXT NOT NULL,
                        `upiId` TEXT NOT NULL, `customerSyncId` TEXT, `orderSyncId` TEXT,
                        `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `external_ledger_new` (
                        id, transactionType, amount, timestamp, customerName, customerId, orderId, note,
                        accountHolder, upiId, customerSyncId, orderSyncId, syncId, updatedAt, syncStatus
                    )
                    SELECT id, transactionType, `_migration_money_amount`, timestamp, customerName,
                        customerId, orderId, note, accountHolder, upiId, customerSyncId, orderSyncId,
                        syncId, updatedAt, syncStatus FROM `${snapshots.getValue("external_ledger")}`
                """.trimIndent())
                database.execSQL("DROP TABLE `external_ledger`")
                database.execSQL("ALTER TABLE `external_ledger_new` RENAME TO `external_ledger`")

                for (snapshot in snapshots.values) {
                    database.execSQL("DROP TABLE `$snapshot`")
                }
                Log.d(
                    "DatabaseMigration",
                    "Migration 34 to 35 completed; decimal columns use TEXT without dropping order dependents."
                )
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
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
                    // customers and notes already have updatedAt from their creation migrations (v11 and v31)
                    if (table != "customers" && table != "notes") {
                        database.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    }
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

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `customerSyncId` TEXT")
                database.execSQL("ALTER TABLE `external_ledger` ADD COLUMN `orderSyncId` TEXT")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
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

        val MIGRATION_30_31 = object : Migration(30, 31) {
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

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `debtor_credits` ADD COLUMN `phoneNumber` TEXT")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Version 29 was a placeholder bump in a previous edit, keeping it empty to maintain chain
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `orders` ADD COLUMN `receivedAmount` REAL")
                database.execSQL("ALTER TABLE `settlement_history` ADD COLUMN `receivedAmount` REAL")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
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

        val MIGRATION_25_26 = object : Migration(25, 26) {
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

        val MIGRATION_24_25 = object : Migration(24, 25) {
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

        val MIGRATION_19_24 = object : Migration(19, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Handle version jump and adding title to expenses
                db.execSQL("ALTER TABLE expenses ADD COLUMN title TEXT NOT NULL DEFAULT 'Manual Expense'")
            }
        }

        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
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

        val MIGRATION_17_18 = object : Migration(17, 18) {
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

        val MIGRATION_18_19 = object : Migration(18, 19) {
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

        val MIGRATION_15_16 = object : Migration(15, 16) {
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

        val MIGRATION_14_15 = object : Migration(14, 15) {
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

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DatabaseMigration", "Starting migration 13 to 14 (Adding Foreign Key Indices)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_OrderItem_orderId ON OrderItem (orderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_orderId ON photos (orderId)")
                Log.d("DatabaseMigration", "Migration 13 to 14 completed successfully")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
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

        val MIGRATION_11_12 = object : Migration(11, 12) {
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

        val MIGRATION_10_11 = object : Migration(10, 11) {
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

        val MIGRATION_8_9 = object : Migration(8, 9) {
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

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE debtor_credits ADD COLUMN lastUpdated INTEGER NOT NULL DEFAULT " + System.currentTimeMillis())
            }
        }
    }
}
