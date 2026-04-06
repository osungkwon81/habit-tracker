package com.habittracker.data.local.model

import androidx.room.ColumnInfo

data class MonthlyStatRow(
    @ColumnInfo(name = "task_name")
    val taskName: String,
    @ColumnInfo(name = "value_type")
    val valueType: String,
    @ColumnInfo(name = "total_number")
    val totalNumber: Double?,
    @ColumnInfo(name = "completed_count")
    val completedCount: Int,
)
