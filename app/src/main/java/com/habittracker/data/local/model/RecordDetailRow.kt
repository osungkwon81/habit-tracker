package com.habittracker.data.local.model

import androidx.room.ColumnInfo
import com.habittracker.data.local.ValueType

data class RecordDetailRow(
    @ColumnInfo(name = "item_id")
    val itemId: Long,
    @ColumnInfo(name = "record_id")
    val recordId: Long,
    @ColumnInfo(name = "task_item_master_id")
    val taskItemMasterId: Long,
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "value_type")
    val valueType: ValueType,
    @ColumnInfo(name = "unit")
    val unit: String?,
    @ColumnInfo(name = "number_value")
    val numberValue: Double?,
    @ColumnInfo(name = "boolean_value")
    val booleanValue: Boolean?,
    @ColumnInfo(name = "text_value")
    val textValue: String?,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int?,
    @ColumnInfo(name = "checked")
    val checked: Boolean,
    @ColumnInfo(name = "note")
    val note: String?,
)
