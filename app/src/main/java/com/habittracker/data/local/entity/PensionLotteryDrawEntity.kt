package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pension_lottery_draw")
data class PensionLotteryDrawEntity(
    @PrimaryKey
    @ColumnInfo(name = "round_no")
    val roundNo: Int,
    @ColumnInfo(name = "group_no")
    val groupNo: Int,
    @ColumnInfo(name = "winning_number")
    val winningNumber: String,
) {
    init {
        require(roundNo > 0) { "연금복권 회차는 1 이상이어야 합니다." }
        require(groupNo in 1..5) { "연금복권 조는 1부터 5 사이여야 합니다." }
        require(winningNumber.length == 6 && winningNumber.all(Char::isDigit)) {
            "연금복권 당첨번호는 6자리 숫자여야 합니다."
        }
    }
}
