package com.habittracker.data.local.model

import androidx.room.ColumnInfo

data class LottoPeriodStatRow(
    @ColumnInfo(name = "period")
    val period: String,
    @ColumnInfo(name = "purchase_amount")
    val purchaseAmount: Long,
    @ColumnInfo(name = "winning_amount")
    val winningAmount: Long,
)
