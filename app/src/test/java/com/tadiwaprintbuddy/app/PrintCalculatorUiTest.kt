package com.tadiwaprintbuddy.app

import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class PrintCalculatorUiTest {

    @Test
    fun summaryRefreshesWhenPagesAndBatchesChange() {
        val activity = Robolectric.buildActivity(PrintCalculatorActivity::class.java).setup().get()
        val bwPages = activity.findViewById<android.widget.EditText>(R.id.editBwPages)
        val colorPages = activity.findViewById<android.widget.EditText>(R.id.editColorPages)

        bwPages.setText("4")
        colorPages.setText("2")
        assertEquals("4", activity.findViewById<TextView>(R.id.textTotalBwPages).text.toString())
        assertEquals("2", activity.findViewById<TextView>(R.id.textTotalColorPages).text.toString())
        assertEquals("6", activity.findViewById<TextView>(R.id.textTotalPages).text.toString())
        assertTrue(activity.findViewById<TextView>(R.id.textGrandTotal).text.toString().contains("40.00"))

        activity.findViewById<MaterialButton>(R.id.btnAddBatch).performClick()
        bwPages.setText("3")
        colorPages.setText("1")

        assertEquals("7", activity.findViewById<TextView>(R.id.textTotalBwPages).text.toString())
        assertEquals("3", activity.findViewById<TextView>(R.id.textTotalColorPages).text.toString())
        assertEquals("10", activity.findViewById<TextView>(R.id.textTotalPages).text.toString())
        assertTrue(activity.findViewById<TextView>(R.id.textGrandTotal).text.toString().contains("65.00"))
        activity.finish()
    }
}