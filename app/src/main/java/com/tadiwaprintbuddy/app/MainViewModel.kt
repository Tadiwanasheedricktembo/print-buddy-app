package com.tadiwaprintbuddy.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tadiwaprintbuddy.app.data.OrderResult
import com.tadiwaprintbuddy.app.data.HomeOrderDraft
import com.tadiwaprintbuddy.app.data.PrintRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal

data class OrderUiState(
    val customerName: String = "",
    val quantity: Int = 1,
    val quantityInput: String = "1",
    val price: BigDecimal = BigDecimal.ZERO,
    val priceInput: String = "",
    val total: BigDecimal = BigDecimal.ZERO,
    val existingBalance: BigDecimal = BigDecimal.ZERO,
    val creditUsed: BigDecimal = BigDecimal.ZERO,
    val balanceToPay: BigDecimal = BigDecimal.ZERO,
    val receivedAmount: BigDecimal? = null,
    val receivedAmountInput: String? = null,
    val changeAmount: BigDecimal = BigDecimal.ZERO,
    val isLoading: Boolean = false,
    val paymentMethod: String = "CASH",
    val isDraftLoaded: Boolean = false,
    val calculatorItems: List<CartItem> = emptyList(),
    val calculatorUnitPrice: BigDecimal? = null,
    val calculatorTotalPages: Int = 0
)

