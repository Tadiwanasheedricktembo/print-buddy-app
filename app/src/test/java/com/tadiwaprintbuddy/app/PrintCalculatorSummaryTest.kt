package com.tadiwaprintbuddy.app

import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.Test

class PrintCalculatorSummaryTest {

    @Test
    fun blackAndWhiteOnlyTotalsPagesAndAmount() {
        val summary = PrintCalculatorSummary(
            listOf(PrintOrderLine(PrintLineType.BLACK_AND_WHITE, "B&W", 12, BigDecimal("2.50")))
        )

        assertEquals(12, summary.blackAndWhitePages)
        assertEquals(0, summary.colorPages)
        assertEquals(12, summary.totalPages)
        assertEquals(0, BigDecimal("30.00").compareTo(summary.totalAmount))
    }

    @Test
    fun colorOnlyTotalsPagesAndAmount() {
        val summary = PrintCalculatorSummary(
            listOf(PrintOrderLine(PrintLineType.COLOR, "Color", 3, BigDecimal("8.00")))
        )

        assertEquals(0, summary.blackAndWhitePages)
        assertEquals(3, summary.colorPages)
        assertEquals(3, summary.totalPages)
        assertEquals(0, BigDecimal("24.00").compareTo(summary.totalAmount))
    }

    @Test
    fun mixedBatchesAggregatePagesAndAmounts() {
        val summary = PrintCalculatorSummary(
            listOf(
                PrintOrderLine(PrintLineType.BLACK_AND_WHITE, "Document A", 10, BigDecimal("2")),
                PrintOrderLine(PrintLineType.COLOR, "Document A", 2, BigDecimal("7")),
                PrintOrderLine(PrintLineType.BLACK_AND_WHITE, "Document B", 5, BigDecimal("3")),
                PrintOrderLine(PrintLineType.OTHER, "Binding", 0, BigDecimal("12.50"))
            )
        )

        assertEquals(15, summary.blackAndWhitePages)
        assertEquals(2, summary.colorPages)
        assertEquals(17, summary.totalPages)
        assertEquals(0, BigDecimal("61.50").compareTo(summary.totalAmount))
    }

    @Test
    fun invalidNegativeInputsCannotCreateNegativeTotals() {
        val summary = PrintCalculatorSummary(
            listOf(PrintOrderLine(PrintLineType.BLACK_AND_WHITE, "Invalid", -4, BigDecimal("-2")))
        )

        assertEquals(0, summary.blackAndWhitePages)
        assertEquals(0, summary.totalPages)
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.totalAmount))
    }
}