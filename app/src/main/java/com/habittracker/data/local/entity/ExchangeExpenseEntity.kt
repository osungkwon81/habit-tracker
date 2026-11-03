package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "exchange_expense")
data class ExchangeExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currency: String,
    @ColumnInfo(name = "foreign_amount") val foreignAmount: String,
    val description: String,
    @ColumnInfo(name = "expense_date") val expenseDate: LocalDate? = null,
    @ColumnInfo(name = "expense_time") val expenseTime: LocalTime? = null,
    @ColumnInfo(name = "payment_method") val paymentMethod: String? = null,
    @ColumnInfo(name = "card_won_amount") val cardWonAmount: String? = null,
)
