package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lotto_winning_stat")
data class LottoWinningStatEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_label")
    val sourceLabel: String,
    @ColumnInfo(name = "rank5_count")
    val rank5Count: Int,
    @ColumnInfo(name = "rank4_count")
    val rank4Count: Int,
    @ColumnInfo(name = "rank3_count")
    val rank3Count: Int,
    @ColumnInfo(name = "rank2_count")
    val rank2Count: Int,
    @ColumnInfo(name = "rank1_count")
    val rank1Count: Int,
)
