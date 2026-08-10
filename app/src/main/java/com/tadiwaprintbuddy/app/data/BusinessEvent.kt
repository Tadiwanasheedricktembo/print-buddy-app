package com.tadiwaprintbuddy.app.data

sealed class BusinessEvent {
    abstract val timestamp: Long
    abstract val balanceAfter: Double
    abstract val details: List<SettlementHistory>
    var isExpanded: Boolean = false

    data class PaymentReceived(
        val totalPaid: Double,
        val debtCleared: Double,
        val creditCreated: Double,
        val receivedAmount: Double? = null,
        override val timestamp: Long,
        override val balanceAfter: Double,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()

    data class NewOrder(
        val orderId: Int,
        val orderTotal: Double,
        val creditUsed: Double,
        val cashPaid: Double,
        val outstanding: Double,
        val receivedAmount: Double? = null,
        override val timestamp: Long,
        override val balanceAfter: Double,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()

    data class DebtAdded(
        val amount: Double,
        val note: String,
        override val timestamp: Long,
        override val balanceAfter: Double,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()

    data class Adjustment(
        val amount: Double,
        val note: String,
        override val timestamp: Long,
        override val balanceAfter: Double,
        override val details: List<SettlementHistory>
    ) : BusinessEvent()
}
