package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exchange_record")
data class ExchangeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currency: String,
    @ColumnInfo(name = "won_amount") val wonAmount: String,
    @ColumnInfo(name = "applied_rate") val appliedRate: String,
)
