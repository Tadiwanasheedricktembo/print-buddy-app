package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.tadiwaprintbuddy.app.api.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Type
import java.math.BigDecimal

class MissingDependencyException(val type: String, val syncId: String) : Exception("Missing dependency: $type/$syncId")
open class AuthenticationRequiredException(message: String) : Exception(message)
class SyncAuthenticationException(message: String) : AuthenticationRequiredException(message)

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

    private fun isUpiAccountHistoryOrder(order: Order): Boolean =
        order.customerId == 0L &&
            order.customerName == "UPI Account" &&
            order.customerSyncId.isBlank() &&
            order.paymentMethod == "UPI" &&
            order.paymentStatus == "PAID" &&
            order.paidAmount.compareTo(order.totalAmount) == 0

    private val api = Retrofit.Builder()
        .baseUrl(NetworkConfig.BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(TadiwaApi::class.java)

    companion object {
        private val SUPPORTED_SYNC_ENTITY_TYPES = setOf(
            "CUSTOMER",
            "ORDER",
            "ORDER_ITEM",
            "SETTLEMENT",
            "EXPENSE",
            "STOCK",
            "NOTE",
            "BEAUTY_TRANSACTION",
            "UPI_ACCOUNT_TRANSACTION",
            "EXTERNAL_LEDGER",
            "PRINTER_REFERENCE"
        )

        fun getInstance(context: Context): NetworkSyncRepository {
            return NetworkSyncRepository(
                context.applicationContext,
                AuthRepository.getInstance(context),
                AppDatabase.getDatabase(context)
            )
        }
    }

    suspend fun pushPendingChanges(): Result<Unit> {
        val binding = authRepository.ensureAccountBinding()
        if (binding.isFailure) return Result.failure(binding.exceptionOrNull()!!)
        val token = authRepository.getAuthHeader() ?: return Result.success(Unit)
        
        // P0 Memory Hardening: Process pending uploads in batches
        val pending = syncDao.getPendingUploadsOnce()
        if (pending.isEmpty()) return Result.success(Unit)

        // Process only first 100 to avoid large payload/memory issues
        val batch = pending.take(100)

        val events = ArrayList<SyncEventRequest>(batch.size)
        for (entry in batch) {
            if (entry.entityType !in SUPPORTED_SYNC_ENTITY_TYPES) {
                val message = "Unsupported sync entity type: ${entry.entityType}"
                syncDao.updateOutbox(entry.copy(status = SyncStatus.FAILED, lastError = message))
                Log.e("Sync", message)
                return Result.failure(IllegalStateException(message))
            }
            val entityData = fetchEntityData(entry.entityType, entry.entitySyncId)
            if (entityData == null && entry.operation != "DELETE") {
                val message = "Local ${entry.entityType}/${entry.entitySyncId} is missing; outbox entry was retained as failed"
                syncDao.updateOutbox(entry.copy(status = SyncStatus.FAILED, lastError = message))
                Log.e("Sync", message)
                return Result.failure(IllegalStateException(message))
            }

            val timestamp = when (entityData) {
                is Order -> entityData.updatedAt
                is CustomerEntity -> entityData.updatedAt
                is SettlementHistory -> entityData.updatedAt
                is Expense -> entityData.updatedAt
                is StockItem -> entityData.updatedAt
                is Note -> entityData.updatedAt
                is BeautyTransaction -> entityData.updatedAt
                is UpiAccountTransaction -> entityData.updatedAt
                is ExternalLedger -> entityData.updatedAt
                is PrinterReference -> entityData.updatedAt
                is OrderItem -> entityData.updatedAt
                else -> entry.createdAt
            }

            events += SyncEventRequest(
                entityType = entry.entityType,
                entitySyncId = entry.entitySyncId,
                operation = entry.operation,
                data = if (entry.operation == "DELETE") emptyMap() else entityDataToMap(entityData),
                timestamp = timestamp,
                idempotencyKey = entry.idempotencyKey
            )
        }

        if (events.isEmpty()) return Result.success(Unit)

        return try {
            val response = api.pushSync(token, PushRequest(events))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!.results
                var missingAcknowledgement = false
                var rejectedEvent = false
                batch.forEach { outbox ->
                    val matchingResults = results.filter { it.idempotencyKey == outbox.idempotencyKey }
                    if (matchingResults.size != 1) {
                        val isExplicitlyRejectedDuplicate = matchingResults.isNotEmpty() &&
                            matchingResults.all { it.status == "ERROR" } &&
                            matchingResults.any { it.syncId == outbox.entitySyncId }
                        if (isExplicitlyRejectedDuplicate) {
                            syncDao.updateOutbox(outbox.copy(
                                status = SyncStatus.FAILED,
                                lastError = matchingResults.first { it.syncId == outbox.entitySyncId }.message
                                    ?: "Server rejected duplicate idempotency key"
                            ))
                            rejectedEvent = true
                        } else {
                            missingAcknowledgement = true
                        }
                    } else if (matchingResults.single().syncId != outbox.entitySyncId) {
                        missingAcknowledgement = true
                    } else {
                        val result = matchingResults.single()
                        when (result.status) {
                            "SUCCESS", "SERVER_WINS" -> syncDao.deleteOutbox(outbox)
                            "ERROR" -> {
                                syncDao.updateOutbox(outbox.copy(
                                    status = SyncStatus.FAILED,
                                    lastError = result.message ?: "Server rejected sync event"
                                ))
                                rejectedEvent = true
                            }
                            else -> missingAcknowledgement = true
                        }
                    }
                }
                when {
                    missingAcknowledgement -> Result.failure(
                        IllegalStateException("Push response did not acknowledge every outbox event; unacknowledged entries remain pending")
                    )
                    rejectedEvent -> Result.failure(IllegalStateException("Server rejected one or more sync events"))
                    else -> Result.success(Unit)
                }
            } else if (response.code() == 401) {
                authRepository.logout()
                Result.failure(SyncAuthenticationException("Cloud session expired; sign in again"))
            } else if (response.code() in 400..499 && response.code() !in setOf(408, 429)) {
                val message = "Push rejected: HTTP ${response.code()}"
                batch.forEach { syncDao.updateOutbox(it.copy(status = SyncStatus.FAILED, lastError = message)) }
                Result.failure(IllegalStateException(message))
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
            "UPI_ACCOUNT_TRANSACTION" -> syncDao.getUpiAccountTransactionBySyncId(syncId)
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
        val binding = authRepository.ensureAccountBinding()
        if (binding.isFailure) return Result.failure(binding.exceptionOrNull()!!)
        val token = authRepository.getAuthHeader() ?: return Result.success(Unit)
        val lastCursorTs = getSyncCursorTs()
        val lastCursorId = getSyncCursorId()
        
        return try {
            val response = api.pullSync(token, PullRequest(lastSyncTimestamp = lastCursorTs, lastSyncId = lastCursorId))
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                
                database.withTransaction {
                    val affectedCustomerIds = mutableSetOf<Long>()
                    for (event in data.events) {
                        try {
                            val customerId = applyRemoteEventSync(event)
                            if (customerId != null && customerId != 0L) {
                                affectedCustomerIds.add(customerId)
                            }
                        } catch (e: MissingDependencyException) {
                            // P0 Dependency Hardening: Defer instead of stalling
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
                        } catch (e: Exception) {
                            Log.e("Sync", "Fatal error applying event: ${event.entitySyncId}", e)
                            throw e 
                        }
                    }
                    
                    affectedCustomerIds.forEach { id ->
                        printDao.rebuildCustomerProjection(id)
                    }
                }
                
                if (data.nextCursor != null) {
                    saveSyncCursor(data.nextCursor, data.nextCursorId)
                }

                // Process deferred items after batch success
                processDeferredItems()

                Result.success(Unit)
            } else {
                if (response.code() == 401) {
                    authRepository.logout()
                    Result.failure(SyncAuthenticationException("Cloud session expired; sign in again"))
                } else {
                    Result.failure(Exception("Pull failed: ${response.code()}"))
                }
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
                    
                    database.withTransaction {
                        val cid = applyRemoteEventSync(event)
                        if (cid != null) printDao.rebuildCustomerProjection(cid)
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

    internal suspend fun applyRemoteEventSync(event: SyncEventRequest): Long? {
        val type = event.entityType
        val syncId = event.entitySyncId
        
        return when (type) {
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
                        val customer = if (isUpiAccountHistoryOrder(order)) null else {
                            syncDao.getCustomerBySyncId(order.customerSyncId)
                                ?: throw MissingDependencyException("CUSTOMER", order.customerSyncId)
                        }
                        val toSave = order.copy(
                            id = existing?.id ?: 0,
                            customerId = customer?.id ?: 0,
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
                        val isWalletSettlement = settlement.customerId == 0L &&
                            settlement.customerName == "UPI Account" &&
                            settlement.customerSyncId.isBlank() &&
                            settlement.ledgerEntryType in setOf("UPI_ACCOUNT_TOPUP", "UPI_ACCOUNT_RETURN")
                        val customer = if (isWalletSettlement) null else {
                            syncDao.getCustomerBySyncId(settlement.customerSyncId)
                                ?: throw MissingDependencyException("CUSTOMER", settlement.customerSyncId)
                        }
                        val order = if (settlement.originSyncId != null) {
                            syncDao.getOrderBySyncId(settlement.originSyncId)
                                ?: if (isWalletSettlement && (settlement.isShadowDuplicate || settlement.ledgerEntryType == "UPI_ACCOUNT_RETURN")) null
                                else throw MissingDependencyException("ORDER", settlement.originSyncId)
                        } else null
                        
                        val toSave = settlement.copy(
                            id = existing?.id ?: 0,
                            customerId = customer?.id ?: 0,
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
            "UPI_ACCOUNT_TRANSACTION" -> {
                val existing = syncDao.getUpiAccountTransactionBySyncId(syncId)
                if (event.operation == "DELETE") {
                    if (existing == null || event.timestamp >= existing.updatedAt) {
                        syncDao.markUpiAccountTransactionDeleted(syncId, event.timestamp)
                    }
                } else {
                    if (existing == null || (event.timestamp >= existing.updatedAt && existing.deletedAt == null)) {
                        val transaction = mapToEntity<UpiAccountTransaction>(event.data, syncId)
                        val toSave = transaction.copy(
                            id = existing?.id ?: 0,
                            syncStatus = SyncStatus.SYNCED
                        )
                        syncDao.upsertUpiAccountTransaction(toSave)
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
