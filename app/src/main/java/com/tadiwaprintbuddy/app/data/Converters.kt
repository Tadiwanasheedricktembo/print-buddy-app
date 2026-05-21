package com.tadiwaprintbuddy.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromExpenseCategory(value: ExpenseCategory): String {
        return value.name
    }

    @TypeConverter
    fun toExpenseCategory(value: String): ExpenseCategory {
        return ExpenseCategory.valueOf(value)
    }
}
