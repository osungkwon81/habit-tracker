package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "stock_safety_config")
data class StockSafetyConfigEntity(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "monitoring_enabled")
    val monitoringEnabled: Boolean = false,
    @ColumnInfo(name = "automatic_order_enabled")
    val automaticOrderEnabled: Boolean = false,
    @ColumnInfo(name = "global_order_blocked")
    val globalOrderBlocked: Boolean = false,
    @ColumnInfo(name = "block_reason")
    val blockReason: String? = null,
    @ColumnInfo(name = "crash_guard_enabled")
    val crashGuardEnabled: Boolean = false,
    @ColumnInfo(name = "crash_benchmark_code")
    val crashBenchmarkCode: String? = null,
    @ColumnInfo(name = "crash_threshold_percent")
    val crashThresholdPercent: Double? = null,
    @ColumnInfo(name = "monitor_interval_minutes")
    val monitorIntervalMinutes: Int? = null,
    @ColumnInfo(name = "max_order_amount")
    val maxOrderAmount: Long? = null,
    @ColumnInfo(name = "daily_buy_limit")
    val dailyBuyLimit: Long? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
