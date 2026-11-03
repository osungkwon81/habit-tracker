package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "exchange_record")
data class ExchangeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currency: String,
    @ColumnInfo(name = "won_amount") val wonAmount: String,
    @ColumnInfo(name = "applied_rate") val appliedRate: String,
    @ColumnInfo(name = "exchange_date") val exchangeDate: LocalDate? = null,
    @ColumnInfo(name = "exchange_time") val exchangeTime: LocalTime? = null,
)
