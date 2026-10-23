package com.habittracker.ui.lotto

import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity
import com.habittracker.data.lotto.LotteryProduct
import com.habittracker.data.lotto.lotteryEvaluationDeadline
import java.security.MessageDigest

data class PensionLotteryPerformanceSummary(
    val recordedRoundCount: Int,
    val pairedRoundCount: Int,
    val recommendationCount: Int,
    val controlCount: Int,
    val preDrawRecommendationCount: Int,
    val preDrawControlCount: Int,
    val backupCount: Int,
    val issues: Map<String, Int>,
    val settings: List<PensionLotterySettingPerformance>,
)

data class PensionLotterySettingPerformance(
    val generationVersion: String,
    val configHash: String,
    val roundCount: Int,
    val batchCount: Int,
    val recommendation: PensionLotteryPerformanceMetrics,
    val control: PensionLotteryPerformanceMetrics,
)

data class PensionLotteryPerformanceMetrics(
    val suffix1Rate: Double,
    val suffix2Rate: Double,
    val suffix3Rate: Double,
    val bonusRate: Double,
    val lastDigitCoverage: Double,
    val lastTwoDigitsCoverage: Double,
)

internal fun buildPensionLotteryPerformance(
    draws: List<PensionLotteryDrawEntity>,
    rows: List<PensionLotteryGeneratedNumberEntity>,
): PensionLotteryPerformanceSummary {
    val drawsByRound = draws.associateBy { it.roundNo }
    val issues = linkedMapOf<String, Int>()
    fun recordIssue(reason: String, count: Int = 1) { issues[reason] = issues.getOrDefault(reason, 0) + count }
    val backups = rows.filter { it.generationId.startsWith("backup:") }
    val mainRows = rows.filterNot { it.generationId.startsWith("backup:") }
    val batches = mainRows.groupBy { it.generationId }
    val preDrawRows = mainRows.filter { row ->
        val round = row.targetRoundNo
        val savedAt = row.savedAt
        row.isEvaluationTarget && round != null && round > 0 && savedAt != null &&
            savedAt.isBefore(lotteryEvaluationDeadline(LotteryProduct.PENSION_720, round))
    }
    val paired = mutableListOf<List<PensionLotteryGeneratedNumberEntity>>()
    for (batch in batches.values) {
        val first = batch.first()
        val round = first.targetRoundNo
        val config = first.generationConfigJson
        val reason = when {
            round == null || round <= 0 || first.analysisThroughRound != round - 1 ||
                config.isNullOrBlank() || first.generationConfigHash.isNullOrBlank() || first.inputDataHash.isNullOrBlank() ->
                "생성 당시 회차·입력·설정 정보 부족"
            batch.size != 8 || batch.filterNot { it.isControl }.map { it.generationType }.toSet() != RECOMMENDATION_TYPES ||
                batch.filter { it.isControl }.map { it.generationType }.toSet() != CONTROL_TYPES ->
                "추천 4개·대조군 4개 구성 불완전"
            batch.any { it.targetRoundNo != round || it.analysisThroughRound != first.analysisThroughRound ||
                it.generationVersion != first.generationVersion || it.generationConfigHash != first.generationConfigHash ||
                it.generationConfigJson != config || it.inputDataHash != first.inputDataHash } ->
                "배치 내부 회차·입력·설정 불일치"
            else -> null
        }
        if (reason != null) { recordIssue(reason); continue }
        val targetRound = requireNotNull(round)
        val configHash = MessageDigest.getInstance("SHA-256").digest(requireNotNull(config).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (configHash != first.generationConfigHash) { recordIssue("설정 원문·해시 불일치"); continue }
        val deadline = lotteryEvaluationDeadline(LotteryProduct.PENSION_720, targetRound)
        if (batch.any { it.savedAt == null || !it.isEvaluationTarget || !it.savedAt.isBefore(deadline) }) {
            recordIssue("사후 등록·평가 제외·추첨 전 저장 확인 불가"); continue
        }
        val draw = drawsByRound[targetRound]
        if (draw == null) { recordIssue("대상 회차 당첨 결과 대기"); continue }
        if (draw.bonusNumber == null) { recordIssue("보너스 번호 수집 대기"); continue }
        if (batch.any { it.evaluatedAt == null || it.matchedSuffixLength == null || it.positionMatchCount == null || it.isBonusMatch == null }) {
            recordIssue("저장된 일치 결과 평가 대기"); continue
        }
        if (batch.any { row ->
                row.winningNumber.length != 6 || row.winningNumber.any { it !in '0'..'9' } ||
                    row.matchedSuffixLength != row.winningNumber.reversed().zip(draw.winningNumber.reversed()).takeWhile { it.first == it.second }.size ||
                    row.positionMatchCount != row.winningNumber.zip(draw.winningNumber).count { it.first == it.second } ||
                    row.isBonusMatch != (row.winningNumber == draw.bonusNumber)
            } || batch.filterNot { it.isControl }.map { it.winningNumber }.distinct().size != 4 ||
            batch.filter { it.isControl }.map { it.winningNumber }.distinct().size != 4
        ) {
            recordIssue("저장 결과와 현재 당첨 원본 불일치·번호 오류"); continue
        }
        paired.add(batch)
    }
    val settings = mutableListOf<PensionLotterySettingPerformance>()
    val completedRounds = mutableSetOf<Int>()
    for ((key, settingBatches) in paired.groupBy { it.first().generationVersion to requireNotNull(it.first().generationConfigHash) }) {
        val rounds = settingBatches.groupBy { requireNotNull(it.first().targetRoundNo) }.filterValues { roundBatches ->
            val consistent = roundBatches.map { it.first().inputDataHash }.distinct().size == 1
            if (!consistent) recordIssue("같은 회차·설정의 입력 해시 불일치", roundBatches.size)
            consistent
        }
        if (rounds.isEmpty()) continue
        completedRounds += rounds.keys
        fun metrics(control: Boolean): PensionLotteryPerformanceMetrics {
            val perRound = rounds.values.map { roundBatches ->
                val numbers = roundBatches.flatMap { batch -> batch.filter { it.isControl == control } }
                fun rate(matches: Int) = numbers.count { requireNotNull(it.matchedSuffixLength) >= matches }.toDouble() / numbers.size
                PensionLotteryPerformanceMetrics(rate(1), rate(2), rate(3), numbers.count { it.isBonusMatch == true }.toDouble() / numbers.size,
                    roundBatches.map { batch -> batch.filter { it.isControl == control }.map { it.winningNumber.takeLast(1) }.distinct().size }.average(),
                    roundBatches.map { batch -> batch.filter { it.isControl == control }.map { it.winningNumber.takeLast(2) }.distinct().size }.average())
            }
            return PensionLotteryPerformanceMetrics(perRound.map { it.suffix1Rate }.average(), perRound.map { it.suffix2Rate }.average(),
                perRound.map { it.suffix3Rate }.average(), perRound.map { it.bonusRate }.average(),
                perRound.map { it.lastDigitCoverage }.average(), perRound.map { it.lastTwoDigitsCoverage }.average())
        }
        settings += PensionLotterySettingPerformance(key.first, key.second, rounds.size, rounds.values.sumOf { it.size }, metrics(false), metrics(true))
    }
    return PensionLotteryPerformanceSummary(
        recordedRoundCount = mainRows.mapNotNull { it.targetRoundNo?.takeIf { round -> round > 0 } }.distinct().size,
        pairedRoundCount = completedRounds.size,
        recommendationCount = mainRows.count { !it.isControl },
        controlCount = mainRows.count { it.isControl },
        preDrawRecommendationCount = preDrawRows.count { !it.isControl },
        preDrawControlCount = preDrawRows.count { it.isControl },
        backupCount = backups.size,
        issues = issues,
        settings = settings,
    )
}

private val RECOMMENDATION_TYPES = setOf("APPEARED", "APPEARED_SECOND", "COLD_MIX", "COLD_MIX_SECOND")
private val CONTROL_TYPES = setOf("CONTROL_1", "CONTROL_2", "CONTROL_3", "CONTROL_4")
