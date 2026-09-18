package com.tadiwaprintbuddy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.data.SettlementHistory
import com.tadiwaprintbuddy.app.data.BusinessEvent
import com.tadiwaprintbuddy.app.data.BusinessEventMapper
import com.tadiwaprintbuddy.app.data.CustomerEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Calendar

enum class TransactionSortOrder {
    OLDEST_FIRST,
    NEWEST_FIRST
}

data class GroupedSettlement(
    val customerId: Long,
    val customerName: String,
    val totalOwed: BigDecimal,
    val events: List<BusinessEvent>,
    val rawTransactions: List<SettlementHistory>,
    var isExpanded: Boolean = false
)

class SettlementHistoryViewModel(private val repository: PrintRepository) : ViewModel() {

    private val mapper = BusinessEventMapper()

    private val _sortOrder = MutableStateFlow(TransactionSortOrder.NEWEST_FIRST)
    val sortOrder: StateFlow<TransactionSortOrder> = _sortOrder.asStateFlow()

    private val _filterPeriod = MutableStateFlow("Today")
    val filterPeriod: StateFlow<String> = _filterPeriod.asStateFlow()

    private val _filterMethod = MutableStateFlow("All")
    val filterMethod: StateFlow<String> = _filterMethod.asStateFlow()

    private val _allSettlements = MutableStateFlow<List<SettlementHistory>>(emptyList())
    private val _allCustomers = MutableStateFlow<List<CustomerEntity>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _expandedCustomerIds = MutableStateFlow<Set<Long>>(emptySet())
    
    val groupedSettlements: StateFlow<List<GroupedSettlement>> = combine(
        _allSettlements, 
        _allCustomers,
        _sortOrder, 
        _searchQuery, 
        _expandedCustomerIds
    ) { settlements, customers, order, query, expandedIds ->
        groupAndSort(settlements, customers, order, query, expandedIds)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun groupAndSort(
        settlements: List<SettlementHistory>,
        customers: List<CustomerEntity>,
        order: TransactionSortOrder,
        query: String,
        expandedIds: Set<Long>
    ): List<GroupedSettlement> {
        val customerMap = customers.associateBy { it.id }
        
        return settlements.groupBy { it.customerId }
            .map { (id, trans) ->
                val customer = customerMap[id]
                val name = customer?.displayName ?: trans.firstOrNull { it.customerName.isNotBlank() }?.customerName ?: "Unknown"
                
                // Authoritative balance derived from the most recent transaction (by time then ID)
                val mostRecent = trans.maxWithOrNull(compareBy<SettlementHistory> { it.timestamp }.thenBy { it.id })
                val balance = mostRecent?.let { 
                    if (it.newBalance.compareTo(BigDecimal.ZERO) != 0 || it.transactionAmount.compareTo(BigDecimal.ZERO) != 0) it.newBalance else it.balanceAfter 
                } ?: BigDecimal.ZERO

                val businessEvents = mapper.map(trans).let { events ->
                    when (order) {
                        TransactionSortOrder.NEWEST_FIRST -> events.sortedByDescending { it.timestamp }
                        TransactionSortOrder.OLDEST_FIRST -> events.sortedBy { it.timestamp }
                    }
                }

                GroupedSettlement(
                    customerId = id,
                    customerName = name,
                    totalOwed = balance,
                    events = businessEvents,
                    rawTransactions = trans,
                    isExpanded = expandedIds.contains(id)
                )
            }
            .filter { it.customerName.contains(query, ignoreCase = true) }
            .sortedBy { it.customerName }
    }

    fun loadSettlements() {
        viewModelScope.launch {
            _allCustomers.value = repository.getAllCustomers()
            
            val period = _filterPeriod.value
            val method = _filterMethod.value.uppercase()
            val (start, end) = getRange(period)
            
            _allSettlements.value = repository.getFilteredSettlements(start, end, method)
        }
    }

    fun setSortOrder(order: TransactionSortOrder) {
        _sortOrder.value = order
    }

    fun setPeriod(period: String) {
        _filterPeriod.value = period
        loadSettlements()
    }

    fun setMethod(method: String) {
        _filterMethod.value = method
        loadSettlements()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
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

    fun toggleExpansion(customerId: Long) {
        _expandedCustomerIds.update { 
            if (it.contains(customerId)) it - customerId else it + customerId
        }
    }
    
    fun setInitialExpansion(customerId: Long) {
        if (customerId != -1L) {
            _expandedCustomerIds.update { it + customerId }
        }
    }
}

class SettlementHistoryViewModelFactory(private val repository: PrintRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettlementHistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettlementHistoryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
