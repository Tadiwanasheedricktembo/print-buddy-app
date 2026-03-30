package com.tadiwaprintbuddy.app.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "printer_references")
data class PrinterReference(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val notes: String?,
    val imagePath: String,
    val timestamp: Long
) : Parcelable
