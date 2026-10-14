package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "exchange_expense")
data class ExchangeExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currency: String,
    @ColumnInfo(name = "foreign_amount") val foreignAmount: String,
    val description: String,
    @ColumnInfo(name = "expense_date") val expenseDate: LocalDate? = null,
)
