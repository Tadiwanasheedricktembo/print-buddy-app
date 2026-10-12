package com.tadiwaprintbuddy.app

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.tadiwaprintbuddy.app.data.AppDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class CustomerCreditAnalyticsUiTest {

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
        Dispatchers.resetMain()
    }

    @Test
    fun `home credit controls create a customer order rendered by dashboard analytics`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getDatabase(context)
        withContext(Dispatchers.IO) { database.clearAllTables() }

        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val orderViewModel = ViewModelProvider(home)[MainViewModel::class.java]

        saveOrderThroughHomeControls(this, home, orderViewModel, "UI Existing Customer", "10.00", R.id.btnCash)
        saveOrderThroughHomeControls(this, home, orderViewModel, "UI Existing Customer", "300.00", R.id.btnCredit)

        val dao = database.printDao()
        val customer = dao.getCustomerByNormalizedName("ui existing customer")!!
        val orders = dao.getOrdersBetween(0L, Long.MAX_VALUE)
            .filter { it.customerId == customer.id }
            .sortedBy { it.date }
        val paidOrder = orders.first()
        val creditOrder = orders.last()
        val creditSettlement = dao.getSettlementsForOrder(creditOrder.id)
            .single { it.ledgerEntryType == "ORDER_POST" }
        val projection = dao.getDebtorCreditById(customer.id)!!

        assertEquals(0, BigDecimal("10.00").compareTo(paidOrder.totalAmount))
        assertEquals(0, BigDecimal("10.00").compareTo(paidOrder.paidAmount))
        assertEquals("PAID", paidOrder.paymentStatus)
        assertEquals("CASH", paidOrder.paymentMethod)

        assertEquals(0, BigDecimal("300.00").compareTo(creditOrder.totalAmount))
        assertEquals(0, BigDecimal.ZERO.compareTo(creditOrder.paidAmount))
        assertEquals("NONE", creditOrder.paymentMethod)
        assertEquals("UNPAID", creditOrder.paymentStatus)
        assertEquals("ACTIVE", creditOrder.orderStatus)
        assertEquals(0, BigDecimal.ZERO.compareTo(creditOrder.previousBalance))
        assertEquals(0, BigDecimal("300.00").compareTo(creditOrder.newBalance))
        assertEquals(customer.id, creditOrder.customerId)
        assertEquals(customer.id, creditSettlement.customerId)
        assertEquals(0, BigDecimal("300.00").compareTo(creditSettlement.transactionAmount))
        assertEquals("ORDER_POST", creditSettlement.ledgerEntryType)
        assertEquals(creditOrder.id, creditSettlement.originId)
        assertEquals("OWES_ME", creditSettlement.paymentMethod)
        assertEquals(0, BigDecimal("300.00").compareTo(projection.amount))

        val dashboard = Robolectric.buildActivity(DashboardActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val dashboardViewModel = ViewModelProvider(dashboard)[DashboardViewModel::class.java]
        awaitMainCondition("dashboard analytics state") {
            val state = dashboardViewModel.uiState.value
            !state.isLoading && state.metrics != null &&
                state.totalReceivables.compareTo(BigDecimal("300.00")) == 0
        }
        val analyticsState = dashboardViewModel.uiState.value
        awaitMainCondition("rendered Credit Extended insight") {
            renderedInsightValue(dashboard, "Credit Extended").orEmpty().contains("300.00")
        }
        assertAnalyticsUiShowsCredit(dashboard, analyticsState)

        home.finish()
        dashboard.finish()
    }

    private suspend fun saveOrderThroughHomeControls(
        scope: TestScope,
        activity: MainActivity,
        viewModel: MainViewModel,
        customerName: String,
        amount: String,
        paymentToggleId: Int
    ) {
        activity.runOnUiThread {
            activity.findViewById<AutoCompleteTextView>(R.id.editCustomerName)
                .setText(customerName, false)
            activity.findViewById<EditText>(R.id.editPrice).setText(amount)
        }
        shadowOf(Looper.getMainLooper()).idle()
        scope.runCurrent()
        viewModel.uiState.first {
            it.customerName == customerName && it.total.compareTo(BigDecimal(amount)) == 0
        }
        val completion = scope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.events.first()
        }
        activity.runOnUiThread {
            activity.findViewById<com.google.android.material.button.MaterialButton>(paymentToggleId).performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()
        scope.runCurrent()
        val event = completion.await()
        assertTrue("Home UI save failed: $event", event is MainViewModel.MainEvent.OrderCompleted)
    }

    private fun renderedInsightValue(activity: DashboardActivity, title: String): String? {
        val insights = activity.findViewById<LinearLayout>(R.id.layoutInsightsStrip)
        for (index in 0 until insights.childCount) {
            val card = insights.getChildAt(index)
            if (card.findViewById<TextView>(R.id.textInsightIconTitle).text.toString() == title) {
                return card.findViewById<TextView>(R.id.textInsightValue).text.toString()
            }
        }
        return null
    }

    private suspend fun TestScope.awaitMainCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for $description")
            }
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun assertAnalyticsUiShowsCredit(
        activity: DashboardActivity,
        state: DashboardUiState
    ) {
        assertEquals(0, BigDecimal("10.00").compareTo(state.metrics!!.revenue))
        assertEquals(0, BigDecimal("300.00").compareTo(state.metrics.creditExtended))
        assertEquals(0, BigDecimal("300.00").compareTo(state.totalReceivables))
        assertTrue(state.insights.single { it.title == "Credit Extended" }.value.contains("300.00"))
        assertTrue(state.insights.single { it.title == "⏳ Outstanding" }.value.contains("300.00"))

        val insights = activity.findViewById<LinearLayout>(R.id.layoutInsightsStrip)
        val rendered = (0 until insights.childCount).associate { index ->
            val card = insights.getChildAt(index)
            card.findViewById<TextView>(R.id.textInsightIconTitle).text.toString() to
                card.findViewById<TextView>(R.id.textInsightValue).text.toString()
        }
        assertTrue(rendered["Credit Extended"].orEmpty().contains("300.00"))
        assertTrue(rendered["⏳ Outstanding"].orEmpty().contains("300.00"))
        assertTrue(rendered["Customer Credit"].orEmpty().contains("0.00"))
    }

}