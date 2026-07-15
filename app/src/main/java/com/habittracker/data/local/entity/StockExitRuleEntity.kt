package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "stock_exit_rule",
    indices = [Index(value = ["product_code", "enabled"])],
)
data class StockExitRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "product_code")
    val productCode: String,
    @ColumnInfo(name = "product_name")
    val productName: String,
    @ColumnInfo(name = "rule_type")
    val ruleType: String,
    @ColumnInfo(name = "trigger_value")
    val triggerValue: Double,
    @ColumnInfo(name = "sell_quantity_percent")
    val sellQuantityPercent: Double,
    @ColumnInfo(name = "action_mode")
    val actionMode: String,
    @ColumnInfo(name = "order_division_code")
    val orderDivisionCode: String,
    @ColumnInfo(name = "reference_high_price")
    val referenceHighPrice: Long? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "last_triggered_at")
    val lastTriggeredAt: LocalDateTime? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
