package com.tadiwaprintbuddy.app

import android.content.Context
import android.util.Log
import androidx.work.*
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.DebugTags
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncManager private constructor(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context).also { INSTANCE = it }
            }
        }
    }

    suspend fun initializeSyncIds() {
        val database = AppDatabase.getDatabase(appContext)
        val syncDao = database.syncDao()
        
        Log.i(DebugTags.SYNC_INIT, "Starting Sync ID initialization for existing records...")
        
        // 1. Customers
        syncDao.getCustomersNeedingSyncId().forEach { id ->
            syncDao.updateCustomerSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 2. Orders
        syncDao.getOrdersNeedingSyncId().forEach { id ->
            syncDao.updateOrderSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 3. OrderItems
        syncDao.getOrderItemsNeedingSyncId().forEach { id ->
            syncDao.updateOrderItemSyncId(id, UUID.randomUUID().toString())
        }

        // 4. Settlements
        syncDao.getSettlementsNeedingSyncId().forEach { id ->
            syncDao.updateSettlementSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 5. Expenses
        syncDao.getExpensesNeedingSyncId().forEach { id ->
            syncDao.updateExpenseSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 6. Stock
        syncDao.getStockItemsNeedingSyncId().forEach { id ->
            syncDao.updateStockItemSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 7. Notes
        syncDao.getNotesNeedingSyncId().forEach { id ->
            syncDao.updateNoteSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 8. Beauty Transactions
        syncDao.getBeautyTransactionsNeedingSyncId().forEach { id ->
            syncDao.updateBeautyTransactionSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 9. External Ledger
        syncDao.getExternalLedgerNeedingSyncId().forEach { id ->
            syncDao.updateExternalLedgerSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 10. Photos
        syncDao.getPhotosNeedingSyncId().forEach { id ->
            syncDao.updatePhotoSyncId(id, UUID.randomUUID().toString())
        }

        // 11. Printer References
        syncDao.getPrinterReferencesNeedingSyncId().forEach { id ->
            syncDao.updatePrinterReferenceSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }

        // 12. Global Foreign Key Backfill
        Log.i(DebugTags.SYNC_INIT, "Starting global foreign key backfill...")
        
        // Orders -> customerSyncId
        syncDao.getOrdersNeedingCustomerSyncId().forEach { ids ->
            syncDao.getCustomerById(ids.customerId)?.let { customer ->
                syncDao.updateOrderCustomerSyncId(ids.id, customer.syncId)
            }
        }
        
        // OrderItems -> orderSyncId
        syncDao.getOrderItemsNeedingOrderSyncId().forEach { ids ->
            syncDao.getOrderById(ids.orderId)?.let { order ->
                syncDao.updateOrderItemOrderSyncId(ids.id, order.syncId)
            }
        }

        // Settlements -> customerSyncId, originSyncId
        syncDao.getSettlementsNeedingGlobalIds().forEach { ids ->
            val customer = syncDao.getCustomerById(ids.customerId)
            val order = ids.originId?.let { syncDao.getOrderById(it) }
            if (customer != null || order != null) {
                syncDao.updateSettlementGlobalIds(ids.id, customer?.syncId ?: "", order?.syncId)
            }
        }
        
        // ExternalLedger -> customerSyncId, orderSyncId
        syncDao.getExternalLedgersNeedingGlobalIds().forEach { ids ->
            val customer = ids.customerId?.let { syncDao.getCustomerById(it) }
            val order = ids.orderId?.let { syncDao.getOrderById(it) }
            if (customer != null || order != null) {
                syncDao.updateExternalLedgerGlobalIds(ids.id, customer?.syncId, order?.syncId)
            }
        }

        Log.i(DebugTags.SYNC_INIT, "Sync ID initialization and backfill completed.")
    }

    fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            "cloud_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    fun requestImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueue(syncRequest)
    }
}