class MainViewModel(private val repository: PrintRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderUiState())
    val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    private var calculationJob: Job? = null
    private var draftSaveJob: Job? = null
    private val draftMutex = Mutex()
    private var draftEditedBeforeLoad = false
    private val gson = Gson()
    private val cartItemsType = object : TypeToken<List<CartItem>>() {}.type

    init {
        viewModelScope.launch {
            try {
                val draft = repository.getHomeOrderDraft()
                if (!draftEditedBeforeLoad && draft != null) {
                    val restoredItems = runCatching {
                        gson.fromJson<List<CartItem>>(draft.calculatorItemsJson, cartItemsType).orEmpty()
                    }.getOrDefault(emptyList())
                    val restoredPrice = parseAmount(draft.priceInput)
                    val restoredReceived = draft.receivedAmount?.let(::parseAmount)
                    val restoredQuantity = draft.quantityInput.toIntOrNull() ?: 1
                    _uiState.update {
                        it.copy(
                            customerName = draft.customerName,
                            quantity = restoredQuantity,
                            quantityInput = draft.quantityInput,
                            price = restoredPrice,
                            priceInput = draft.priceInput,
                            receivedAmount = restoredReceived,
                            paymentMethod = normalizePaymentMethod(draft.paymentMethod),
                            calculatorItems = restoredItems,
                            calculatorUnitPrice = restoredItems.fold(BigDecimal.ZERO) { total, item -> total.add(item.getSubtotal()) }
                                .takeIf { restoredItems.isNotEmpty() },
                            calculatorTotalPages = draft.calculatorTotalPages.coerceAtLeast(0)
                        )
                    }
                }
                _uiState.update { it.copy(isDraftLoaded = true) }
                calculateTotals()
                if (draftEditedBeforeLoad) scheduleDraftSave()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Unable to load Quick Order draft", e)
                _uiState.update { it.copy(isDraftLoaded = true) }
                _events.emit(MainEvent.ShowError("Unable to restore Quick Order draft"))
            }
        }
    }

    sealed class MainEvent {
        object OrderCompleted : MainEvent()
        data class ShowError(val message: String) : MainEvent()
    }

    fun onCustomerNameChanged(name: String) {
        _uiState.update { it.copy(customerName = name) }
        onDraftInputChanged()
        calculateTotals()
    }

    fun onQuantityChanged(quantity: Int) {
        _uiState.update { it.copy(quantity = quantity, quantityInput = quantity.toString()) }
        onDraftInputChanged()
        calculateTotals()
    }

    fun onQuantityInputChanged(input: String) {
        _uiState.update { it.copy(quantity = input.toIntOrNull() ?: 1, quantityInput = input) }
        onDraftInputChanged()
        calculateTotals()
    }

    fun onPriceChanged(price: BigDecimal) {
        _uiState.update { it.copy(price = price, priceInput = price.toPlainString()) }
        onDraftInputChanged()
        calculateTotals()
    }

    fun onPriceInputChanged(input: String) {
        _uiState.update { it.copy(price = parseAmount(input), priceInput = input) }
        onDraftInputChanged()
        calculateTotals()
    }

    fun onCalculatorOrderSelected(items: List<CartItem>, totalPages: Int) {
        val savedItems = items.map { it.copy() }
        val unitPrice = savedItems.fold(BigDecimal.ZERO) { total, item -> total.add(item.getSubtotal()) }
        _uiState.update {
            it.copy(
                calculatorItems = savedItems,
                calculatorUnitPrice = unitPrice,
                calculatorTotalPages = totalPages.coerceAtLeast(0)
            )
        }
        onDraftInputChanged()
    }

    fun onReceivedAmountChanged(amount: BigDecimal?) {
        _uiState.update { 
            val change = if (amount != null && amount > it.balanceToPay) amount.subtract(it.balanceToPay) else BigDecimal.ZERO
            it.copy(receivedAmount = amount, receivedAmountInput = amount?.toPlainString(), changeAmount = change)
        }
        onDraftInputChanged()
    }

    fun onPaymentMethodChanged(paymentMethod: String) {
        _uiState.update { it.copy(paymentMethod = normalizePaymentMethod(paymentMethod)) }
        onDraftInputChanged()
    }

    fun onPaymentMethodSelected(paymentMethod: String) {
        val method = normalizePaymentMethod(paymentMethod)
        onPaymentMethodChanged(method)
        val current = _uiState.value
        if (current.isLoading) return

        when (method) {
            "CASH" -> completeOrder("Paid", "CASH")
            "UPI" -> completeOrder("Paid", "UPI")
            "CREDIT" -> completeOrder("Credit", "NONE")
        }
    }

    private fun calculateTotals() {
        val current = _uiState.value
        val total = current.price.multiply(BigDecimal(current.quantity))
        
        _uiState.update {
            it.copy(
                total = total
            )
        }

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
                    existingBalance = balance,
                    creditUsed = creditUsed,
                    balanceToPay = toPay
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
        Log.d("PrintBuddyTransaction", "Home Screen Save Initiated: customer=${current.customerName}, price=${current.price}, qty=${current.quantity}, total=${current.total}, method=$paymentMethod, status=$paymentStatus")
        viewModelScope.launch {
            var orderFinalized = false
            try {
                draftSaveJob?.cancelAndJoin()
                val isCalculatorOrder = current.calculatorItems.isNotEmpty() && current.price == current.calculatorUnitPrice
                val cartItems = if (isCalculatorOrder) {
                    current.calculatorItems.map { item ->
                        val quantity = (item.quantity.toLong() * current.quantity).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        item.copy(
                            serviceName = PrintOrderLine.withCopyCount(item.serviceName, current.quantity),
                            quantity = quantity
                        )
                    }
                } else {
                    listOf(CartItem("General", current.price, current.quantity))
                }
                val internalPaymentMethod = if (paymentStatus == "Credit") "OWES_ME" else paymentMethod
                
                val result = draftMutex.withLock {
                    repository.confirmOrder(
                        current.customerName,
                        cartItems,
                        internalPaymentMethod,
                        current.creditUsed,
                        current.receivedAmount,
                        explicitPrintCount = if (isCalculatorOrder) {
                            (current.calculatorTotalPages.toLong() * current.quantity)
                                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        } else null,
                        clearHomeOrderDraft = true
                    )
                }
                Log.d("PrintBuddyTransaction", "Repository confirmOrder result: $result")
                
                when (result) {
                    is OrderResult.Success -> {
                        orderFinalized = true
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
                if (!orderFinalized) scheduleDraftSave()
            }
        }
    }

    suspend fun flushDraft() {
        draftSaveJob?.cancelAndJoin()
        val state = _uiState.value
        if (!state.isDraftLoaded || state.isLoading) return
        persistDraft(state)
    }

    fun resetForm() {
        _uiState.update { 
            OrderUiState(
                customerName = "",
                quantity = 1,
                quantityInput = "1",
                price = BigDecimal.ZERO,
                priceInput = "",
                receivedAmount = null,
                receivedAmountInput = null,
                changeAmount = BigDecimal.ZERO,
                isDraftLoaded = true
            )
        }
    }

    private fun onDraftInputChanged() {
        draftEditedBeforeLoad = true
        scheduleDraftSave()
    }

    private fun scheduleDraftSave() {
        if (!_uiState.value.isDraftLoaded || _uiState.value.isLoading) return
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            val state = _uiState.value
            if (!state.isLoading) persistDraft(state)
        }
    }

    private suspend fun persistDraft(state: OrderUiState) {
        try {
            draftMutex.withLock {
                repository.saveHomeOrderDraft(
                    HomeOrderDraft(
                        customerName = state.customerName,
                        quantityInput = state.quantityInput,
                        priceInput = state.priceInput,
                        receivedAmount = state.receivedAmountInput,
                        paymentMethod = state.paymentMethod,
                        calculatorItemsJson = gson.toJson(state.calculatorItems),
                        calculatorTotalPages = state.calculatorTotalPages.coerceAtLeast(0),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Unable to save Quick Order draft", e)
            _events.emit(MainEvent.ShowError("Unable to save Quick Order draft"))
        }
    }

    private fun normalizePaymentMethod(method: String): String = when (method.trim().uppercase()) {
        "UPI" -> "UPI"
        "CREDIT", "NONE" -> "CREDIT"
        else -> "CASH"
    }

    private fun parseAmount(value: String): BigDecimal =
        try { BigDecimal(value) } catch (e: Exception) { BigDecimal.ZERO }

    companion object {
        private const val DRAFT_SAVE_DEBOUNCE_MS = 300L
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
