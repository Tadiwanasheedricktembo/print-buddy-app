package com.tadiwaprintbuddy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tadiwaprintbuddy.app.data.PrintRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OrderUiState(
    val customerName: String = "",
    val quantity: Int = 1,
    val price: Double = 0.0,
    val total: Double = 0.0,
    val existingBalance: Double = 0.0,
    val creditUsed: Double = 0.0,
    val balanceToPay: Double = 0.0,
    val isCompleteEnabled: Boolean = false,
    val isLoading: Boolean = false
)

class MainViewModel(private val repository: PrintRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderUiState())
    val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    sealed class MainEvent {
        object OrderCompleted : MainEvent()
        data class ShowError(val message: String) : MainEvent()
    }

    fun onCustomerNameChanged(name: String) {
        _uiState.update { it.copy(customerName = name) }
        calculateTotals()
    }

    fun onQuantityChanged(quantity: Int) {
        _uiState.update { it.copy(quantity = quantity) }
        calculateTotals()
    }

    fun onPriceChanged(price: Double) {
        _uiState.update { it.copy(price = price) }
        calculateTotals()
    }

    private fun calculateTotals() {
        val current = _uiState.value
        val total = current.quantity * current.price
        
        viewModelScope.launch {
            val balance = if (current.customerName.isNotBlank()) {
                repository.getCustomerBalance(current.customerName)
            } else 0.0

            val creditAvailable = if (balance < 0) abs(balance) else 0.0
            val creditUsed = if (total > 0) min(total, creditAvailable) else 0.0
            val toPay = max(0.0, total - creditUsed)

            _uiState.update {
                it.copy(
                    total = total,
                    existingBalance = balance,
                    creditUsed = creditUsed,
                    balanceToPay = toPay,
                    isCompleteEnabled = current.quantity > 0 && current.price > 0
                )
            }
        }
    }

    fun completeOrder(paymentStatus: String, paymentMethod: String) {
        val current = _uiState.value
        if (paymentStatus == "Credit" && current.customerName.isBlank()) {
            viewModelScope.launch { _events.emit(MainEvent.ShowError("Customer name is required for Credit orders")) }
            return
        }

        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val cartItems = listOf(CartItem("General", current.price, current.quantity))
                val internalPaymentMethod = if (paymentStatus == "Credit") "OWES_ME" else paymentMethod
                
                repository.confirmOrder(
                    current.customerName,
                    cartItems,
                    internalPaymentMethod,
                    current.creditUsed
                )
                
                resetForm()
                _events.emit(MainEvent.OrderCompleted)
            } catch (e: Exception) {
                _events.emit(MainEvent.ShowError("Failed to save order: ${e.message}"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun resetForm() {
        _uiState.update { 
            OrderUiState(
                customerName = "",
                quantity = 1,
                price = 0.0
            )
        }
    }
}

class MainViewModelFactory(private val repository: PrintRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
