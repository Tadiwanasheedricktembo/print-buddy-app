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
    private lateinit var authRepository: AuthRepository
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
        testPrefs.edit().clear().commit()
        authRepository = AuthRepository.getTestInstance(context, testPrefs)
        authRepository.saveAuth(AuthResponse("test-token", "bearer"), "sync-test-user")

        repository = NetworkSyncRepository(context, authRepository, db)
        installMockApi(repository)
    }

    private fun installMockApi(target: NetworkSyncRepository) {
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .addConverterFactory(GsonConverterFactory.create(testGson))
            .build()
        val field = target.javaClass.getDeclaredField("api").apply { isAccessible = true }
        field.set(target, retrofit.create(TadiwaApi::class.java))
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

    @Test
    fun `customer credit settlement sync preserves analytics after pull`() = runTest {
        val printRepository = PrintRepository(db.printDao())
        printRepository.addOrUpdateDebtorCredit("Synced Credit Customer", BigDecimal("325.00"), "Manual credit")
        val customer = db.printDao().getCustomerByNormalizedName("synced credit customer")!!
        val adjustment = db.printDao().getAllSettlements().single { it.ledgerEntryType == "ADJUSTMENT" }

        val pending = syncDao.getPendingUploadsOnce()
        assertTrue(pending.any { it.entityType == "SETTLEMENT" && it.entitySyncId == adjustment.syncId })
        mockWebServer.enqueue(MockResponse().setBody(
            testGson.toJson(mapOf("results" to pending.map {
                mapOf(
                    "sync_id" to it.entitySyncId,
                    "idempotency_key" to it.idempotencyKey,
                    "status" to "SUCCESS",
                    "message" to null
                )
            }))
        ).setResponseCode(200))

        assertTrue(repository.pushPendingChanges().isSuccess)
        val pushRequest = JsonParser.parseString(mockWebServer.takeRequest().body.readUtf8()).asJsonObject
        val pushedEvents = pushRequest.getAsJsonArray("events")
        val pushedAdjustment = pushedEvents.map { it.asJsonObject }.single {
            it.get("entity_type").asString == "SETTLEMENT" &&
                it.get("entity_sync_id").asString == adjustment.syncId
        }
        assertEquals("325.00", pushedAdjustment.getAsJsonObject("data").get("transactionAmount").asString)
        assertTrue(syncDao.getPendingUploadsOnce().isEmpty())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val restoredDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val pullRepository = NetworkSyncRepository(context, authRepository, restoredDb)
            installMockApi(pullRepository)

            val pulledEvents = JsonArray()
            pushedEvents.map { it.asJsonObject }.forEach { event ->
                pulledEvents.add(event.deepCopy())
            }
            mockWebServer.enqueue(MockResponse().setBody(testGson.toJson(mapOf(
                "events" to pulledEvents,
                "next_cursor" to "credit-sync-cursor",
                "next_cursor_id" to 1
            ))).setResponseCode(200))
            assertTrue(pullRepository.pullChanges().isSuccess)

            val restoredRepository = PrintRepository(restoredDb.printDao())
            assertEquals(0, BigDecimal("325.00").compareTo(restoredRepository.getTotalReceivables()))
            assertEquals(0, BigDecimal("325.00").compareTo(
                restoredRepository.getCreditExtendedBetween(0L, Long.MAX_VALUE)
            ))
            assertEquals(customer.syncId,
                restoredDb.printDao().getCustomerByNormalizedName("synced credit customer")?.syncId)
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun `push removes outbox only after matching idempotency acknowledgement`() = runTest {
        val note = Note(title = "Ack note", content = "Body", createdAt = 1000L, updatedAt = 1000L)
        syncDao.upsertNote(note)
        val outbox = SyncOutbox(
            entityType = "NOTE",
            entitySyncId = note.syncId,
            operation = "CREATE",
            idempotencyKey = "ack-key"
        )
        syncDao.insertOutbox(outbox)
        mockWebServer.enqueue(MockResponse().setBody(
            testGson.toJson(mapOf("results" to listOf(
                mapOf(
                    "sync_id" to note.syncId,
                    "idempotency_key" to "ack-key",
                    "status" to "SUCCESS",
                    "message" to null
                )
            )))
        ).setResponseCode(200))

        assertTrue(repository.pushPendingChanges().isSuccess)
        assertTrue(syncDao.getPendingUploadsOnce().isEmpty())
    }

    @Test
    fun `beauty transaction push preserves decimal strings and acknowledgement key`() = runTest {
        val transaction = BeautyTransaction(
            amount = BigDecimal("0.10"),
            type = "ADD",
            timestamp = 1000L,
            previousBalance = BigDecimal.ZERO,
            transactionAmount = BigDecimal("0.10"),
            newBalance = BigDecimal("0.10")
        )
        syncDao.upsertBeautyTransaction(transaction)
        syncDao.insertOutbox(SyncOutbox(
            entityType = "BEAUTY_TRANSACTION",
            entitySyncId = transaction.syncId,
            operation = "CREATE",
            idempotencyKey = "beauty-push-key"
        ))
        mockWebServer.enqueue(MockResponse().setBody(
            testGson.toJson(mapOf("results" to listOf(
                mapOf(
                    "sync_id" to transaction.syncId,
                    "idempotency_key" to "beauty-push-key",
                    "status" to "SUCCESS",
                    "message" to null
                )
            )))
        ).setResponseCode(200))

        assertTrue(repository.pushPendingChanges().isSuccess)
        val pushedEvent = JsonParser.parseString(
            mockWebServer.takeRequest().body.readUtf8()
        ).asJsonObject.getAsJsonArray("events").single().asJsonObject
        assertEquals("BEAUTY_TRANSACTION", pushedEvent.get("entity_type").asString)
        assertEquals("beauty-push-key", pushedEvent.get("idempotency_key").asString)
        assertEquals("0.10", pushedEvent.getAsJsonObject("data").get("amount").asString)
    }

    @Test
    fun `push keeps pending outbox when response omits its acknowledgement`() = runTest {
        val note = Note(title = "Unacked note", content = "Body", createdAt = 1000L, updatedAt = 1000L)
        syncDao.upsertNote(note)
        val outbox = SyncOutbox(
            entityType = "NOTE",
            entitySyncId = note.syncId,
            operation = "CREATE",
            idempotencyKey = "missing-ack-key"
        )
        syncDao.insertOutbox(outbox)
        mockWebServer.enqueue(MockResponse().setBody(
            testGson.toJson(mapOf("results" to emptyList<Any>()))
        ).setResponseCode(200))

        assertTrue(repository.pushPendingChanges().isFailure)
        assertEquals(listOf("missing-ack-key"), syncDao.getPendingUploadsOnce().map { it.idempotencyKey })
    }

    @Test
    fun `push marks duplicate-key server rejections as failed`() = runTest {
        val note = Note(title = "Duplicate key note", content = "Body", createdAt = 1000L, updatedAt = 1000L)
        syncDao.upsertNote(note)
        syncDao.insertOutbox(SyncOutbox(
            entityType = "NOTE",
            entitySyncId = note.syncId,
            operation = "CREATE",
            idempotencyKey = "duplicate-key"
        ))
        mockWebServer.enqueue(MockResponse().setBody(
            testGson.toJson(mapOf("results" to listOf(
                mapOf(
                    "sync_id" to note.syncId,
                    "idempotency_key" to "duplicate-key",
                    "status" to "ERROR",
                    "message" to "Idempotency key is duplicated within this batch"
                ),
                mapOf(
                    "sync_id" to UUID.randomUUID().toString(),
                    "idempotency_key" to "duplicate-key",
                    "status" to "ERROR",
                    "message" to "Idempotency key is duplicated within this batch"
                )
            )))
        ).setResponseCode(200))

        assertTrue(repository.pushPendingChanges().isFailure)
        assertTrue(syncDao.getPendingUploadsOnce().isEmpty())
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT status, lastError FROM sync_outbox WHERE idempotencyKey = 'duplicate-key'"
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(SyncStatus.FAILED.name, it.getString(0))
            assertTrue(it.getString(1).contains("duplicated within this batch"))
        }
    }

    @Test
    fun `push marks unsupported photo sync as failed instead of deleting the outbox`() = runTest {
        val outbox = SyncOutbox(
            entityType = "PHOTO",
            entitySyncId = UUID.randomUUID().toString(),
            operation = "CREATE",
            idempotencyKey = "photo-key"
        )
        syncDao.insertOutbox(outbox)

        assertTrue(repository.pushPendingChanges().isFailure)
        assertTrue(syncDao.getPendingUploadsOnce().isEmpty())
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT status, lastError FROM sync_outbox WHERE idempotencyKey = 'photo-key'"
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(SyncStatus.FAILED.name, it.getString(0))
            assertTrue(it.getString(1).contains("Unsupported sync entity type: PHOTO"))
        }
    }

    @Test
    fun `expired session clears token and reports authentication requirement`() = runTest {
        val note = Note(title = "Expired token note", content = "Body", createdAt = 1000L, updatedAt = 1000L)
        syncDao.upsertNote(note)
        syncDao.insertOutbox(SyncOutbox(
            entityType = "NOTE",
            entitySyncId = note.syncId,
            operation = "CREATE",
            idempotencyKey = "expired-token-key"
        ))
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = repository.pushPendingChanges()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncAuthenticationException)
        assertNull(authRepository.getToken())
        assertEquals(1, syncDao.getPendingUploadsOnce().size)
    }

    @Test
    fun `local account binding prevents switching cloud users after logout`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("account_binding_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val accountAuth = AuthRepository.getTestInstance(context, prefs)
        accountAuth.saveAuth(AuthResponse("alice-token", "bearer"), "alice")
        accountAuth.logout()

        assertTrue(accountAuth.isBoundToAccount("alice"))
        assertFalse(accountAuth.isBoundToAccount("bob"))
        val result = accountAuth.login("bob", "password")
        assertTrue(result.isFailure)
        assertNull(accountAuth.getToken())
        assertEquals(0, mockWebServer.requestCount)
    }
}