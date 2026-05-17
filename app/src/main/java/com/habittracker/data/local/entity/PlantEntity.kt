package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "plant",
    indices = [Index(value = ["next_watering_date"])],
)
data class PlantEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "image_uri")
    val imageUri: String? = null,
    @ColumnInfo(name = "memo")
    val memo: String? = null,
    @ColumnInfo(name = "watering_interval_days")
    val wateringIntervalDays: Int,
    @ColumnInfo(name = "last_watered_date")
    val lastWateredDate: LocalDate,
    @ColumnInfo(name = "next_watering_date")
    val nextWateringDate: LocalDate,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
