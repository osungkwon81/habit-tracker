package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "pension_lottery_generated_number",
    indices = [
        Index(value = ["generation_id"]),
        Index(value = ["generation_id", "generation_type"], unique = true),
    ],
)
data class PensionLotteryGeneratedNumberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "generation_id")
    val generationId: String,
    @ColumnInfo(name = "generation_type")
    val generationType: String,
    @ColumnInfo(name = "group_no")
    val groupNo: Int,
    @ColumnInfo(name = "winning_number")
    val winningNumber: String,
    @ColumnInfo(name = "digit_scores")
    val digitScores: String,
    @ColumnInfo(name = "total_score")
    val totalScore: Int,
    @ColumnInfo(name = "score_band")
    val scoreBand: String,
    @ColumnInfo(name = "duplicate_label")
    val duplicateLabel: String,
    @ColumnInfo(name = "cold_positions")
    val coldPositions: String,
    @ColumnInfo(name = "cold_priority_scores")
    val coldPriorityScores: String,
    @ColumnInfo(name = "generated_at")
    val generatedAt: LocalDateTime,
    @ColumnInfo(name = "saved_at")
    val savedAt: LocalDateTime? = null,
    @ColumnInfo(name = "target_round_no")
    val targetRoundNo: Int? = null,
    @ColumnInfo(name = "analysis_through_round")
    val analysisThroughRound: Int? = null,
    @ColumnInfo(name = "generation_version", defaultValue = "'legacy'")
    val generationVersion: String = "legacy",
    @ColumnInfo(name = "generation_config_hash")
    val generationConfigHash: String? = null,
    @ColumnInfo(name = "input_data_hash")
    val inputDataHash: String? = null,
    @ColumnInfo(name = "generation_seed")
    val generationSeed: Long? = null,
    @ColumnInfo(name = "is_control", defaultValue = "0")
    val isControl: Boolean = false,
    @ColumnInfo(name = "is_evaluation_target", defaultValue = "0")
    val isEvaluationTarget: Boolean = false,
    @ColumnInfo(name = "is_hidden", defaultValue = "0")
    val isHidden: Boolean = false,
    @ColumnInfo(name = "evaluated_at")
    val evaluatedAt: LocalDateTime? = null,
    @ColumnInfo(name = "matched_suffix_length")
    val matchedSuffixLength: Int? = null,
    @ColumnInfo(name = "position_match_count")
    val positionMatchCount: Int? = null,
    @ColumnInfo(name = "is_bonus_match")
    val isBonusMatch: Boolean? = null,
) {
    init {
        require(generationId.isNotBlank()) { "연금번호 생성 배치 ID가 필요합니다." }
        require(groupNo in 1..5) { "연금번호 조는 1부터 5 사이여야 합니다." }
        require(winningNumber.length == 6 && winningNumber.all(Char::isDigit)) {
            "생성된 연금번호는 6자리 숫자여야 합니다."
        }
        require(targetRoundNo == null || targetRoundNo > 0) { "평가 대상 회차는 1 이상이어야 합니다." }
        require(analysisThroughRound == null || analysisThroughRound > 0) { "분석 기준 회차는 1 이상이어야 합니다." }
    }
}
