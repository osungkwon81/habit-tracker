package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "daily_record",
    indices = [Index(value = ["record_date"], unique = true)],
)
data class DailyRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "record_date")
    val recordDate: LocalDate,
    @ColumnInfo(name = "memo")
    val memo: String?,
    @ColumnInfo(name = "is_holiday")
    val isHoliday: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime,
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime,
)