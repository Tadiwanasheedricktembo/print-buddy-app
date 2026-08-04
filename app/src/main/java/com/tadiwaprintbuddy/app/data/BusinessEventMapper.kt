package com.tadiwaprintbuddy.app.data

class BusinessEventMapper {
    fun map(settlements: List<SettlementHistory>): List<BusinessEvent> {
        // Group by timestamp (atomic events share it)
        return settlements.groupBy { it.timestamp }
            .map { (timestamp, events) ->
                val sortedEvents = events.sortedBy { it.id }
                
                val orderPost = sortedEvents.find { it.ledgerEntryType == "ORDER_POST" }
                val payments = sortedEvents.filter { it.ledgerEntryType == "PAYMENT" }
                val credit = sortedEvents.find { it.ledgerEntryType == "CREDIT" }
                
                val lastEvent = sortedEvents.last()
                val balanceAfter = if (lastEvent.newBalance != 0.0 || lastEvent.transactionAmount != 0.0) lastEvent.newBalance else lastEvent.balanceAfter

                when {
                    orderPost != null -> {
                        val total = orderPost.transactionAmount
                        val cashPaid = payments.sumOf { it.amountPaid }
                        val creditUsed = orderPost.amountPaid
                        BusinessEvent.NewOrder(
                            orderId = orderPost.originId ?: 0,
                            orderTotal = total,
                            creditUsed = creditUsed,
                            cashPaid = cashPaid,
                            outstanding = total - cashPaid - creditUsed,
                            timestamp = timestamp,
                            balanceAfter = balanceAfter,
                            details = sortedEvents
                        )
                    }
                    payments.isNotEmpty() || credit != null -> {
                        val totalPaid = payments.sumOf { it.amountPaid } + (credit?.amountPaid ?: 0.0)
                        val debtCleared = payments.sumOf { it.amountPaid }
                        val creditCreated = credit?.amountPaid ?: 0.0
                        BusinessEvent.PaymentReceived(
                            totalPaid = totalPaid,
                            debtCleared = debtCleared,
                            creditCreated = creditCreated,
                            timestamp = timestamp,
                            balanceAfter = balanceAfter,
                            details = sortedEvents
                        )
                    }
                    else -> {
                        val first = sortedEvents.first()
                        if (first.transactionAmount > 0) {
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
            }
    }
}
