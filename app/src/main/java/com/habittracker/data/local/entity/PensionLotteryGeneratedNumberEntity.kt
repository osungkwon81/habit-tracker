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
) {
    init {
        require(generationId.isNotBlank()) { "연금번호 생성 배치 ID가 필요합니다." }
        require(groupNo in 1..5) { "연금번호 조는 1부터 5 사이여야 합니다." }
        require(winningNumber.length == 6 && winningNumber.all(Char::isDigit)) {
            "생성된 연금번호는 6자리 숫자여야 합니다."
        }
    }
}
