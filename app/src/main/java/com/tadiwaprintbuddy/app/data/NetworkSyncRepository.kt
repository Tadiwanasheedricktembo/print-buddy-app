package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.util.Log
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.tadiwaprintbuddy.app.api.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Type
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking as coroutinesRunBlocking

class MissingDependencyException(val type: String, val syncId: String) : Exception("Missing dependency: $type/$syncId")

open class NetworkSyncRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
    private val database: AppDatabase
) {
    private val syncDao = database.syncDao()
    private val printDao = database.printDao()
    private val deferredSyncDao = database.deferredSyncDao()

    private val gson = GsonBuilder()
        .registerTypeAdapter(BigDecimal::class.java, BigDecimalAdapter())
        .registerTypeAdapter(Long::class.java, LongAdapter())
        .create()

    private val api = Retrofit.Builder()
        .baseUrl(NetworkConfig.BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(TadiwaApi::class.java)

    companion object {
        fun getInstance(context: Context): NetworkSyncRepository {
            return NetworkSyncRepository(
                context.applicationContext,
                AuthRepository.getInstance(context),
                AppDatabase.getDatabase(context)
            )
        }
    }

    suspend fun pushPendingChanges(): Result<Unit> {
        val token = authRepository.getAuthHeader() ?: return Result.failure(Exception("Not authenticated"))
        
        // P0 Memory Hardening: Process pending uploads in batches
        val pending = syncDao.getPendingUploadsOnce()
        if (pending.isEmpty()) return Result.success(Unit)

        // Process only first 100 to avoid large payload/memory issues
        val batch = pending.take(100)

        val events = batch.mapNotNull { entry ->
            val entityData = fetchEntityData(entry.entityType, entry.entitySyncId)
            if (entityData == null && entry.operation != "DELETE") return@mapNotNull null

            val timestamp = when (entityData) {
                is Order -> entityData.updatedAt
                is CustomerEntity -> entityData.updatedAt
                is SettlementHistory -> entityData.updatedAt
                is Expense -> entityData.updatedAt
                is StockItem -> entityData.updatedAt
                is Note -> entityData.updatedAt
                is BeautyTransaction -> entityData.updatedAt
                is ExternalLedger -> entityData.updatedAt
                is PrinterReference -> entityData.updatedAt
                is OrderItem -> entityData.updatedAt
                else -> entry.createdAt
            }

            SyncEventRequest(
                entityType = entry.entityType,
                entitySyncId = entry.entitySyncId,
                operation = entry.operation,
                data = if (entry.operation == "DELETE") emptyMap() else entityDataToMap(entityData),
                timestamp = timestamp,
                idempotencyKey = entry.idempotencyKey
            )
        }

        if (events.isEmpty()) {
             // Clean up if all were missing/invalid
             batch.forEach { syncDao.deleteOutbox(it) }
             return Result.success(Unit)
        }

        return try {
            val response = api.pushSync(token, PushRequest(events))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!.results
                batch.forEach { outbox ->
                    val result = results.find { it.syncId == outbox.entitySyncId }
                    if (result?.status == "SUCCESS" || result?.status == "SERVER_WINS") {
                        syncDao.deleteOutbox(outbox)
                    } else if (result?.status == "ERROR") {
                        syncDao.updateOutbox(outbox.copy(status = SyncStatus.FAILED, lastError = result.message))
                    }
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Push failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchEntityData(type: String, syncId: String): Any? {
        return when (type) {
            "CUSTOMER" -> syncDao.getCustomerBySyncId(syncId)
            "ORDER" -> syncDao.getOrderBySyncId(syncId)
            "ORDER_ITEM" -> syncDao.getOrderItemBySyncId(syncId)
            "SETTLEMENT" -> syncDao.getSettlementBySyncId(syncId)
            "EXPENSE" -> syncDao.getExpenseBySyncId(syncId)
            "STOCK" -> syncDao.getStockItemBySyncId(syncId)
            "NOTE" -> syncDao.getNoteBySyncId(syncId)
            "BEAUTY_TRANSACTION" -> syncDao.getBeautyTransactionBySyncId(syncId)
            "EXTERNAL_LEDGER" -> syncDao.getExternalLedgerBySyncId(syncId)
            "PRINTER_REFERENCE" -> syncDao.getPrinterReferenceBySyncId(syncId)
            else -> null
        }
    }

    private fun entityDataToMap(entity: Any?): Map<String, Any?> {
        if (entity == null) return emptyMap()
        val json = gson.toJson(entity)
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson(json, type)
    }

    suspend fun pullChanges(): Result<Unit> {
        val token = authRepository.getAuthHeader() ?: return Result.failure(Exception("Not authenticated"))
        val lastCursorTs = getSyncCursorTs()
        val lastCursorId = getSyncCursorId()
        
        return try {
            val response = api.pullSync(token, PullRequest(lastSyncTimestamp = lastCursorTs, lastSyncId = lastCursorId))
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                
                database.runInTransaction {
                    val affectedCustomerIds = mutableSetOf<Long>()
                    for (event in data.events) {
                        try {
                            val customerId = applyRemoteEventSync(event)
                            if (customerId != null && customerId != 0L) {
                                affectedCustomerIds.add(customerId)
                            }
                        } catch (e: MissingDependencyException) {
                            // P0 Dependency Hardening: Defer instead of stalling
                            runBlocking {
                                deferredSyncDao.insertDeferred(DeferredSync(
                                    entityType = event.entityType,
                                    entitySyncId = event.entitySyncId,
                                    operation = event.operation,
                                    data = gson.toJson(event.data),
                                    timestamp = event.timestamp,
                                    serverUpdatedAt = event.serverUpdatedAt,
                                    serverId = event.serverId,
                                    idempotencyKey = event.idempotencyKey
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e("Sync", "Fatal error applying event: ${event.entitySyncId}", e)
                            throw e 
                        }
                    }
                    
                    affectedCustomerIds.forEach { id ->
                        runBlocking { printDao.rebuildCustomerProjection(id) }
                    }
                }
                
                if (data.nextCursor != null) {
                    saveSyncCursor(data.nextCursor, data.nextCursorId)
                }

                // Process deferred items after batch success
                processDeferredItems()

                Result.success(Unit)
            } else {
                Result.failure(Exception("Pull failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun processDeferredItems() {
        val deferred = deferredSyncDao.getAllDeferred()
        if (deferred.isEmpty()) return

        var progress = true
        while (progress) {
            progress = false
            val remaining = deferredSyncDao.getAllDeferred()
            for (item in remaining) {
                try {
                    val event = SyncEventRequest(
                        entityType = item.entityType,
                        entitySyncId = item.entitySyncId,
                        operation = item.operation,
                        data = gson.fromJson(item.data, object : TypeToken<Map<String, Any?>>() {}.type),
                        timestamp = item.timestamp,
                        idempotencyKey = item.idempotencyKey,
                        serverUpdatedAt = item.serverUpdatedAt,
                        serverId = item.serverId
                    )
                    
                    database.runInTransaction {
                        val cid = applyRemoteEventSync(event)
                        if (cid != null) runBlocking { printDao.rebuildCustomerProjection(cid) }
                    }
                    
                    deferredSyncDao.deleteDeferred(item)
                    progress = true
                } catch (e: MissingDependencyException) {
                    // Still missing, keep deferred
                } catch (e: Exception) {
                    Log.e("Sync", "Error processing deferred item: ${item.entitySyncId}", e)
                    // Potentially remove or mark as failed to avoid infinite loop
                    deferredSyncDao.deleteDeferred(item)
                }
            }
        }
    }

    internal fun applyRemoteEventSync(event: SyncEventRequest): Long? = runBlocking {
        val type = event.entityType
        val syncId = event.entitySyncId
        
        return@runBlocking when (type) {
            "CUSTOMER" -> {
                val existing = syncDao.getCustomerBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markCustomerDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val customer = mapToEntity<CustomerEntity>(event.data, syncId)
                        val toSave = if (existing != null) customer.copy(id = existing.id, syncStatus = SyncStatus.SYNCED) else customer.copy(syncStatus = SyncStatus.SYNCED)
                        syncDao.upsertCustomer(toSave)
                    }
                }
                null as Long?
            }
            "NOTE" -> {
                val existing = syncDao.getNoteBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markNoteDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val note = mapToEntity<Note>(event.data, syncId)
                        val toSave = if (existing != null) note.copy(id = existing.id, syncStatus = SyncStatus.SYNCED) else note.copy(syncStatus = SyncStatus.SYNCED)
                        syncDao.upsertNote(toSave)
                    }
                }
                null as Long?
            }
            "ORDER" -> {
                val existing = syncDao.getOrderBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markOrderDeleted(syncId, event.timestamp)
                    }
                    null as Long?
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val order = mapToEntity<Order>(event.data, syncId)
                        val customer = syncDao.getCustomerBySyncId(order.customerSyncId) 
                            ?: throw MissingDependencyException("CUSTOMER", order.customerSyncId)
                        val toSave = order.copy(
                            id = existing?.id ?: 0,
                            customerId = customer.id,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertOrder(toSave)
                        toSave.customerId
                    } else null as Long?
                }
            }
            "ORDER_ITEM" -> {
                val existing = syncDao.getOrderItemBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markOrderItemDeleted(syncId, event.timestamp)
                    }
                    null as Long?
                } else {
                    val item = mapToEntity<OrderItem>(event.data, syncId)
                    val order = syncDao.getOrderBySyncId(item.orderSyncId)
                        ?: throw MissingDependencyException("ORDER", item.orderSyncId)
                    if (existing == null || (event.timestamp >= (existing.updatedAt) && existing.deletedAt == null)) {
                        val toSave = item.copy(
                            id = existing?.id ?: 0,
                            orderId = order.id
                        )
                        syncDao.upsertOrderItem(toSave)
                    }
                    order.customerId
                }
            }
            "SETTLEMENT" -> {
                val existing = syncDao.getSettlementBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markSettlementDeleted(syncId, event.timestamp)
                    }
                    null as Long?
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val settlement = mapToEntity<SettlementHistory>(event.data, syncId)
                        val customer = syncDao.getCustomerBySyncId(settlement.customerSyncId)
                            ?: throw MissingDependencyException("CUSTOMER", settlement.customerSyncId)
                        val order = if (settlement.originSyncId != null) {
                            syncDao.getOrderBySyncId(settlement.originSyncId) ?: throw MissingDependencyException("ORDER", settlement.originSyncId)
                        } else null
                        
                        val toSave = settlement.copy(
                            id = existing?.id ?: 0,
                            customerId = customer.id,
                            originId = order?.id,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertSettlement(toSave)
                        toSave.customerId
                    } else null as Long?
                }
            }
            "EXPENSE" -> {
                val existing = syncDao.getExpenseBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markExpenseDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val expense = mapToEntity<Expense>(event.data, syncId)
                        val toSave = expense.copy(
                            id = existing?.id ?: 0,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertExpense(toSave)
                    }
                }
                null as Long?
            }
            "STOCK" -> {
                val existing = syncDao.getStockItemBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markStockItemDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val item = mapToEntity<StockItem>(event.data, syncId)
                        val toSave = item.copy(
                            id = existing?.id ?: 0,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertStockItem(toSave)
                    }
                }
                null as Long?
            }
            "BEAUTY_TRANSACTION" -> {
                val existing = syncDao.getBeautyTransactionBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markBeautyTransactionDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val transaction = mapToEntity<BeautyTransaction>(event.data, syncId)
                        val toSave = transaction.copy(
                            id = existing?.id ?: 0,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertBeautyTransaction(toSave)
                    }
                }
                null as Long?
            }
            "EXTERNAL_LEDGER" -> {
                val existing = syncDao.getExternalLedgerBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markExternalLedgerDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val ledger = mapToEntity<ExternalLedger>(event.data, syncId)
                        val customer = if (ledger.customerSyncId != null) {
                            syncDao.getCustomerBySyncId(ledger.customerSyncId) ?: throw MissingDependencyException("CUSTOMER", ledger.customerSyncId)
                        } else null
                        val order = if (ledger.orderSyncId != null) {
                            syncDao.getOrderBySyncId(ledger.orderSyncId) ?: throw MissingDependencyException("ORDER", ledger.orderSyncId)
                        } else null
                        
                        val toSave = ledger.copy(
                            id = existing?.id ?: 0,
                            customerId = customer?.id,
                            orderId = order?.id,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertExternalLedger(toSave)
                        toSave.customerId
                    } else null as Long?
                }
                null as Long?
            }
            "PRINTER_REFERENCE" -> {
                val existing = syncDao.getPrinterReferenceBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markPrinterReferenceDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val ref = mapToEntity<PrinterReference>(event.data, syncId)
                        val toSave = ref.copy(
                            id = existing?.id ?: 0,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertPrinterReference(toSave)
                    }
                }
                null as Long?
            }
            else -> null as Long?
        }
    }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return coroutinesRunBlocking { block() }
    }

    private inline fun <reified T> mapToEntity(data: Map<String, Any?>, syncId: String): T {
        val mutableData = data.toMutableMap()
        if (!mutableData.containsKey("syncId")) mutableData["syncId"] = syncId
        val json = gson.toJson(mutableData)
        return gson.fromJson(json, typeOfT<T>())
    }

    private inline fun <reified T> typeOfT(): Type = object : TypeToken<T>() {}.type

    private fun getSyncCursorTs(): String? {
        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        return prefs.getString("last_cursor_ts", null)
    }

    private fun getSyncCursorId(): Int? {
        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val id = prefs.getInt("last_cursor_id", -1)
        return if (id == -1) null else id
    }

    private fun saveSyncCursor(ts: String, id: Int?) {
        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit().putString("last_cursor_ts", ts)
        if (id != null) editor.putInt("last_cursor_id", id)
        editor.apply()
    }

    class BigDecimalAdapter : JsonSerializer<BigDecimal>, JsonDeserializer<BigDecimal> {
        override fun serialize(src: BigDecimal?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
            return JsonPrimitive(src?.toPlainString() ?: "0")
        }

        override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): BigDecimal {
            return try {
                BigDecimal(json?.asString ?: "0")
            } catch (e: Exception) {
                BigDecimal.ZERO
            }
        }
    }

    class LongAdapter : JsonDeserializer<Long> {
        override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Long {
            return if (json != null && json.isJsonPrimitive) {
                val primitive = json.asJsonPrimitive
                if (primitive.isNumber) {
                    primitive.asDouble.toLong()
                } else {
                    primitive.asLong
                }
            } else 0L
        }
    }
}
