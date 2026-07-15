package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "stock_target_allocation")
data class StockTargetAllocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "product_code")
    val productCode: String,
    @ColumnInfo(name = "product_name")
    val productName: String,
    @ColumnInfo(name = "target_percent")
    val targetPercent: Double,
    val enabled: Boolean = true,
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
