package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.time.LocalDateTime

@Entity(tableName = "exchange_rate", primaryKeys = ["currency", "quoted_at", "quote_round"])
data class ExchangeRateEntity(
    val currency: String,
    @ColumnInfo(name = "quoted_at") val quotedAt: LocalDateTime,
    @ColumnInfo(name = "quote_round") val quoteRound: Int,
    val rate: String,
)
