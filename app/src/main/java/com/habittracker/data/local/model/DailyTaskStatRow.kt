package com.habittracker.data.local.model

import androidx.room.ColumnInfo
import java.time.LocalDate

data class DailyTaskStatRow(
    @ColumnInfo(name = "task_item_master_id")
    val taskItemMasterId: Long,
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "value_type")
    val valueType: String,
    @ColumnInfo(name = "unit")
    val unit: String?,
    @ColumnInfo(name = "record_date")
    val recordDate: LocalDate,
    @ColumnInfo(name = "total_number")
    val totalNumber: Double?,
    @ColumnInfo(name = "total_duration")
    val totalDuration: Int?,
    @ColumnInfo(name = "completed_count")
    val completedCount: Int,
)
