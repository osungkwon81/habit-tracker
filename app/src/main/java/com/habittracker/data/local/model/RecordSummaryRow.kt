package com.habittracker.data.local.model

import androidx.room.ColumnInfo
import java.time.LocalDate

data class RecordSummaryRow(
    @ColumnInfo(name = "record_date")
    val recordDate: LocalDate,
    @ColumnInfo(name = "item_count")
    val itemCount: Int,
    @ColumnInfo(name = "completed_count")
    val completedCount: Int,
)
