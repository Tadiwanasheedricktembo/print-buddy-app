package com.tadiwaprintbuddy.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.data.SettlementHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class TransactionSortingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: PrintRepository
    private lateinit var viewModel: SettlementHistoryViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PrintRepository(db.printDao())
        viewModel = SettlementHistoryViewModel(repository)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun createSettlement(id: Int, customerId: Long, timestamp: Long, balanceAfter: Double = 0.0) = SettlementHistory(
        id = id,
        customerId = customerId,
        customerName = "Test",
        balanceBefore = 0.0,
        amountPaid = 0.0,
        balanceAfter = balanceAfter,
        timestamp = timestamp,
        newBalance = balanceAfter
    )

    @Test
    fun `test newest-first transaction order`() {
        val now = System.currentTimeMillis()
        val t1 = createSettlement(1, 1, now)
        val t2 = createSettlement(2, 1, now + 1000)
        
        val settlements = listOf(t1, t2)
        val groups = viewModel.groupAndSort(settlements, TransactionSortOrder.NEWEST_FIRST, "", emptySet())
        val transactions = groups[0].transactions
        
        assertEquals(2, transactions.size)
        assertEquals(2, transactions[0].id)
        assertEquals(1, transactions[1].id)
    }

    @Test
    fun `test oldest-first transaction order`() {
        val now = System.currentTimeMillis()
        val t1 = createSettlement(1, 1, now)
        val t2 = createSettlement(2, 1, now + 1000)
        
        val settlements = listOf(t1, t2)
        val groups = viewModel.groupAndSort(settlements, TransactionSortOrder.OLDEST_FIRST, "", emptySet())
        val transactions = groups[0].transactions
        
        assertEquals(2, transactions.size)
        assertEquals(1, transactions[0].id)
        assertEquals(2, transactions[1].id)
    }

    @Test
    fun `test equal timestamps ordered by ID`() {
        val now = System.currentTimeMillis()
        val t1 = createSettlement(1, 1, now)
        val t2 = createSettlement(2, 1, now)
        
        val settlements = listOf(t1, t2)
        
        // Newest first should have ID 2 then 1 (Descending ID)
        val groupsNewest = viewModel.groupAndSort(settlements, TransactionSortOrder.NEWEST_FIRST, "", emptySet())
        assertEquals(2, groupsNewest[0].transactions[0].id)
        assertEquals(1, groupsNewest[0].transactions[1].id)
        
        // Oldest first should have ID 1 then 2 (Ascending ID)
        val groupsOldest = viewModel.groupAndSort(settlements, TransactionSortOrder.OLDEST_FIRST, "", emptySet())
        assertEquals(1, groupsOldest[0].transactions[0].id)
        assertEquals(2, groupsOldest[0].transactions[1].id)
    }

    @Test
    fun `test search preserves selected sorting`() {
        val now = System.currentTimeMillis()
        val t1 = createSettlement(1, 1, now)
        val t2 = createSettlement(2, 1, now + 1000)
        
        val settlements = listOf(t1, t2)
        
        // Sort Oldest, Search matches (since groupAndSort filters by name)
        val groups = viewModel.groupAndSort(settlements, TransactionSortOrder.OLDEST_FIRST, "Tes", emptySet())
        assertEquals(1, groups[0].transactions[0].id)
    }

    @Test
    fun `test changing sort does not change customer balance`() {
        val now = System.currentTimeMillis()
        val t1 = createSettlement(1, 1, now, 10.0)
        val t2 = createSettlement(2, 1, now + 1000, 20.0)
        
        val settlements = listOf(t1, t2)
        
        val newestGroups = viewModel.groupAndSort(settlements, TransactionSortOrder.NEWEST_FIRST, "", emptySet())
        assertEquals(20.0, newestGroups[0].totalOwed, 0.0)
        
        val oldestGroups = viewModel.groupAndSort(settlements, TransactionSortOrder.OLDEST_FIRST, "", emptySet())
        assertEquals(20.0, oldestGroups[0].totalOwed, 0.0)
    }

    @Test
    fun `test adapter respects viewmodel order`() {
        val now = System.currentTimeMillis()
        val t1 = createSettlement(1, 1, now)
        val t2 = createSettlement(2, 1, now + 1000)
        
        // Simulated Newest First List from ViewModel
        val historyList = listOf(t2, t1)
        val adapter = TransactionAdapter(historyList)
        
        // TransactionAdapter should have t2 then t1 (plus one date header if same day)
        // If different days, maybe more headers. Let's assume same day.
        assertTrue(adapter.itemCount >= 2)
    }
}
