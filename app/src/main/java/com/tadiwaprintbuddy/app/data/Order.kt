package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val totalAmount: Double,
    val date: Long
)
