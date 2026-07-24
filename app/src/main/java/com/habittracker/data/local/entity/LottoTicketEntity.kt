package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "lotto_ticket",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["round_no", "source_label"]),
        Index(
            value = ["round_no", "source_label", "set_no", "recommendation_rank"],
            unique = true,
        ),
    ],
)
data class LottoTicketEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "source_label")
    val sourceLabel: String,
    @ColumnInfo(name = "number1")
    val number1: Int,
    @ColumnInfo(name = "number2")
    val number2: Int,
    @ColumnInfo(name = "number3")
    val number3: Int,
    @ColumnInfo(name = "number4")
    val number4: Int,
    @ColumnInfo(name = "number5")
    val number5: Int,
    @ColumnInfo(name = "number6")
    val number6: Int,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "round_no")
    val roundNo: Int? = null,
    @ColumnInfo(name = "set_no")
    val setNo: Int? = null,
    @ColumnInfo(name = "is_purchased")
    val isPurchased: Boolean = false,
    @ColumnInfo(name = "is_evaluation_target")
    val isEvaluationTarget: Boolean = false,
    @ColumnInfo(name = "purchase_confirmed_at")
    val purchaseConfirmedAt: LocalDateTime? = null,
    @ColumnInfo(name = "generation_version")
    val generationVersion: String = "legacy",
    @ColumnInfo(name = "generation_config_hash")
    val generationConfigHash: String? = null,
    @ColumnInfo(name = "history_through_round")
    val historyThroughRound: Int? = null,
    @ColumnInfo(name = "generation_seed")
    val generationSeed: Long? = null,
    @ColumnInfo(name = "analysis_score")
    val analysisScore: Double? = null,
    @ColumnInfo(name = "data_score")
    val dataScore: Double? = null,
    @ColumnInfo(name = "pattern_score")
    val patternScore: Double? = null,
    @ColumnInfo(name = "distribution_score")
    val distributionScore: Double? = null,
    @ColumnInfo(name = "avoidance_score")
    val avoidanceScore: Double? = null,
    @ColumnInfo(name = "validation_score")
    val validationScore: Double? = null,
    @ColumnInfo(name = "generation_mode")
    val generationMode: String? = null,
    @ColumnInfo(name = "recommendation_rank")
    val recommendationRank: Int? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun numbers(): List<Int> = listOf(number1, number2, number3, number4, number5, number6)

    companion object {
        fun from(
            sourceLabel: String,
            numbers: List<Int>,
            note: String? = null,
            roundNo: Int? = null,
            setNo: Int? = null,
            isEvaluationTarget: Boolean = false,
            generationVersion: String = "manual",
            generationConfigHash: String? = null,
            historyThroughRound: Int? = null,
            generationSeed: Long? = null,
            analysisScore: Double? = null,
            dataScore: Double? = null,
            patternScore: Double? = null,
            distributionScore: Double? = null,
            avoidanceScore: Double? = null,
            validationScore: Double? = null,
            generationMode: String? = null,
            recommendationRank: Int? = null,
        ): LottoTicketEntity {
            require(numbers.size == 6) { "로또 번호는 6개여야 합니다." }
            require(numbers.all { it in 1..45 }) { "로또 번호는 1부터 45 사이여야 합니다." }
            require(numbers.distinct().size == 6) { "로또 번호는 중복될 수 없습니다." }
            require(roundNo == null || roundNo > 0) { "로또 회차는 1 이상이어야 합니다." }
            require(setNo == null || setNo > 0) { "로또 세트 번호는 1 이상이어야 합니다." }
            require(historyThroughRound == null || historyThroughRound > 0) { "분석 기준 회차는 1 이상이어야 합니다." }
            val sortedNumbers = numbers.sorted()
            return LottoTicketEntity(
                sourceLabel = sourceLabel,
                number1 = sortedNumbers[0],
                number2 = sortedNumbers[1],
                number3 = sortedNumbers[2],
                number4 = sortedNumbers[3],
                number5 = sortedNumbers[4],
                number6 = sortedNumbers[5],
                note = note,
                roundNo = roundNo,
                setNo = setNo,
                isEvaluationTarget = isEvaluationTarget,
                generationVersion = generationVersion,
                generationConfigHash = generationConfigHash,
                historyThroughRound = historyThroughRound,
                generationSeed = generationSeed,
                analysisScore = analysisScore,
                dataScore = dataScore,
                patternScore = patternScore,
                distributionScore = distributionScore,
                avoidanceScore = avoidanceScore,
                validationScore = validationScore,
                generationMode = generationMode,
                recommendationRank = recommendationRank,
            )
        }
    }
}
