package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "lotto_winning_stat_round",
    primaryKeys = ["round_no", "source_label"],
)
data class LottoWinningStatRoundEntity(
    @ColumnInfo(name = "round_no")
    val roundNo: Int,
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
