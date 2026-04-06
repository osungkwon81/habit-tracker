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
    @ColumnInfo(name = "saved_at")
    val savedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun numbers(): List<Int> = listOf(number1, number2, number3, number4, number5, number6)

    companion object {
        fun from(roundNo: Int, numbers: List<Int>): LottoDrawEntity {
            require(numbers.size == 6) { "로또 번호는 6개여야 합니다." }
            return LottoDrawEntity(
                roundNo = roundNo,
                number1 = numbers[0],
                number2 = numbers[1],
                number3 = numbers[2],
                number4 = numbers[3],
                number5 = numbers[4],
                number6 = numbers[5],
            )
        }
    }
}
