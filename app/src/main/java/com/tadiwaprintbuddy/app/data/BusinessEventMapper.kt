package com.tadiwaprintbuddy.app.data

import java.math.BigDecimal

class BusinessEventMapper {

    fun map(settlements: List<SettlementHistory>): List<BusinessEvent> {
        if (settlements.isEmpty()) return emptyList()

        return settlements.groupBy { it.timestamp }.map { (timestamp, events) ->
            val sortedEvents = events.sortedBy { it.id }
            val lastEvent = sortedEvents.last()
            
            // balanceAfter in SettlementHistory is UI snapshot, we use it for display
            val balanceAfter = if (lastEvent.newBalance.compareTo(BigDecimal.ZERO) != 0 || lastEvent.transactionAmount.compareTo(BigDecimal.ZERO) != 0) lastEvent.newBalance else lastEvent.balanceAfter

            val orderPost = sortedEvents.find { it.ledgerEntryType == "ORDER_POST" }
            if (orderPost != null) {
                val total = orderPost.transactionAmount
                val payments = sortedEvents.filter { it.ledgerEntryType == "PAYMENT" }
                val cashPaid = payments.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amountPaid) }
                val creditUsed = orderPost.amountPaid
                
                BusinessEvent.NewOrder(
                    orderId = orderPost.originId ?: 0,
                    orderTotal = total,
                    creditUsed = creditUsed,
                    cashPaid = cashPaid,
                    outstanding = total.subtract(cashPaid).subtract(creditUsed).max(BigDecimal.ZERO),
                    receivedAmount = orderPost.receivedAmount ?: payments.firstOrNull()?.receivedAmount,
                    timestamp = timestamp,
                    balanceAfter = balanceAfter,
                    details = sortedEvents
                )
            } else {
                val payments = sortedEvents.filter { it.ledgerEntryType == "PAYMENT" }
                val credit = sortedEvents.find { it.ledgerEntryType == "CREDIT" }
                
                if (payments.isNotEmpty() || credit != null) {
                    val totalPaid = payments.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amountPaid) }.add(credit?.amountPaid ?: BigDecimal.ZERO)
                    val debtCleared = payments.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amountPaid) }
                    val creditCreated = credit?.amountPaid ?: BigDecimal.ZERO
                    
                    BusinessEvent.PaymentReceived(
                        totalPaid = totalPaid,
                        debtCleared = debtCleared,
                        creditCreated = creditCreated,
                        receivedAmount = payments.firstOrNull { it.receivedAmount != null }?.receivedAmount ?: credit?.receivedAmount,
                        timestamp = timestamp,
                        balanceAfter = balanceAfter,
                        details = sortedEvents
                    )
                } else {
                    val first = sortedEvents.first()
                    if (first.transactionAmount.compareTo(BigDecimal.ZERO) > 0) {
                        BusinessEvent.DebtAdded(
                            amount = first.transactionAmount,
                            note = first.note,
                            timestamp = timestamp,
                            balanceAfter = balanceAfter,
                            details = sortedEvents
                        )
                    } else {
                        BusinessEvent.Adjustment(
                            amount = first.transactionAmount,
                            note = first.note,
                            timestamp = timestamp,
                            balanceAfter = balanceAfter,
                            details = sortedEvents
                        )
                    }
                }
            }
        }.sortedByDescending { it.timestamp }
    }
}
