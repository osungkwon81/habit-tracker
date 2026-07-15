package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "stock_order",
    indices = [
        Index(value = ["order_date", "order_number"], unique = true),
        Index(value = ["product_code"]),
        Index(value = ["status"]),
    ],
)
data class StockOrderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "order_number")
    val orderNumber: String,
    @ColumnInfo(name = "order_date")
    val orderDate: LocalDate,
    @ColumnInfo(name = "order_time")
    val orderTime: String,
    val side: String,
    @ColumnInfo(name = "product_code")
    val productCode: String,
    @ColumnInfo(name = "product_name")
    val productName: String,
    @ColumnInfo(name = "requested_quantity")
    val requestedQuantity: Long,
    @ColumnInfo(name = "requested_unit_price")
    val requestedUnitPrice: Long,
    @ColumnInfo(name = "reference_price")
    val referencePrice: Long,
    @ColumnInfo(name = "order_division_code")
    val orderDivisionCode: String,
    @ColumnInfo(name = "exchange_code")
    val exchangeCode: String,
    val source: String,
    val status: String,
    @ColumnInfo(name = "filled_quantity")
    val filledQuantity: Long = 0,
    @ColumnInfo(name = "applied_filled_quantity")
    val appliedFilledQuantity: Long = 0,
    @ColumnInfo(name = "filled_average_price")
    val filledAveragePrice: Long? = null,
    @ColumnInfo(name = "remaining_quantity")
    val remainingQuantity: Long = 0,
    @ColumnInfo(name = "estimated_realized_profit")
    val estimatedRealizedProfit: Long? = null,
    val message: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
