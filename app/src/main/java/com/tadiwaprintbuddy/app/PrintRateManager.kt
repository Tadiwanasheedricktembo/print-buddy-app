package com.tadiwaprintbuddy.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PrintRateManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("print_rates", Context.MODE_PRIVATE)

    companion object {
        const val KEY_BW_RATE = "bw_rate"
        const val KEY_COLOR_RATE = "color_rate"
        
        const val DEFAULT_BW_RATE = 5.0
        const val DEFAULT_COLOR_RATE = 10.0
    }

    var bwRate: Double
        get() = prefs.getFloat(KEY_BW_RATE, DEFAULT_BW_RATE.toFloat()).toDouble()
        set(value) = prefs.edit { putFloat(KEY_BW_RATE, value.toFloat()) }

    var colorRate: Double
        get() = prefs.getFloat(KEY_COLOR_RATE, DEFAULT_COLOR_RATE.toFloat()).toDouble()
        set(value) = prefs.edit { putFloat(KEY_COLOR_RATE, value.toFloat()) }
}
