package com.habittracker.data.local.model

import androidx.room.ColumnInfo
import java.time.LocalDate

data class DiarySearchRow(
    @ColumnInfo(name = "diary_date")
    val diaryDate: LocalDate,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "weather")
    val weather: String,
    @ColumnInfo(name = "preview")
    val preview: String,
)