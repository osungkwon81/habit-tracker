package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "stock_automation_event",
    indices = [Index(value = ["created_at"])],
)
data class StockAutomationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val level: String,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "product_code")
    val productCode: String? = null,
    val message: String,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
