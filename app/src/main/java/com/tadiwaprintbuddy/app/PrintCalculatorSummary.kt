package com.tadiwaprintbuddy.app

import java.math.BigDecimal

enum class PrintLineType {
    BLACK_AND_WHITE,
    COLOR,
    OTHER
}

data class PrintOrderLine(
    val type: PrintLineType,
    val description: String,
    val quantity: Int,
    val unitPrice: BigDecimal
) {
    val safeQuantity: Int = if (type == PrintLineType.OTHER) 1 else quantity.coerceAtLeast(0)
    val safeUnitPrice: BigDecimal = unitPrice.max(BigDecimal.ZERO)
    val totalPrice: BigDecimal = safeUnitPrice.multiply(BigDecimal(safeQuantity))

    fun toStoredServiceName(copies: Int = 1): String = "$STORAGE_PREFIX${type.name}:COPIES=${copies.coerceAtLeast(1)}:$description"

    companion object {
        private const val STORAGE_PREFIX = "PRINT_BUDDY_"

        fun typeFromStoredServiceName(serviceName: String): PrintLineType? {
            if (!serviceName.startsWith(STORAGE_PREFIX)) return null
            val typeName = serviceName.removePrefix(STORAGE_PREFIX).substringBefore(':')
            return PrintLineType.entries.firstOrNull { it.name == typeName }
        }

        fun displayDescription(serviceName: String): String {
            if (!serviceName.startsWith(STORAGE_PREFIX)) return serviceName
            val encodedDescription = serviceName.substringAfter(':', serviceName)
            return if (encodedDescription.startsWith("COPIES=")) {
                encodedDescription.substringAfter(':', encodedDescription)
            } else encodedDescription
        }

        fun copiesFromStoredServiceName(serviceName: String): Int {
            val encoded = serviceName.substringAfter(":COPIES=", "").substringBefore(':')
            return encoded.toIntOrNull()?.coerceAtLeast(1) ?: 1
        }

        fun withCopyCount(serviceName: String, copies: Int): String {
            val type = typeFromStoredServiceName(serviceName) ?: return serviceName
            return PrintOrderLine(type, displayDescription(serviceName), 0, BigDecimal.ZERO)
                .toStoredServiceName(copies)
        }
    }
}

data class PrintCalculatorSummary(val lines: List<PrintOrderLine>) {
    val blackAndWhitePages: Int = sumPages(PrintLineType.BLACK_AND_WHITE)
    val colorPages: Int = sumPages(PrintLineType.COLOR)
    val totalPages: Int = (blackAndWhitePages.toLong() + colorPages).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val blackAndWhiteAmount: BigDecimal = amountFor(PrintLineType.BLACK_AND_WHITE)
    val colorAmount: BigDecimal = amountFor(PrintLineType.COLOR)
    val otherAmount: BigDecimal = amountFor(PrintLineType.OTHER)
    val totalAmount: BigDecimal = lines.fold(BigDecimal.ZERO) { total, line -> total.add(line.totalPrice) }

    private fun sumPages(type: PrintLineType): Int = lines
        .asSequence()
        .filter { it.type == type }
        .fold(0L) { total, line -> (total + line.safeQuantity).coerceAtMost(Int.MAX_VALUE.toLong()) }
        .toInt()

    private fun amountFor(type: PrintLineType): BigDecimal = lines
        .asSequence()
        .filter { it.type == type }
        .fold(BigDecimal.ZERO) { total, line -> total.add(line.totalPrice) }
}