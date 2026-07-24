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
    @ColumnInfo(name = "data_source", defaultValue = "'LEGACY'")
    val dataSource: String = "MANUAL",
    @ColumnInfo(name = "saved_at")
    val savedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun numbers(): List<Int> = listOf(number1, number2, number3, number4, number5, number6)

    companion object {
        fun from(
            roundNo: Int,
            numbers: List<Int>,
            bonusNumber: Int? = null,
            dataSource: String = "MANUAL",
        ): LottoDrawEntity {
            require(roundNo > 0) { "로또 회차는 1 이상이어야 합니다." }
            require(numbers.size == 6) { "로또 번호는 6개여야 합니다." }
            require(numbers.all { it in 1..45 }) { "로또 번호는 1부터 45 사이여야 합니다." }
            require(numbers.distinct().size == 6) { "로또 번호는 중복될 수 없습니다." }
            require(bonusNumber == null || bonusNumber in 1..45) { "보너스 번호는 1부터 45 사이여야 합니다." }
            require(bonusNumber == null || bonusNumber !in numbers) { "보너스 번호는 당첨 번호와 중복될 수 없습니다." }
            require(dataSource.isNotBlank()) { "추첨 데이터 출처가 필요합니다." }
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
                dataSource = dataSource,
            )
        }
    }
}
