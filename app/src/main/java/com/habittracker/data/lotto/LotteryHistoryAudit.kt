package com.habittracker.data.lotto

import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class LotteryHistoryAudit(
    val count: Int,
    val firstRound: Int?,
    val lastRound: Int?,
    val expectedLastRound: Int,
    val missingRounds: List<Int>,
    val missingBeforeStart: Int,
    val duplicateRounds: List<Int>,
    val invalidRounds: List<Int>,
    val missingBonusCount: Int,
    val missingSourceHashCount: Int,
) {
    val canResearch: Boolean get() = count > 0 && missingRounds.isEmpty() &&
        duplicateRounds.isEmpty() && invalidRounds.isEmpty()
}

internal object LotteryHistoryAuditor {
    fun lotto(draws: List<LottoDrawEntity>, now: LocalDateTime = lotteryEvaluationNow()): LotteryHistoryAudit = audit(
        LotteryProduct.LOTTO_645, draws.map { draw ->
            val numbers = draw.numbers()
            Row(draw.roundNo, numbers.size == 6 && numbers.distinct().size == 6 && numbers.all { it in 1..45 } &&
                (draw.bonusNumber == null || draw.bonusNumber in 1..45 && draw.bonusNumber !in numbers),
                draw.bonusNumber != null, !draw.sourceContentHash.isNullOrBlank())
        }, now,
    )

    fun pension(draws: List<PensionLotteryDrawEntity>, now: LocalDateTime = lotteryEvaluationNow()): LotteryHistoryAudit = audit(
        LotteryProduct.PENSION_720, draws.map { draw ->
            Row(draw.roundNo, draw.groupNo in 1..5 && number.matches(draw.winningNumber) &&
                (draw.bonusNumber == null || number.matches(draw.bonusNumber)),
                draw.bonusNumber != null, !draw.sourceContentHash.isNullOrBlank())
        }, now,
    )

    private fun audit(product: LotteryProduct, rows: List<Row>, now: LocalDateTime): LotteryHistoryAudit {
        val firstDeadline = lotteryEvaluationDeadline(product, 1)
        var expected = if (now.isBefore(firstDeadline)) 0 else
            (ChronoUnit.WEEKS.between(firstDeadline.toLocalDate(), now.toLocalDate()) + 1).toInt()
        if (expected > 0 && now.isBefore(lotteryEvaluationDeadline(product, expected))) expected--
        val rounds = rows.map { it.round }
        val present = rounds.toSet()
        val first = rounds.minOrNull()
        val last = rounds.maxOrNull()
        return LotteryHistoryAudit(
            count = rows.size, firstRound = first, lastRound = last, expectedLastRound = expected,
            missingRounds = if (first == null) emptyList() else (first..maxOf(last ?: first, expected)).filterNot(present::contains),
            missingBeforeStart = (first?.minus(1) ?: 0).coerceAtLeast(0),
            duplicateRounds = rounds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted(),
            invalidRounds = rows.filter { !it.valid || it.round <= 0 || it.round > expected }.map { it.round }.distinct().sorted(),
            missingBonusCount = rows.count { !it.hasBonus }, missingSourceHashCount = rows.count { !it.hasSource },
        )
    }

    private val number = Regex("[0-9]{6}")
    private data class Row(val round: Int, val valid: Boolean, val hasBonus: Boolean, val hasSource: Boolean)
}
