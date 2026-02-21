package com.tadiwaprintbuddy.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.liveData
import com.tadiwaprintbuddy.app.data.Order
import com.tadiwaprintbuddy.app.data.OrderItem
import com.tadiwaprintbuddy.app.data.PrintDao

class OrderDetailsViewModel(private val orderId: Int, private val printDao: PrintDao) : ViewModel() {

    val order: LiveData<Order> = liveData {
        emit(printDao.getAllOrders().first { it.id == orderId })
    }

    val orderItems: LiveData<List<OrderItem>> = liveData {
        emit(printDao.getItemsForOrder(orderId))
    }
}

class OrderDetailsViewModelFactory(private val orderId: Int, private val printDao: PrintDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OrderDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OrderDetailsViewModel(orderId, printDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
