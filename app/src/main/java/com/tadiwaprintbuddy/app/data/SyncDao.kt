package com.tadiwaprintbuddy.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(entry: SyncOutbox): Long

    @Query("SELECT * FROM sync_outbox WHERE status = 'PENDING_UPLOAD' ORDER BY createdAt ASC")
    fun getPendingUploads(): Flow<List<SyncOutbox>>

    @Query("SELECT * FROM sync_outbox WHERE status = 'PENDING_UPLOAD' ORDER BY createdAt ASC")
    suspend fun getPendingUploadsOnce(): List<SyncOutbox>

    @Update
    suspend fun updateOutbox(entry: SyncOutbox): Int

    @Delete
    suspend fun deleteOutbox(entry: SyncOutbox): Int

    @Query("DELETE FROM sync_outbox WHERE status = 'SYNCED'")
    suspend fun clearSyncedEntries(): Int

    // Batch initialization helpers for syncId
    @Query("SELECT id FROM customers WHERE syncId = ''")
    suspend fun getCustomersNeedingSyncId(): List<Long>

    @Query("UPDATE customers SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCustomerSyncId(id: Long, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM orders WHERE syncId = ''")
    suspend fun getOrdersNeedingSyncId(): List<Int>

    @Query("UPDATE orders SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateOrderSyncId(id: Int, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM `OrderItem` WHERE syncId = ''")
    suspend fun getOrderItemsNeedingSyncId(): List<Int>

    @Query("UPDATE `OrderItem` SET syncId = :syncId WHERE id = :id")
    suspend fun updateOrderItemSyncId(id: Int, syncId: String): Int

    @Query("SELECT id FROM settlement_history WHERE syncId = ''")
    suspend fun getSettlementsNeedingSyncId(): List<Int>

    @Query("UPDATE settlement_history SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSettlementSyncId(id: Int, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM expenses WHERE syncId = ''")
    suspend fun getExpensesNeedingSyncId(): List<Int>

    @Query("UPDATE expenses SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateExpenseSyncId(id: Int, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM stock_items WHERE syncId = ''")
    suspend fun getStockItemsNeedingSyncId(): List<Int>

    @Query("UPDATE stock_items SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStockItemSyncId(id: Int, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM notes WHERE syncId = ''")
    suspend fun getNotesNeedingSyncId(): List<Int>

    @Query("UPDATE notes SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNoteSyncId(id: Int, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM beauty_transactions WHERE syncId = ''")
    suspend fun getBeautyTransactionsNeedingSyncId(): List<Int>

    @Query("UPDATE beauty_transactions SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBeautyTransactionSyncId(id: Int, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM external_ledger WHERE syncId = ''")
    suspend fun getExternalLedgerNeedingSyncId(): List<Int>

    @Query("UPDATE external_ledger SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateExternalLedgerSyncId(id: Int, syncId: String, updatedAt: Long): Int

    @Query("SELECT id FROM photos WHERE syncId = ''")
    suspend fun getPhotosNeedingSyncId(): List<Int>

    @Query("UPDATE photos SET syncId = :syncId WHERE id = :id")
    suspend fun updatePhotoSyncId(id: Int, syncId: String): Int

    @Query("SELECT id FROM printer_references WHERE syncId = ''")
    suspend fun getPrinterReferencesNeedingSyncId(): List<Int>

    @Query("UPDATE printer_references SET syncId = :syncId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePrinterReferenceSyncId(id: Int, syncId: String, updatedAt: Long): Int

    // Fetching by syncId
    @Query("SELECT * FROM customers WHERE syncId = :syncId")
    suspend fun getCustomerBySyncId(syncId: String): CustomerEntity?

    @Query("SELECT * FROM orders WHERE syncId = :syncId")
    suspend fun getOrderBySyncId(syncId: String): Order?

    @Query("SELECT * FROM `OrderItem` WHERE syncId = :syncId")
    suspend fun getOrderItemBySyncId(syncId: String): OrderItem?

    @Query("SELECT * FROM settlement_history WHERE syncId = :syncId")
    suspend fun getSettlementBySyncId(syncId: String): SettlementHistory?

    @Query("SELECT * FROM expenses WHERE syncId = :syncId")
    suspend fun getExpenseBySyncId(syncId: String): Expense?

    @Query("SELECT * FROM stock_items WHERE syncId = :syncId")
    suspend fun getStockItemBySyncId(syncId: String): StockItem?

    @Query("SELECT * FROM notes WHERE syncId = :syncId")
    suspend fun getNoteBySyncId(syncId: String): Note?

    @Query("SELECT * FROM beauty_transactions WHERE syncId = :syncId")
    suspend fun getBeautyTransactionBySyncId(syncId: String): BeautyTransaction?

    @Query("SELECT * FROM external_ledger WHERE syncId = :syncId")
    suspend fun getExternalLedgerBySyncId(syncId: String): ExternalLedger?

    @Query("SELECT * FROM printer_references WHERE syncId = :syncId")
    suspend fun getPrinterReferenceBySyncId(syncId: String): PrinterReference?

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun getOrderById(id: Int): Order?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomer(customer: CustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: Note): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: Order): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderItem(item: OrderItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettlement(settlement: SettlementHistory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpense(expense: Expense): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockItem(item: StockItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBeautyTransaction(transaction: BeautyTransaction): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExternalLedger(ledger: ExternalLedger): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrinterReference(ref: PrinterReference): Long

    @Query("UPDATE customers SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markCustomerDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE notes SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markNoteDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE expenses SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markExpenseDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE stock_items SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markStockItemDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE printer_references SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markPrinterReferenceDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE orders SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markOrderDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE `OrderItem` SET deletedAt = :deletedAt WHERE syncId = :syncId")
    suspend fun markOrderItemDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE settlement_history SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markSettlementDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE beauty_transactions SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markBeautyTransactionDeleted(syncId: String, deletedAt: Long): Int

    @Query("UPDATE external_ledger SET deletedAt = :deletedAt, syncStatus = 'SYNCED' WHERE syncId = :syncId")
    suspend fun markExternalLedgerDeleted(syncId: String, deletedAt: Long): Int

    // Backfill Helpers for Sync-based Foreign Keys
    @Query("SELECT id, customerId FROM orders WHERE customerSyncId = '' AND customerId != 0")
    suspend fun getOrdersNeedingCustomerSyncId(): List<OrderIds>

    @Query("UPDATE orders SET customerSyncId = :customerSyncId WHERE id = :id")
    suspend fun updateOrderCustomerSyncId(id: Int, customerSyncId: String): Int

    @Query("SELECT id, orderId FROM `OrderItem` WHERE orderSyncId = '' AND orderId != 0")
    suspend fun getOrderItemsNeedingOrderSyncId(): List<OrderItemIds>

    @Query("UPDATE `OrderItem` SET orderSyncId = :orderSyncId WHERE id = :id")
    suspend fun updateOrderItemOrderSyncId(id: Int, orderSyncId: String): Int

    @Query("SELECT id, customerId, originId FROM settlement_history WHERE customerSyncId = ''")
    suspend fun getSettlementsNeedingGlobalIds(): List<SettlementIds>

    @Query("UPDATE settlement_history SET customerSyncId = :customerSyncId, originSyncId = :originSyncId WHERE id = :id")
    suspend fun updateSettlementGlobalIds(id: Int, customerSyncId: String, originSyncId: String?): Int

    @Query("SELECT id, customerId, orderId FROM external_ledger WHERE customerSyncId IS NULL")
    suspend fun getExternalLedgersNeedingGlobalIds(): List<ExternalLedgerIds>

    @Query("UPDATE external_ledger SET customerSyncId = :customerSyncId, orderSyncId = :orderSyncId WHERE id = :id")
    suspend fun updateExternalLedgerGlobalIds(id: Int, customerSyncId: String?, orderSyncId: String?): Int
}

data class OrderIds(val id: Int, val customerId: Long)
data class OrderItemIds(val id: Int, val orderId: Int)
data class SettlementIds(val id: Int, val customerId: Long, val originId: Int?)
data class ExternalLedgerIds(val id: Int, val customerId: Long?, val orderId: Int?)
