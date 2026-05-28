package com.tadiwaprintbuddy.app

import androidx.lifecycle.*
import com.tadiwaprintbuddy.app.data.BeautyTransaction
import com.tadiwaprintbuddy.app.data.PrintRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.*

data class BeautyPeriodSummary(
    val received: Double,
    val returned: Double,
    val netFlow: Double,
    val count: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
class BeautyAccountViewModel(private val repository: PrintRepository) : ViewModel() {

    val balance = repository.getBeautyBalanceFlow().asLiveData()
    
    private val _filterPeriod = MutableStateFlow("Today")
    val filterPeriod: LiveData<String> = _filterPeriod.asLiveData()

    val transactions = _filterPeriod.flatMapLatest { period ->
        val range = getRange(period)
        repository.getFilteredBeautyTransactions(range.first, range.second)
    }.asLiveData()

    private val _periodSummary = MutableLiveData<BeautyPeriodSummary>()
    val periodSummary: LiveData<BeautyPeriodSummary> = _periodSummary

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
            val received = repository.getBeautyReceivedBetween(range.first, range.second)
            val returned = repository.getBeautyReturnedBetween(range.first, range.second)
            val count = repository.getBeautyTransactionCountBetween(range.first, range.second)
            
            _periodSummary.value = BeautyPeriodSummary(
                received = received,
                returned = returned,
                netFlow = received - returned,
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

    fun addMoney(amount: Double, note: String?) {
        viewModelScope.launch {
            repository.insertBeautyTransaction(amount, "ADD", note)
            calculateSummary(_filterPeriod.value)
        }
    }

    fun returnMoney(amount: Double, note: String?) {
        viewModelScope.launch {
            repository.insertBeautyTransaction(amount, "RETURN", note)
            calculateSummary(_filterPeriod.value)
        }
    }

    fun resetBalance() {
        viewModelScope.launch {
            val currentBalance = repository.getCurrentBeautyBalance()
            if (currentBalance != 0.0) {
                repository.insertBeautyTransaction(0.0, "RESET", "Balance reset to zero")
                calculateSummary(_filterPeriod.value)
            }
        }
    }

    fun deleteTransaction(transaction: BeautyTransaction) {
        viewModelScope.launch {
            repository.deleteBeautyTransaction(transaction)
            calculateSummary(_filterPeriod.value)
        }
    }
}

class BeautyAccountViewModelFactory(private val repository: PrintRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BeautyAccountViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BeautyAccountViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
