package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tadiwaprintbuddy.app.BuildConfig

@Database(
    entities = [Order::class, OrderItem::class, Photo::class, DebtorCredit::class, PrinterReference::class, SettlementHistory::class, ExternalLedger::class, BeautyTransaction::class],
    version = 10,
    exportSchema = false
)
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
                ).addMigrations(MIGRATION_8_9, MIGRATION_9_10)

                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
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
