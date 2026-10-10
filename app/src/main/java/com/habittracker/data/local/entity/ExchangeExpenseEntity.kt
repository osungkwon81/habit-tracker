package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exchange_expense")
data class ExchangeExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currency: String,
    @ColumnInfo(name = "foreign_amount") val foreignAmount: String,
    val description: String,
)
