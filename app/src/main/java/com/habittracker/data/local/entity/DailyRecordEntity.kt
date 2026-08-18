package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

/** 하루에 하나만 존재하는 기록의 머리글이며, 세부 습관 값은 DailyRecordItemEntity에 저장된다. */
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
