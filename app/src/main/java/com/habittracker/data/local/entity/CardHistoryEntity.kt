package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "card_history",
    indices = [Index(value = ["use_date"])],
)
data class CardHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "use_date")
    val useDate: LocalDate,
    @ColumnInfo(name = "amount")
    val amount: Long,
    @ColumnInfo(name = "memo")
    val memo: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
