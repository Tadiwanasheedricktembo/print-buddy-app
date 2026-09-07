package com.tadiwaprintbuddy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tadiwaprintbuddy.app.data.OrderResult
import com.tadiwaprintbuddy.app.data.PrintRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class OrderUiState(
    val customerName: String = "",
    val quantity: Int = 1,
    val price: BigDecimal = BigDecimal.ZERO,
    val total: BigDecimal = BigDecimal.ZERO,
    val existingBalance: BigDecimal = BigDecimal.ZERO,
    val creditUsed: BigDecimal = BigDecimal.ZERO,
    val balanceToPay: BigDecimal = BigDecimal.ZERO,
    val receivedAmount: BigDecimal? = null,
    val changeAmount: BigDecimal = BigDecimal.ZERO,
    val isCompleteEnabled: Boolean = false,
    val isLoading: Boolean = false
)

class MainViewModel(private val repository: PrintRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderUiState())
    val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    private var calculationJob: Job? = null

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

    fun onPriceChanged(price: BigDecimal) {
        _uiState.update { it.copy(price = price) }
        calculateTotals()
    }

    fun onReceivedAmountChanged(amount: BigDecimal?) {
        _uiState.update { 
            val change = if (amount != null && amount > it.balanceToPay) amount.subtract(it.balanceToPay) else BigDecimal.ZERO
            it.copy(receivedAmount = amount, changeAmount = change) 
        }
    }

    private fun calculateTotals() {
        val current = _uiState.value
        val total = current.price.multiply(BigDecimal(current.quantity))
        
        calculationJob?.cancel()
        calculationJob = viewModelScope.launch {
            val balance = if (current.customerName.isNotBlank()) {
                repository.getCustomerBalance(current.customerName)
            } else BigDecimal.ZERO

            val creditAvailable = if (balance < BigDecimal.ZERO) balance.negate() else BigDecimal.ZERO
            val creditUsed = if (total > BigDecimal.ZERO) total.min(creditAvailable) else BigDecimal.ZERO
            val toPay = total.subtract(creditUsed).max(BigDecimal.ZERO)

            _uiState.update {
                it.copy(
                    total = total,
                    existingBalance = balance,
                    creditUsed = creditUsed,
                    balanceToPay = toPay,
                    isCompleteEnabled = it.quantity > 0 && it.price > BigDecimal.ZERO
                )
            }
        }
    }

    fun completeOrder(paymentStatus: String, paymentMethod: String) {
        val current = _uiState.value
        if (current.isLoading) return
        
        // --- Authority Validation ---
        if (current.total <= BigDecimal.ZERO) {
            viewModelScope.launch { _events.emit(MainEvent.ShowError("Enter a valid amount greater than ₹0")) }
            return
        }
        
        if (current.quantity <= 0) {
            viewModelScope.launch { _events.emit(MainEvent.ShowError("Quantity must be greater than zero")) }
            return
        }

        if (paymentStatus == "Credit" && current.customerName.isBlank()) {
            viewModelScope.launch { _events.emit(MainEvent.ShowError("Customer name is required for Credit orders")) }
            return
        }
        
        // Prevent UPI status change without confirmed payment
        if (paymentMethod == "UPI" && paymentStatus != "Paid") {
            return
        }

        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val cartItems = listOf(CartItem("General", current.price, current.quantity))
                val internalPaymentMethod = if (paymentStatus == "Credit") "OWES_ME" else paymentMethod
                
                val result = repository.confirmOrder(
                    current.customerName,
                    cartItems,
                    internalPaymentMethod,
                    current.creditUsed,
                    current.receivedAmount
                )
                
                when (result) {
                    is OrderResult.Success -> {
                        resetForm()
                        _events.emit(MainEvent.OrderCompleted)
                    }
                    is OrderResult.InsufficientStock -> {
                        _events.emit(MainEvent.ShowError("Insufficient stock for ${result.itemName}. Available: ${result.available}"))
                    }
                    is OrderResult.ValidationError -> {
                        _events.emit(MainEvent.ShowError(result.message))
                    }
                    is OrderResult.Error -> {
                        _events.emit(MainEvent.ShowError(result.message))
                    }
                }
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
                price = BigDecimal.ZERO,
                receivedAmount = null,
                changeAmount = BigDecimal.ZERO
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
