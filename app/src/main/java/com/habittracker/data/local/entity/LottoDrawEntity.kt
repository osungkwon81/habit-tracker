package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "lotto_draw")
data class LottoDrawEntity(
    @PrimaryKey
    @ColumnInfo(name = "round_no")
    val roundNo: Int,
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
    @ColumnInfo(name = "bonus_number")
    val bonusNumber: Int? = null,
    @ColumnInfo(name = "rank1_prize_amount")
    val rank1PrizeAmount: Long? = null,
    @ColumnInfo(name = "rank2_prize_amount")
    val rank2PrizeAmount: Long? = null,
    @ColumnInfo(name = "rank3_prize_amount")
    val rank3PrizeAmount: Long? = null,
    @ColumnInfo(name = "rank4_prize_amount")
    val rank4PrizeAmount: Long? = null,
    @ColumnInfo(name = "rank5_prize_amount")
    val rank5PrizeAmount: Long? = null,
    @ColumnInfo(name = "data_source", defaultValue = "'LEGACY'")
    val dataSource: String = "MANUAL",
    @ColumnInfo(name = "source_reference")
    val sourceReference: String? = null,
    @ColumnInfo(name = "source_content_hash")
    val sourceContentHash: String? = null,
    @ColumnInfo(name = "saved_at")
    val savedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun numbers(): List<Int> = listOf(number1, number2, number3, number4, number5, number6)

    fun prizeAmount(rank: Int): Long? = when (rank) {
        1 -> rank1PrizeAmount
        2 -> rank2PrizeAmount
        3 -> rank3PrizeAmount
        4 -> rank4PrizeAmount
        5 -> rank5PrizeAmount
        else -> null
    }

    companion object {
        fun from(
            roundNo: Int,
            numbers: List<Int>,
            bonusNumber: Int? = null,
            prizeAmounts: Map<Int, Long> = emptyMap(),
            dataSource: String = "MANUAL",
            sourceReference: String? = null,
            sourceContentHash: String? = null,
        ): LottoDrawEntity {
            require(roundNo > 0) { "로또 회차는 1 이상이어야 합니다." }
            require(numbers.size == 6) { "로또 번호는 6개여야 합니다." }
            require(numbers.all { it in 1..45 }) { "로또 번호는 1부터 45 사이여야 합니다." }
            require(numbers.distinct().size == 6) { "로또 번호는 중복될 수 없습니다." }
            require(bonusNumber == null || bonusNumber in 1..45) { "보너스 번호는 1부터 45 사이여야 합니다." }
            require(bonusNumber == null || bonusNumber !in numbers) { "보너스 번호는 당첨 번호와 중복될 수 없습니다." }
            require(dataSource.isNotBlank()) { "추첨 데이터 출처가 필요합니다." }
            require(prizeAmounts.keys.all { it in 1..5 } && prizeAmounts.values.all { it > 0L }) {
                "등수별 당첨금 값이 올바르지 않습니다."
            }
            val sanitizedSourceReference = sourceReference?.trim()?.takeIf(String::isNotEmpty)
            val sanitizedSourceContentHash = sourceContentHash?.trim()?.takeIf(String::isNotEmpty)
            require((sanitizedSourceReference == null) == (sanitizedSourceContentHash == null)) {
                "원본 출처와 해시값은 함께 저장해야 합니다."
            }
            val sortedNumbers = numbers.sorted()
            return LottoDrawEntity(
                roundNo = roundNo,
                number1 = sortedNumbers[0],
                number2 = sortedNumbers[1],
                number3 = sortedNumbers[2],
                number4 = sortedNumbers[3],
                number5 = sortedNumbers[4],
                number6 = sortedNumbers[5],
                bonusNumber = bonusNumber,
                rank1PrizeAmount = prizeAmounts[1],
                rank2PrizeAmount = prizeAmounts[2],
                rank3PrizeAmount = prizeAmounts[3],
                rank4PrizeAmount = prizeAmounts[4],
                rank5PrizeAmount = prizeAmounts[5],
                dataSource = dataSource,
                sourceReference = sanitizedSourceReference,
                sourceContentHash = sanitizedSourceContentHash,
            )
        }
    }
}
