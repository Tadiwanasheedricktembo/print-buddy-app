package com.tadiwaprintbuddy.app.data

import java.math.BigDecimal

sealed class BusinessEvent {
    abstract val timestamp: Long
    abstract val balanceAfter: BigDecimal
    abstract val details: List<SettlementHistory>
    var isExpanded: Boolean = false

    data class PaymentReceived(
        val totalPaid: BigDecimal,
        val debtCleared: BigDecimal,
        val creditCreated: BigDecimal,
        val receivedAmount: BigDecimal? = null,
        override val timestamp: Long,
        override val balanceAfter: BigDecimal,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()

    data class NewOrder(
        val orderId: Int,
        val orderTotal: BigDecimal,
        val creditUsed: BigDecimal,
        val cashPaid: BigDecimal,
        val outstanding: BigDecimal,
        val receivedAmount: BigDecimal? = null,
        override val timestamp: Long,
        override val balanceAfter: BigDecimal,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()

    data class DebtAdded(
        val amount: BigDecimal,
        val note: String,
        override val timestamp: Long,
        override val balanceAfter: BigDecimal,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()

    data class Adjustment(
        val amount: BigDecimal,
        val note: String,
        override val timestamp: Long,
        override val balanceAfter: BigDecimal,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()
}
