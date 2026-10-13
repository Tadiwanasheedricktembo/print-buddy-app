package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class CustomerCreditAnalyticsInstrumentedTest {

    @Test
    fun homeCreditOrderAppearsOnAnalyticsScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = AppDatabase.getDatabase(context)
        runBlocking { database.clearAllTables() }

        val homeScenario = ActivityScenario.launch(MainActivity::class.java)
        saveOrderThroughHome(homeScenario, database, "Analytics Customer A", "10", R.id.btnCash, 1)
        saveOrderThroughHome(homeScenario, database, "Analytics Customer A", "300", R.id.btnCredit, 2)
        saveOrderThroughHome(homeScenario, database, "Analytics Customer B", "500", R.id.btnCredit, 3)

        val dao = database.printDao()
        val customer = runBlocking {
            withTimeout(10_000) {
                while (true) {
                    dao.getCustomerByNormalizedName("analytics customer a")?.let { break }
                    delay(50)
                }
            }
            dao.getCustomerByNormalizedName("analytics customer a")
        }
        assertNotNull(customer)
        customer!!

        val orders = runBlocking {
            withTimeout(10_000) {
                while (true) {
                    val current = dao.getOrdersBetween(0L, Long.MAX_VALUE)
                        .filter { it.customerId == customer.id }
                    if (current.size == 2) break
                    delay(50)
                }
            }
            dao.getOrdersBetween(0L, Long.MAX_VALUE)
                .filter { it.customerId == customer.id }
                .sortedBy { it.date }
        }
        val paidOrder = orders.first()
        val creditOrder = orders.last()
        val (creditPost, projection) = runBlocking {
            val settlement = dao.getSettlementsForOrder(creditOrder.id)
                .single { it.ledgerEntryType == "ORDER_POST" }
            settlement to dao.getDebtorCreditById(customer.id)
        }

        assertEquals(0, BigDecimal("10").compareTo(paidOrder.totalAmount))
        assertEquals("PAID", paidOrder.paymentStatus)
        assertEquals(0, BigDecimal("300").compareTo(creditOrder.totalAmount))
        assertEquals(0, BigDecimal.ZERO.compareTo(creditOrder.paidAmount))
        assertEquals("UNPAID", creditOrder.paymentStatus)
        assertEquals(customer.id, creditOrder.customerId)
        assertEquals(customer.id, creditPost.customerId)
        assertEquals(creditOrder.id, creditPost.originId)
        assertEquals("OWES_ME", creditPost.paymentMethod)
        assertEquals("ORDER_POST", creditPost.ledgerEntryType)
        assertEquals(0, BigDecimal("300").compareTo(creditPost.transactionAmount))
        assertEquals(0, BigDecimal("300").compareTo(projection!!.amount))

        val customerB = runBlocking { dao.getCustomerByNormalizedName("analytics customer b") }!!
        val customerBOrder = runBlocking {
            dao.getOrdersBetween(0L, Long.MAX_VALUE).single { it.customerId == customerB.id }
        }
        assertEquals(0, BigDecimal("500").compareTo(customerBOrder.totalAmount))
        assertEquals(0, BigDecimal("500").compareTo(daoBalance(dao, customerB.id)))

        homeScenario.close()
        dashboardState(expectedReceivables = "800", expectedCredit = "800", expectedRevenue = "10")

        val balanceScenario = ActivityScenario.launch(DebtorCreditActivity::class.java)
        balanceScenario.onActivity { activity ->
            val recycler = activity.findViewById<RecyclerView>(R.id.recycler_debtor_credits)
            val customerPosition = (0 until recycler.childCount).first { position ->
                recycler.getChildAt(position)
                    .findViewById<TextView>(R.id.textCustomerName).text.toString()
                    .equals("ANALYTICS CUSTOMER A", ignoreCase = true)
            }
            recycler.getChildAt(customerPosition)
                .findViewById<Button>(R.id.buttonUpdateAmount)
                .performClick()
        }
        enterDialogAmountAndApply("editPaymentAmount", "100")
        balanceScenario.close()

        AppDatabase.closeDatabase()
        val refreshedDao = AppDatabase.getDatabase(context).printDao()
        val customerABalance = runBlocking {
            withTimeout(10_000) {
                while (daoBalance(refreshedDao, customer.id).compareTo(BigDecimal("200")) != 0) {
                    delay(50)
                }
                daoBalance(refreshedDao, customer.id)
            }
        }
        assertEquals("Customer A balance was $customerABalance", 0, BigDecimal("200").compareTo(customerABalance))
        assertEquals(0, BigDecimal("500").compareTo(daoBalance(refreshedDao, customerB.id)))
        assertEquals(0, BigDecimal("700").compareTo(runBlocking { repositoryReceivables(refreshedDao) }))
        assertEquals(2, runBlocking {
            refreshedDao.getAllOrders().count { it.totalAmount.compareTo(BigDecimal("300")) == 0 || it.totalAmount.compareTo(BigDecimal("500")) == 0 }
        })

        dashboardState(expectedReceivables = "700", expectedCredit = "800", expectedRevenue = "110")

        val upiScenario = ActivityScenario.launch(BeautyAccountActivity::class.java)
        upiScenario.onActivity { activity ->
            activity.findViewById<Button>(R.id.btnAddMoney).performClick()
        }
        enterVisibleDialogAmountAndClick("1000", "Add")
        var observedUpiBalance: BigDecimal? = null
        val upiBalanceDeadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < upiBalanceDeadline &&
            observedUpiBalance?.compareTo(BigDecimal("1000")) != 0
        ) {
            upiScenario.onActivity { activity ->
                observedUpiBalance = ViewModelProvider(activity)[BeautyAccountViewModel::class.java]
                    .balance.value
            }
            if (observedUpiBalance?.compareTo(BigDecimal("1000")) != 0) SystemClock.sleep(50)
        }
        assertEquals(0, BigDecimal("1000").compareTo(observedUpiBalance))
        upiScenario.close()

        AppDatabase.closeDatabase()
        val afterUpiDao = AppDatabase.getDatabase(context).printDao()
        val upiAmounts = runBlocking { afterUpiDao.getUpiAccountTransactionAmountsBetween(0L, Long.MAX_VALUE) }
        assertEquals(0, BigDecimal("1000").compareTo(upiAmounts.fold(BigDecimal.ZERO, BigDecimal::add)))
        val upiTopUp = runBlocking {
            afterUpiDao.getAllSettlements().single { it.ledgerEntryType == "UPI_ACCOUNT_TOPUP" }
        }
        assertEquals(0L, upiTopUp.customerId)
        assertEquals("UPI", upiTopUp.paymentMethod)
        assertEquals(0, BigDecimal("1000").compareTo(upiTopUp.transactionAmount))
        assertEquals(0, BigDecimal("200").compareTo(daoBalance(afterUpiDao, customer.id)))
        assertEquals(0, BigDecimal("500").compareTo(daoBalance(afterUpiDao, customerB.id)))
        dashboardState(expectedReceivables = "700", expectedCredit = "800", expectedRevenue = "1110")
    }

    private fun dashboardState(
        expectedReceivables: String,
        expectedCredit: String,
        expectedRevenue: String
    ): DashboardUiState {
        val dashboardScenario = ActivityScenario.launch(DashboardActivity::class.java)
        lateinit var dashboardViewModel: DashboardViewModel
        dashboardScenario.onActivity { activity ->
            dashboardViewModel = ViewModelProvider(activity)[DashboardViewModel::class.java]
        }
        val state = runBlocking {
            withTimeout(10_000) {
                dashboardViewModel.uiState.first {
                    !it.isLoading && it.metrics != null &&
                        it.totalReceivables.compareTo(BigDecimal(expectedReceivables)) == 0
                }
            }
        }
        assertEquals(0, BigDecimal(expectedCredit).compareTo(state.metrics!!.creditExtended))
        assertEquals(0, BigDecimal(expectedRevenue).compareTo(state.metrics.revenue))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        dashboardScenario.onActivity { activity ->
            val insights = activity.findViewById<LinearLayout>(R.id.layoutInsightsStrip)
            val cards = (0 until insights.childCount).map { insights.getChildAt(it) }
            fun valueFor(title: String): String? = cards.firstOrNull { card ->
                card.findViewById<TextView>(R.id.textInsightIconTitle).text.toString()
                    .contains(title)
            }?.findViewById<TextView>(R.id.textInsightValue)?.text?.toString()

            assertEquals("₹$expectedCredit.00", valueFor("Credit Extended"))
            assertEquals("₹$expectedReceivables.00", valueFor("Outstanding"))
        }
        dashboardScenario.close()
        return state
    }

    private fun enterDialogAmountAndApply(fieldId: String, amount: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        var root: AccessibilityNodeInfo? = null
        var amountField: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < deadline && amountField == null) {
            root = automation.rootInActiveWindow
            amountField = root?.findAccessibilityNodeInfosByViewId(
                "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}:id/$fieldId"
            )?.firstOrNull()
            if (amountField == null) SystemClock.sleep(50)
        }
        val field = requireNotNull(amountField) { "Payment dialog field $fieldId was not found" }
        field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, amount)
            }
        )
        val positiveButton = root?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
            ?: automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
        requireNotNull(positiveButton) { "Apply Payment button was not found" }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun enterVisibleDialogAmountAndClick(amount: String, buttonText: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        var root: AccessibilityNodeInfo? = null
        var amountField: AccessibilityNodeInfo? = null
        var button: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < deadline && (amountField == null || button == null)) {
            root = automation.rootInActiveWindow
            amountField = findNodeByClass(root, "android.widget.EditText")
            button = root?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
            if (amountField == null || button == null) SystemClock.sleep(50)
        }
        val didSetAmount = requireNotNull(amountField) {
            "Amount field was not found in the $buttonText dialog"
        }.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, amount)
                }
            )
        assertTrue("Could not enter $amount in the $buttonText dialog", didSetAmount)
        val positiveButton = requireNotNull(button) { "$buttonText dialog button was not found" }
        assertTrue("$buttonText dialog button was not clickable", positiveButton.isClickable)
        val bounds = Rect()
        positiveButton.getBoundsInScreen(bounds)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(downTime, downTime + 1, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        instrumentation.sendPointerSync(down)
        instrumentation.sendPointerSync(up)
        down.recycle()
        up.recycle()
    }

    private fun findNodeByClass(node: AccessibilityNodeInfo?, className: String): AccessibilityNodeInfo? {
        if (node?.className == className) return node
        for (index in 0 until (node?.childCount ?: 0)) {
            findNodeByClass(node?.getChild(index), className)?.let { return it }
        }
        return null
    }

    private fun daoBalance(dao: com.tadiwaprintbuddy.app.data.PrintDao, customerId: Long): BigDecimal =
        runBlocking { dao.getAuthoritativeCustomerBalance(customerId) }

    private fun repositoryReceivables(dao: com.tadiwaprintbuddy.app.data.PrintDao): BigDecimal =
        runBlocking { com.tadiwaprintbuddy.app.data.PrintRepository(dao).getTotalReceivables() }

    private fun saveOrderThroughHome(
        scenario: ActivityScenario<MainActivity>,
        database: AppDatabase,
        customer: String,
        amount: String,
        paymentToggle: Int,
        expectedOrderCount: Int
    ) {
        lateinit var viewModel: MainViewModel
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
            activity.findViewById<android.widget.AutoCompleteTextView>(R.id.editCustomerName)
                .setText(customer, false)
            activity.findViewById<android.widget.EditText>(R.id.editPrice).setText(amount)
        }
        runBlocking {
            withTimeout(10_000) {
                viewModel.uiState.first {
                    it.customerName == customer &&
                        it.total.compareTo(BigDecimal(amount)) == 0 &&
                        !it.isLoading
                }
            }
        }
        scenario.onActivity { activity ->
            activity.findViewById<com.google.android.material.button.MaterialButton>(paymentToggle).performClick()
        }
        runBlocking {
            withTimeout(10_000) {
                while (database.printDao().getAllOrders().size < expectedOrderCount) {
                    delay(50)
                }
            }
        }
    }
}