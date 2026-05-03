package com.habittracker.data.local.model

import androidx.room.ColumnInfo

data class LottoMonthlyStatRow(
    @ColumnInfo(name = "month")
    val month: String,
    @ColumnInfo(name = "purchase_amount")
    val purchaseAmount: Long,
    @ColumnInfo(name = "winning_amount")
    val winningAmount: Long,
)
