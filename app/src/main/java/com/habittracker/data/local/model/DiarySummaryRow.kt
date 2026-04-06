package com.habittracker.data.local.model

import androidx.room.ColumnInfo
import java.time.LocalDate

data class DiarySummaryRow(
    @ColumnInfo(name = "diary_date")
    val diaryDate: LocalDate,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "weather")
    val weather: String,
)