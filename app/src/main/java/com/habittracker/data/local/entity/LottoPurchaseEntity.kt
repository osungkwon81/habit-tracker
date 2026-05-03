package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "lotto_purchase",
    indices = [Index(value = ["purchase_date"])],
)
data class LottoPurchaseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "purchase_date")
    val purchaseDate: LocalDate,
    @ColumnInfo(name = "lotto_type")
    val lottoType: String,
    @ColumnInfo(name = "amount")
    val amount: Int,
    @ColumnInfo(name = "memo")
    val memo: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
