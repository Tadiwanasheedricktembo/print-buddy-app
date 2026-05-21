package com.tadiwaprintbuddy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.tadiwaprintbuddy.app.data.BeautyTransaction
import com.tadiwaprintbuddy.app.data.PrintRepository
import kotlinx.coroutines.launch

class BeautyAccountViewModel(private val repository: PrintRepository) : ViewModel() {

    val balance = repository.getBeautyBalanceFlow().asLiveData()
    val transactions = repository.getAllBeautyTransactionsFlow().asLiveData()

    fun addMoney(amount: Double, note: String?) {
        viewModelScope.launch {
            repository.insertBeautyTransaction(amount, "ADD", note)
        }
    }

    fun returnMoney(amount: Double, note: String?) {
        viewModelScope.launch {
            repository.insertBeautyTransaction(amount, "RETURN", note)
        }
    }

    fun resetBalance() {
        viewModelScope.launch {
            val currentBalance = repository.getCurrentBeautyBalance()
            if (currentBalance != 0.0) {
                repository.insertBeautyTransaction(-currentBalance, "RESET", "Balance reset to zero")
            }
        }
    }

    fun deleteTransaction(transaction: BeautyTransaction) {
        viewModelScope.launch {
            repository.deleteBeautyTransaction(transaction)
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
