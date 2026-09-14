package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "notification_assistant_item",
    indices = [Index(value = ["source_key"], unique = true)],
)
data class NotificationAssistantItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "event_at") val eventAt: LocalDateTime?,
    @ColumnInfo(name = "received_at") val receivedAt: LocalDateTime,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "amount") val amount: Long? = null,
    @ColumnInfo(name = "merchant") val merchant: String? = null,
    @ColumnInfo(name = "product_name") val productName: String? = null,
    @ColumnInfo(name = "reference_id") val referenceId: String? = null,
    @ColumnInfo(name = "delivery_state") val deliveryState: String? = null,
)
