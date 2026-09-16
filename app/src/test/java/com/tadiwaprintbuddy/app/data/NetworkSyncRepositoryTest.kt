package com.tadiwaprintbuddy.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.*
import com.tadiwaprintbuddy.app.TestApplication
import com.tadiwaprintbuddy.app.api.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class NetworkSyncRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var syncDao: SyncDao
    private lateinit var repository: NetworkSyncRepository
    private lateinit var mockWebServer: MockWebServer
    
    private val testGson = GsonBuilder()
        .registerTypeAdapter(BigDecimal::class.java, NetworkSyncRepository.BigDecimalAdapter())
        .create()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncDao = db.syncDao()
        mockWebServer = MockWebServer()
        mockWebServer.start()
        
        // Mock Auth Header
        val testPrefs = context.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        val authRepo = AuthRepository.getTestInstance(context, testPrefs)
        authRepo.saveAuth(AuthResponse("test-token", "bearer"))

        repository = NetworkSyncRepository(context, authRepo, db)
        
        // Inject mock server URL via reflection
        val field = repository.javaClass.getDeclaredField("api")
        field.isAccessible = true
        
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .addConverterFactory(GsonConverterFactory.create(testGson))
            .build()
        field.set(repository, retrofit.create(TadiwaApi::class.java))
    }

    @After
    fun teardown() {
        db.close()
        mockWebServer.shutdown()
    }

    @Test
    fun `test pull new customer from server`() = runTest {
        val syncId = UUID.randomUUID().toString()
        val customerData = mapOf(
            "displayName" to "Server Customer",
            "normalizedName" to "server customer",
            "phoneNumber" to "999",
            "createdAt" to 1000L,
            "updatedAt" to 2000L,
            "syncId" to syncId,
            "syncStatus" to "SYNCED"
        )
        
        val json = """
            {
                "events": [
                    {
                        "entity_type": "CUSTOMER",
                        "entity_sync_id": "$syncId",
                        "operation": "CREATE",
                        "data": ${testGson.toJson(customerData)},
                        "timestamp": 2000,
                        "idempotency_key": "test-idemp"
                    }
                ],
                "next_cursor": "cursor-1"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse().setBody(json).setResponseCode(200))
        
        val result = repository.pullChanges()
        assertTrue(result.isSuccess)
        
        val local = syncDao.getCustomerBySyncId(syncId)
        assertNotNull(local)
        assertEquals("Server Customer", local?.displayName)
        assertEquals(SyncStatus.SYNCED, local?.syncStatus)
    }

    @Test
    fun `test pull deleted note from server`() = runTest {
        val syncId = UUID.randomUUID().toString()
        // Create local note first
        val localNote = Note(
            title = "Old Note",
            content = "Content",
            createdAt = 1000,
            updatedAt = 1000,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED
        )
        syncDao.upsertNote(localNote)
        
        val json = """
            {
                "events": [
                    {
                        "entity_type": "NOTE",
                        "entity_sync_id": "$syncId",
                        "operation": "DELETE",
                        "data": {},
                        "timestamp": 3000,
                        "idempotency_key": "test-idemp-del"
                    }
                ],
                "next_cursor": "cursor-2"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse().setBody(json).setResponseCode(200))
        
        repository.pullChanges()
        
        val updatedLocal = syncDao.getNoteBySyncId(syncId)
        assertNotNull(updatedLocal?.deletedAt)
        assertEquals(3000L, updatedLocal?.deletedAt)
    }

    @Test
    fun `test pull order links to local customer`() = runTest {
        // 1. Create local customer
        val custSyncId = UUID.randomUUID().toString()
        val customer = CustomerEntity(displayName = "Local Cust", normalizedName = "local cust", syncId = custSyncId)
        syncDao.upsertCustomer(customer)
        val localCustId = syncDao.getCustomerBySyncId(custSyncId)!!.id
        
        // 2. Pull order from server referencing that customer
        val orderSyncId = UUID.randomUUID().toString()
        val orderData = mapOf(
            "totalAmount" to "100.0",
            "date" to 1000L,
            "customerName" to "Local Cust",
            "paymentMethod" to "CASH",
            "paymentStatus" to "PAID",
            "orderStatus" to "ACTIVE",
            "paidAmount" to "0.0",
            "previousBalance" to "0.0",
            "transactionAmount" to "0.0",
            "newBalance" to "0.0",
            "customerSyncId" to custSyncId,
            "syncId" to orderSyncId,
            "updatedAt" to 1000L
        )
        
        val json = """
            {
                "events": [
                    {
                        "entity_type": "ORDER",
                        "entity_sync_id": "$orderSyncId",
                        "operation": "CREATE",
                        "data": ${testGson.toJson(orderData)},
                        "timestamp": 1000,
                        "idempotency_key": "order-idemp"
                    }
                ],
                "next_cursor": "cursor-3"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse().setBody(json).setResponseCode(200))
        
        repository.pullChanges()
        
        val localOrder = syncDao.getOrderBySyncId(orderSyncId)
        assertNotNull(localOrder)
        assertEquals(localCustId, localOrder?.customerId)
        assertEquals(custSyncId, localOrder?.customerSyncId)
    }

    @Test
    fun `test pull order item links to local order`() = runTest {
        // 1. Create local order
        val orderSyncId = UUID.randomUUID().toString()
        val order = Order(totalAmount = BigDecimal("100.0"), date = 1000L, customerName = "Test", syncId = orderSyncId)
        syncDao.upsertOrder(order)
        val localOrderId = syncDao.getOrderBySyncId(orderSyncId)!!.id
        
        // 2. Pull item from server
        val itemSyncId = UUID.randomUUID().toString()
        val itemData = mapOf(
            "orderId" to 0, // Placeholder
            "serviceName" to "Service X",
            "price" to "10.0",
            "quantity" to 5,
            "orderSyncId" to orderSyncId,
            "syncId" to itemSyncId
        )
        
        val json = """
            {
                "events": [
                    {
                        "entity_type": "ORDER_ITEM",
                        "entity_sync_id": "$itemSyncId",
                        "operation": "CREATE",
                        "data": ${testGson.toJson(itemData)},
                        "timestamp": 1000,
                        "idempotency_key": "item-idemp"
                    }
                ],
                "next_cursor": "cursor-4"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse().setBody(json).setResponseCode(200))
        
        repository.pullChanges()
        
        val localItem = syncDao.getOrderItemBySyncId(itemSyncId)
        assertNotNull(localItem)
        assertEquals(localOrderId, localItem?.orderId)
    }

    @Test
    fun `test pull settlement links to local customer and order`() = runTest {
        val custSyncId = UUID.randomUUID().toString()
        val orderSyncId = UUID.randomUUID().toString()
        val settlementSyncId = UUID.randomUUID().toString()
        
        // 1. Setup local references
        syncDao.upsertCustomer(CustomerEntity(displayName = "C1", normalizedName = "c1", syncId = custSyncId))
        syncDao.upsertOrder(Order(totalAmount = BigDecimal("50.0"), date = 1000L, customerName = "C1", syncId = orderSyncId))
        
        val localCustId = syncDao.getCustomerBySyncId(custSyncId)!!.id
        val localOrderId = syncDao.getOrderBySyncId(orderSyncId)!!.id
        
        // 2. Pull settlement
        val setDate = mapOf(
            "customerName" to "C1",
            "balanceBefore" to "0.0",
            "amountPaid" to "50.0",
            "balanceAfter" to "0.0",
            "timestamp" to 1000L,
            "type" to "PAYMENT",
            "ledgerEntryType" to "PAYMENT",
            "note" to "Paid",
            "customerId" to 0,
            "transactionAmount" to "-50.0",
            "newBalance" to "0.0",
            "reconciliationStatus" to "VERIFIED",
            "syncId" to settlementSyncId,
            "customerSyncId" to custSyncId,
            "originSyncId" to orderSyncId,
            "updatedAt" to 1000L,
            "syncStatus" to "SYNCED"
        )
        
        val json = """
            {
                "events": [
                    {
                        "entity_type": "SETTLEMENT",
                        "entity_sync_id": "$settlementSyncId",
                        "operation": "CREATE",
                        "data": ${testGson.toJson(setDate)},
                        "timestamp": 1000,
                        "idempotency_key": "set-idemp"
                    }
                ],
                "next_cursor": "cursor-5"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse().setBody(json).setResponseCode(200))
        
        repository.pullChanges()
        
        val localSet = syncDao.getSettlementBySyncId(settlementSyncId)
        assertNotNull(localSet)
        assertEquals(localCustId, localSet?.customerId)
        assertEquals(localOrderId, localSet?.originId)
    }
}
