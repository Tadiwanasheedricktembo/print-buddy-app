package com.tadiwaprintbuddy.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.TestApplication
import com.tadiwaprintbuddy.app.api.SyncEventRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class HardenedSyncTest {

    private lateinit var database: AppDatabase
    private lateinit var syncDao: SyncDao
    private lateinit var printDao: PrintDao
    private lateinit var deferredSyncDao: DeferredSyncDao
    private lateinit var repository: NetworkSyncRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        syncDao = database.syncDao()
        printDao = database.printDao()
        deferredSyncDao = database.deferredSyncDao()
        
        val testPrefs = context.getSharedPreferences("test_auth_prefs", Context.MODE_PRIVATE)
        val authRepository = AuthRepository.getTestInstance(context, testPrefs)
        
        repository = NetworkSyncRepository(context, authRepository, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testPull_SplitBatch_DeferredResolution() = runTest {
        val orderSyncId = "order-split"
        val itemSyncId = "item-split"
        
        // 1. Child arrives in Batch 1
        val itemEvent = SyncEventRequest(
            entityType = "ORDER_ITEM",
            entitySyncId = itemSyncId,
            operation = "CREATE",
            data = mapOf(
                "orderSyncId" to orderSyncId,
                "serviceName" to "Split Service",
                "price" to "5.00",
                "quantity" to 1.0,
                "updatedAt" to 1000.0
            ),
            timestamp = 1000,
            idempotencyKey = "batch1-k1"
        )
        
        // Apply Batch 1 - should defer
        try {
            repository.applyRemoteEventSync(itemEvent)
        } catch (e: MissingDependencyException) {
            deferredSyncDao.insertDeferred(DeferredSync(
                entityType = itemEvent.entityType,
                entitySyncId = itemEvent.entitySyncId,
                operation = itemEvent.operation,
                data = "{\"orderSyncId\":\"$orderSyncId\",\"serviceName\":\"Split Service\",\"price\":\"5.00\",\"quantity\":1.0,\"updatedAt\":1000.0}",
                timestamp = itemEvent.timestamp,
                serverUpdatedAt = null,
                serverId = null,
                idempotencyKey = itemEvent.idempotencyKey
            ))
        }
        
        assertNull(syncDao.getOrderItemBySyncId(itemSyncId))
        assertEquals(1, deferredSyncDao.getAllDeferred().size)
        
        // 2. Parent and Customer arrive in Batch 2
        val custEvent = SyncEventRequest(
            entityType = "CUSTOMER",
            entitySyncId = "cust-split",
            operation = "CREATE",
            data = mapOf("displayName" to "Split Alice", "normalizedName" to "split alice", "updatedAt" to 1000.0),
            timestamp = 1000,
            idempotencyKey = "batch2-k1"
        )
        
        val orderEvent = SyncEventRequest(
            entityType = "ORDER",
            entitySyncId = orderSyncId,
            operation = "CREATE",
            data = mapOf(
                "totalAmount" to "5.00",
                "paidAmount" to "5.00",
                "customerName" to "Split Alice",
                "customerSyncId" to "cust-split",
                "date" to 1000.0,
                "updatedAt" to 1000.0,
                "paymentMethod" to "CASH",
                "paymentStatus" to "PAID",
                "orderStatus" to "ACTIVE",
                "previousBalance" to "0.00",
                "transactionAmount" to "5.00",
                "newBalance" to "5.00"
            ),
            timestamp = 1000,
            idempotencyKey = "batch2-k2"
        )
        
        repository.applyRemoteEventSync(custEvent)
        repository.applyRemoteEventSync(orderEvent)
        
        // 3. Process deferred items
        repository.processDeferredItems()
        
        // Verify resolution
        val savedItem = syncDao.getOrderItemBySyncId(itemSyncId)
        assertNotNull(savedItem)
        assertEquals(0, deferredSyncDao.getAllDeferred().size)
    }

    @Test
    fun testTombstoneVisibility() = runTest {
        val syncId = "visible-cust"
        val cust = CustomerEntity(
            id = 0, displayName = "Visible", normalizedName = "visible", 
            createdAt = 1000, updatedAt = 1000, syncId = syncId
        )
        printDao.insertCustomer(cust)
        
        assertEquals(1, printDao.getAllCustomers().size)
        
        // Soft delete
        printDao.markCustomerDeletedInternal(cust.id, 2000, 2000) // Needs id, using 1 since it's first
        // Wait, insertCustomer returns id. Let's do it properly
        val id = printDao.insertCustomer(cust.copy(syncId = "new-id", normalizedName = "new-norm"))
        printDao.markCustomerDeletedInternal(id, 2000, 2000)
        
        val all = printDao.getAllCustomers()
        // The original "Visible" + the new one. Both might be there if not careful.
        // Actually, printDao.insertCustomer with syncId already in DB might replace.
        
        val currentAll = printDao.getAllCustomers()
        for (c in currentAll) {
            assertNull(c.deletedAt, "Should only return non-deleted customers")
        }
    }

    @Test
    fun testResurrection_StaleUpdateAfterDelete() = runTest {
        val syncId = "res-cust"
        val cust = CustomerEntity(
            id = 0, displayName = "Bob", normalizedName = "bob", 
            createdAt = 1000, updatedAt = 1000, syncId = syncId
        )
        syncDao.upsertCustomer(cust)
        
        // Delete locally at T=2000
        syncDao.markCustomerDeleted(syncId, 2000)
        
        // Stale update from cloud at T=1500
        val staleUpdate = SyncEventRequest(
            entityType = "CUSTOMER",
            entitySyncId = syncId,
            operation = "UPDATE",
            data = mapOf("displayName" to "Bob Old", "normalizedName" to "bob", "updatedAt" to 1500.0),
            timestamp = 1500,
            idempotencyKey = "k-stale"
        )
        repository.applyRemoteEventSync(staleUpdate)
        
        val final = syncDao.getCustomerBySyncId(syncId)
        assertNotNull(final?.deletedAt, "Record should remain deleted")
        assertEquals("Bob", final?.displayName, "Record should not have been updated by stale event")
    }
}
