package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "stock_sell_allocation",
    indices = [
        Index(value = ["sell_order_id"]),
        Index(value = ["buy_order_id"]),
    ],
)
data class StockSellAllocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "sell_order_id")
    val sellOrderId: Long,
    @ColumnInfo(name = "buy_order_id")
    val buyOrderId: Long,
    val quantity: Long,
    @ColumnInfo(name = "buy_unit_price")
    val buyUnitPrice: Long,
    @ColumnInfo(name = "sell_unit_price")
    val sellUnitPrice: Long,
    @ColumnInfo(name = "realized_profit")
    val realizedProfit: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
