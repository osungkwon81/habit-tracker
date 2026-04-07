package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "lotto_ticket",
    indices = [Index(value = ["created_at"])],
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
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun numbers(): List<Int> = listOf(number1, number2, number3, number4, number5, number6)

    companion object {
        fun from(sourceLabel: String, numbers: List<Int>, note: String? = null): LottoTicketEntity {
            require(numbers.size == 6) { "로또 번호는 6개여야 합니다." }
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
            )
        }
    }
}