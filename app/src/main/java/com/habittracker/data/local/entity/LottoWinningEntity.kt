package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "lotto_winning",
    indices = [Index(value = ["round_no"])],
)
data class LottoWinningEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "round_no")
    val roundNo: Int,
    @ColumnInfo(name = "lotto_type", defaultValue = "'로또'")
    val lottoType: String = "로또",
    @ColumnInfo(name = "amount")
    val amount: Long,
    @ColumnInfo(name = "source_label")
    val sourceLabel: String = "기타",
    @ColumnInfo(name = "memo")
    val memo: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
