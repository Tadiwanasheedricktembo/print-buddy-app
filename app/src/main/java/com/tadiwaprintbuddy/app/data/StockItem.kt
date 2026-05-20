package com.tadiwaprintbuddy.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stock_items")
data class StockItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val currentQuantity: Int,
    val lowStockThreshold: Int = 10,
    val unit: String = "pcs" // e.g., sheets, ml, units
)
