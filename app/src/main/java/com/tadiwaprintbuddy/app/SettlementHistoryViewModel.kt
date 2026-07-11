package com.tadiwaprintbuddy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.data.SettlementHistory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class TransactionSortOrder {
    OLDEST_FIRST,
    NEWEST_FIRST
}

data class GroupedSettlement(
    val customerId: Long,
    val customerName: String,
    val totalOwed: Double,
    val transactions: List<SettlementHistory>,
    var isExpanded: Boolean = false
)

class SettlementHistoryViewModel(private val repository: PrintRepository) : ViewModel() {

    private val _sortOrder = MutableStateFlow(TransactionSortOrder.NEWEST_FIRST)
    val sortOrder: StateFlow<TransactionSortOrder> = _sortOrder.asStateFlow()

    private val _allSettlements = MutableStateFlow<List<SettlementHistory>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _expandedCustomerIds = MutableStateFlow<Set<Long>>(emptySet())
    
    val groupedSettlements: StateFlow<List<GroupedSettlement>> = combine(
        _allSettlements, 
        _sortOrder, 
        _searchQuery, 
        _expandedCustomerIds
    ) { settlements, order, query, expandedIds ->
        groupAndSort(settlements, order, query, expandedIds)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun groupAndSort(
        settlements: List<SettlementHistory>, 
        order: TransactionSortOrder, 
        query: String,
        expandedIds: Set<Long>
    ): List<GroupedSettlement> {
        return settlements.groupBy { it.customerId }
            .map { (id, trans) ->
                val sortedTrans = when (order) {
                    TransactionSortOrder.NEWEST_FIRST -> trans.sortedWith(
                        compareByDescending<SettlementHistory> { it.timestamp }.thenByDescending { it.id }
                    )
                    TransactionSortOrder.OLDEST_FIRST -> trans.sortedWith(
                        compareBy<SettlementHistory> { it.timestamp }.thenBy { it.id }
                    )
                }
                val name = sortedTrans.firstOrNull { it.customerName.isNotBlank() }?.customerName ?: "Unknown"
                
                // Authoritative balance derived from the most recent transaction (by time then ID)
                val mostRecent = trans.maxWithOrNull(compareBy<SettlementHistory> { it.timestamp }.thenBy { it.id })
                val balance = mostRecent?.let { 
                    if (it.newBalance != 0.0 || it.transactionAmount != 0.0) it.newBalance else it.balanceAfter 
                } ?: 0.0

                GroupedSettlement(
                    customerId = id,
                    customerName = name,
                    totalOwed = balance,
                    transactions = sortedTrans,
                    isExpanded = expandedIds.contains(id)
                )
            }
            .filter { it.customerName.contains(query, ignoreCase = true) }
            .sortedBy { it.customerName }
    }

    fun loadSettlements() {
        viewModelScope.launch {
            _allSettlements.value = repository.getAllSettlements()
        }
    }

    fun setSortOrder(order: TransactionSortOrder) {
        _sortOrder.value = order
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
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
