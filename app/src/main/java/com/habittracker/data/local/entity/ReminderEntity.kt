package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "reminder")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    @ColumnInfo(name = "remind_at") val remindAt: LocalDateTime,
    @ColumnInfo(name = "repeat_interval_minutes") val repeatIntervalMinutes: Int,
    @ColumnInfo(name = "completed_at") val completedAt: LocalDateTime? = null,
    @ColumnInfo(name = "created_at") val createdAt: LocalDateTime,
)
