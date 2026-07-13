package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "lotto_winning_stat",
    primaryKeys = ["source_label", "generation_version"],
)
data class LottoWinningStatEntity(
    @ColumnInfo(name = "source_label")
    val sourceLabel: String,
    @ColumnInfo(name = "generation_version")
    val generationVersion: String,
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
    @ColumnInfo(name = "evaluated_ticket_count")
    val evaluatedTicketCount: Int,
    @ColumnInfo(name = "style_pass_count")
    val stylePassCount: Int,
    @ColumnInfo(name = "style_score_total")
    val styleScoreTotal: Int,
    @ColumnInfo(name = "scored_ticket_count")
    val scoredTicketCount: Int,
    @ColumnInfo(name = "analysis_score_total")
    val analysisScoreTotal: Double,
    @ColumnInfo(name = "match_count_total")
    val matchCountTotal: Int,
)
