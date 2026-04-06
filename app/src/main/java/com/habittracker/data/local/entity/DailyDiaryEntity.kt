package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "daily_diary",
    indices = [Index(value = ["diary_date"], unique = true)],
)
data class DailyDiaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "diary_date")
    val diaryDate: LocalDate,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "weather")
    val weather: String,
    @ColumnInfo(name = "image_uris")
    val imageUris: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime,
)