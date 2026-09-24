package com.tadiwaprintbuddy.app

import androidx.lifecycle.*
import com.tadiwaprintbuddy.app.data.UpiAccountTransaction
import com.tadiwaprintbuddy.app.data.PrintRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.*

data class UpiAccountPeriodSummary(
    val received: BigDecimal,
    val returned: BigDecimal,
    val netFlow: BigDecimal,
    val count: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
class UpiAccountViewModel(private val repository: PrintRepository) : ViewModel() {

    val balance = repository.getUpiAccountBalanceFlow().asLiveData()
    
    private val _filterPeriod = MutableStateFlow("Today")
    val filterPeriod: LiveData<String> = _filterPeriod.asLiveData()

    val transactions = _filterPeriod.flatMapLatest { period ->
        val range = getRange(period)
        repository.getFilteredUpiAccountTransactions(range.first, range.second)
    }.asLiveData()

    private val _periodSummary = MutableLiveData<UpiAccountPeriodSummary>()
    val periodSummary: LiveData<UpiAccountPeriodSummary> = _periodSummary

    init {
        calculateSummary(_filterPeriod.value)
    }

    fun setPeriod(period: String) {
        _filterPeriod.value = period
        calculateSummary(period)
    }

    private fun calculateSummary(period: String) {
        viewModelScope.launch {
            val range = getRange(period)
            val received = repository.getUpiAccountReceivedBetween(range.first, range.second)
            val returned = repository.getUpiAccountReturnedBetween(range.first, range.second)
            val netFlow = repository.getUpiAccountNetFlowBetween(range.first, range.second)
            val count = repository.getUpiAccountTransactionCountBetween(range.first, range.second)

            _periodSummary.value = UpiAccountPeriodSummary(
                received = received,
                returned = returned,
                netFlow = netFlow,
                count = count
            )
        }
    }

    private fun getRange(period: String): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        when (period) {
            "Today" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            "This Week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            "This Month" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            }
            "All Time" -> return Pair(0L, end)
        }
        return Pair(cal.timeInMillis, end)
    }

    fun addMoney(amount: BigDecimal, note: String?) {
        viewModelScope.launch {
            repository.insertUpiAccountTransaction(amount, "ADD", note)
            calculateSummary(_filterPeriod.value)
        }
    }

    fun returnMoney(amount: BigDecimal, note: String?) {
        viewModelScope.launch {
            repository.insertUpiAccountTransaction(amount, "RETURN", note)
            calculateSummary(_filterPeriod.value)
        }
    }

    fun resetBalance() {
        viewModelScope.launch {
            val currentBalance = repository.getCurrentUpiAccountBalance()
            if (currentBalance.compareTo(BigDecimal.ZERO) != 0) {
                repository.insertUpiAccountTransaction(BigDecimal.ZERO, "RESET", "Balance reset to zero")
                calculateSummary(_filterPeriod.value)
            }
        }
    }
    fun deleteTransaction(transaction: UpiAccountTransaction) {
        viewModelScope.launch {
            repository.deleteUpiAccountTransaction(transaction)
            calculateSummary(_filterPeriod.value)
        }
    }
}

class UpiAccountViewModelFactory(private val repository: PrintRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UpiAccountViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UpiAccountViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
