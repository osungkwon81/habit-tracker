package com.habittracker.data.lotto

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class LottoGeneratedTicket(
    val numbers: List<Int>,
    val comment: String? = null,
)

enum class LottoGenerationMode(
    val label: String,
    val candidatePoolSize: Int,
    val finalistPoolSize: Int,
) {
    FAST("빠른", 3000, 120),
    BASIC("기본", 6000, 180),
    PRECISE("정밀", 12000, 260),
}

object LottoNumberGenerator {
    // 생성 로직을 바꿀 때마다 이 값을 올려야 버전별 당첨 통계를 분리할 수 있다.
    const val CURRENT_GENERATION_VERSION = "2026-07-11-v1"

    private const val maxNumber = 45
    private const val pickCount = 6
    private const val defaultGameCount = 5
    private const val targetSpread = 34.0
    private const val targetVariance = 145.0
    private const val minimumBacktestTrainingDraws = 36
    private const val minimumBacktestSamples = 24
    private val random = Random.Default

    fun generateBalanced(
        history: List<List<Int>>,
        gameCount: Int = defaultGameCount,
        mode: LottoGenerationMode = LottoGenerationMode.BASIC,
    ): List<LottoGeneratedTicket> {
        if (history.isEmpty()) return emptyList()
        val normalizedHistory = history.map { it.sorted() }
        val trendProfile = buildTrendProfile(normalizedHistory)
        val lastDraw = normalizedHistory.first()

        return generateRankedTickets(
            history = normalizedHistory,
            gameCount = gameCount,
            generator = { generatePredictedCombination(trendProfile, CoverageStrategy.BALANCED) },
            validator = { numbers -> isBalancedCandidate(numbers, trendProfile) },
            scorer = { numbers ->
                scoreCoverageCandidate(
                    numbers = numbers,
                    history = normalizedHistory,
                    trendProfile = trendProfile,
                    lastDraw = lastDraw,
                    strategy = CoverageStrategy.BALANCED,
                )
            },
            commentBuilder = { numbers, score ->
                val overlap = numbers.count(lastDraw::contains)
                val covered = numbers.joinToString("-")
                val highCount = numbers.count { it >= 32 }
                val spread = numbers.last() - numbers.first()
                val fit = backtestFitLabel(
                    analysisScore = scoreHistoryAnalysisCandidate(numbers, trendProfile.historyAnalysis),
                    profile = trendProfile.backtestProfile,
                )
                "${mode.label} 모드, 예측점수 ${"%.1f".format(score)}, 백테스트 ${fit}, 빈도/미출현/번호쌍 반영, 고번호 ${highCount}개, 폭 ${spread}, 직전겹침 ${overlap}개, 조합 $covered"
            },
            mode = mode,
            strategy = CoverageStrategy.BALANCED,
        )
    }

    fun generateDiversified(
        history: List<List<Int>>,
        gameCount: Int = defaultGameCount,
        mode: LottoGenerationMode = LottoGenerationMode.BASIC,
    ): List<LottoGeneratedTicket> {
        if (history.isEmpty()) return emptyList()

        val normalizedHistory = history.map { it.sorted() }
        val trendProfile = buildTrendProfile(normalizedHistory)
        val lastDraw = normalizedHistory.first()

        return generateRankedTickets(
            history = normalizedHistory,
            gameCount = gameCount,
            generator = { generatePredictedCombination(trendProfile, CoverageStrategy.DIVERSIFIED) },
            validator = { numbers -> isDiversifiedCandidate(numbers, trendProfile) },
            scorer = { numbers ->
                scoreCoverageCandidate(
                    numbers = numbers,
                    history = normalizedHistory,
                    trendProfile = trendProfile,
                    lastDraw = lastDraw,
                    strategy = CoverageStrategy.DIVERSIFIED,
                )
            },
            commentBuilder = { numbers, score ->
                val carryCount = numbers.count(lastDraw::contains)
                val unpopularScore = publicPickAvoidanceScore(numbers)
                val rareCount = numbers.count { trendProfile.recentFrequency.getValue(it).toDouble() <= trendProfile.recentFrequencyMean }
                val spread = numbers.last() - numbers.first()
                val fit = backtestFitLabel(
                    analysisScore = scoreHistoryAnalysisCandidate(numbers, trendProfile.historyAnalysis),
                    profile = trendProfile.backtestProfile,
                )
                "${mode.label} 모드, 예측점수 ${"%.1f".format(score)}, 백테스트 ${fit}, 공유당첨 회피/구간분산 우선, 평균이하빈도 ${rareCount}개, 공유회피 ${"%.1f".format(unpopularScore)}, 폭 ${spread}, 이월 ${carryCount}개"
            },
            mode = mode,
            strategy = CoverageStrategy.DIVERSIFIED,
        )
    }

    fun generateChatGpt(
        history: List<List<Int>>,
        gameCount: Int = defaultGameCount,
        mode: LottoGenerationMode = LottoGenerationMode.BASIC,
    ): List<LottoGeneratedTicket> =
        generateBalanced(history = history, gameCount = gameCount, mode = mode)

    fun generateGemini(
        history: List<List<Int>>,
        gameCount: Int = defaultGameCount,
        mode: LottoGenerationMode = LottoGenerationMode.BASIC,
    ): List<LottoGeneratedTicket> =
        generateDiversified(history = history, gameCount = gameCount, mode = mode)

    private fun buildTrendProfile(history: List<List<Int>>): TrendProfile {
        val recentWindow = history.take(minOf(30, history.size)).ifEmpty { history }
        val longFrequency = buildFrequencyMap(history)
        val recentFrequency = buildFrequencyMap(recentWindow)
        val longPairFrequency = buildPairFrequencyMap(history)
        val historyAnalysis = buildHistoryAnalysisProfile(history)
        val backtestProfile = buildBacktestProfile(history)
        val lastSeenGap = buildLastSeenGap(history)
        val carryOverlaps = recentWindow.zipWithNext { draw, previousDraw ->
            draw.intersect(previousDraw.toSet()).size
        }

        return TrendProfile(
            recentWindowSize = recentWindow.size,
            recentSumAverage = recentWindow.map(List<Int>::sum).average(),
            recentOddAverage = recentWindow.map { draw -> draw.count { it % 2 != 0 } }.average(),
            recentLowAverage = recentWindow.map { draw -> draw.count { it <= 22 } }.average(),
            recentBucketAverage = recentWindow.map(::decadeBucketCount).average(),
            recentCarryAverage = carryOverlaps.takeIf { it.isNotEmpty() }?.average() ?: 1.0,
            longFrequencyMean = longFrequency.values.average(),
            recentFrequencyMean = recentFrequency.values.average(),
            pairFrequencyMean = longPairFrequency.values.average(),
            longFrequency = longFrequency,
            recentFrequency = recentFrequency,
            pairFrequency = longPairFrequency,
            historyAnalysis = historyAnalysis,
            backtestProfile = backtestProfile,
            lastSeenGap = lastSeenGap,
        )
    }

    private fun generateRandomCombination(): List<Int> {
        val selected = mutableSetOf<Int>()
        while (selected.size < pickCount) {
            selected += random.nextInt(maxNumber) + 1
        }
        return selected.sorted()
    }

    private fun generatePredictedCombination(
        trendProfile: TrendProfile,
        strategy: CoverageStrategy,
    ): List<Int> {
        val selected = mutableSetOf<Int>()

        while (selected.size < pickCount) {
            val candidates = (1..maxNumber).filter { number -> number !in selected }
            if (candidates.isEmpty()) return generateRandomCombination()
            selected += pickWeightedNumber(
                candidates = candidates,
                selected = selected,
                trendProfile = trendProfile,
                strategy = strategy,
            )
        }

        return selected.sorted()
    }

    private fun pickWeightedNumber(
        candidates: List<Int>,
        selected: Set<Int>,
        trendProfile: TrendProfile,
        strategy: CoverageStrategy,
    ): Int {
        val weights = candidates.map { number ->
            predictedNumberWeight(
                number = number,
                selected = selected,
                trendProfile = trendProfile,
                strategy = strategy,
            ).coerceAtLeast(0.05)
        }
        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) return candidates[random.nextInt(candidates.size)]

        var threshold = random.nextDouble() * totalWeight
        for (index in candidates.indices) {
            threshold -= weights[index]
            if (threshold <= 0.0) return candidates[index]
        }
        return candidates.last()
    }

    private fun predictedNumberWeight(
        number: Int,
        selected: Set<Int>,
        trendProfile: TrendProfile,
        strategy: CoverageStrategy,
    ): Double {
        val longMean = trendProfile.longFrequencyMean.coerceAtLeast(1.0)
        val recentMean = trendProfile.recentFrequencyMean.coerceAtLeast(1.0)
        val gapTarget = trendProfile.lastSeenGap.values.average().coerceAtLeast(1.0)
        val longFrequency = trendProfile.longFrequency.getValue(number).toDouble()
        val recentFrequency = trendProfile.recentFrequency.getValue(number).toDouble()
        val gap = trendProfile.lastSeenGap.getValue(number).toDouble()
        val longFit = 1.0 + max(0.0, 1.0 - abs(longFrequency - longMean) / longMean) * 0.8
        val recentFit = when (strategy) {
            CoverageStrategy.BALANCED ->
                1.0 + max(0.0, 1.0 - abs(recentFrequency - recentMean) / recentMean) * 0.7
            CoverageStrategy.DIVERSIFIED ->
                if (recentFrequency <= recentMean) 1.45 else 0.85
        }
        val gapFit = 1.0 + minOf(gap / gapTarget, 2.0) * when (strategy) {
            CoverageStrategy.BALANCED -> 0.22
            CoverageStrategy.DIVERSIFIED -> 0.34
        }
        val highNumberFit = when {
            number >= 32 && strategy == CoverageStrategy.DIVERSIFIED -> 1.18
            number >= 32 -> 1.05
            else -> 1.0
        }

        return longFit * recentFit * gapFit * highNumberFit * selectedPairFreshnessFit(number, selected, trendProfile, strategy)
    }

    private fun selectedPairFreshnessFit(
        number: Int,
        selected: Set<Int>,
        trendProfile: TrendProfile,
        strategy: CoverageStrategy,
    ): Double {
        if (selected.isEmpty()) return 1.0

        val pairMean = trendProfile.pairFrequencyMean.coerceAtLeast(0.1)
        val averagePairFrequency = selected.map { picked ->
            val pair = if (number < picked) number to picked else picked to number
            trendProfile.pairFrequency.getValue(pair).toDouble()
        }.average()
        val excess = max(0.0, averagePairFrequency - pairMean)
        val penalty = when (strategy) {
            CoverageStrategy.BALANCED -> 0.10
            CoverageStrategy.DIVERSIFIED -> 0.18
        }
        return (1.0 / (1.0 + excess * penalty)).coerceIn(0.55, 1.2)
    }

    private fun isBaseCoverageCandidate(numbers: List<Int>): Boolean {
        if (numbers.size != pickCount || numbers.distinct().size != pickCount) return false
        val sum = numbers.sum()
        if (sum !in 80..210) return false
        val oddCount = numbers.count { it % 2 != 0 }
        if (oddCount !in 1..5) return false
        val lowCount = numbers.count { it <= 22 }
        if (lowCount !in 1..5) return false
        val highCount = numbers.count { it >= 32 }
        if (highCount !in 1..4) return false
        val middleCount = numbers.count { it in 16..30 }
        if (middleCount !in 1..4) return false
        val spread = numbers.last() - numbers.first()
        if (spread !in 18..42) return false
        if (numberVariance(numbers) !in 45.0..230.0) return false
        if (decadeBucketCount(numbers) < 3) return false
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
        if (tailDuplicates > 3) return false
        if (maxConsecutiveRun(numbers) > 3) return false
        if (isSimplePattern(numbers)) return false
        return acValue(numbers) >= 4
    }

    private fun isBalancedCandidate(numbers: List<Int>, trendProfile: TrendProfile): Boolean {
        if (!isBaseCoverageCandidate(numbers)) return false
        val sum = numbers.sum()
        val oddCount = numbers.count { it % 2 != 0 }
        val lowCount = numbers.count { it <= 22 }
        val highCount = numbers.count { it >= 32 }
        val bucketCounts = decadeBucketCounts(numbers)

        if (abs(sum - trendProfile.recentSumAverage) > 42.0) return false
        if (abs(oddCount - trendProfile.recentOddAverage) > 2.0) return false
        if (abs(lowCount - trendProfile.recentLowAverage) > 2.0) return false
        if (lowCount !in 2..4) return false
        if (highCount !in 1..3) return false
        if (bucketCounts.values.any { it > 2 }) return false
        if (!passesBacktestFloor(numbers, trendProfile)) return false
        return numberVariance(numbers) in 65.0..190.0
    }

    private fun isDiversifiedCandidate(numbers: List<Int>, trendProfile: TrendProfile): Boolean {
        if (!isBaseCoverageCandidate(numbers)) return false
        val highCount = numbers.count { it >= 32 }
        val rareCount = numbers.count { trendProfile.recentFrequency.getValue(it).toDouble() <= trendProfile.recentFrequencyMean }
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1

        if (highCount < 2) return false
        if (rareCount < 3) return false
        if (numbers.last() - numbers.first() < 27) return false
        if (decadeBucketCount(numbers) < 4) return false
        if (tailDuplicates > 2) return false
        if (!passesBacktestFloor(numbers, trendProfile)) return false
        return acValue(numbers) >= 6
    }

    private fun generateRankedTickets(
        history: List<List<Int>>,
        gameCount: Int,
        generator: () -> List<Int>,
        validator: (List<Int>) -> Boolean,
        scorer: (List<Int>) -> Double,
        commentBuilder: (List<Int>, Double) -> String,
        mode: LottoGenerationMode,
        strategy: CoverageStrategy,
    ): List<LottoGeneratedTicket> {
        val candidates = linkedSetOf<List<Int>>()
        val maxAttempts = mode.candidatePoolSize * 20
        var attempt = 0

        while (candidates.size < mode.candidatePoolSize && attempt < maxAttempts) {
            val candidate = generator().sorted()
            if (validator(candidate) && !isTooSimilarToHistory(candidate, history)) {
                candidates += candidate
            }
            attempt++
        }

        val scored = candidates
            .map { numbers -> ScoredCandidate(numbers = numbers, score = scorer(numbers)) }
            .sortedByDescending(ScoredCandidate::score)
            .take(mode.finalistPoolSize)

        return pickDiverseTopGames(scored, gameCount, strategy).map { candidate ->
            LottoGeneratedTicket(
                numbers = candidate.numbers,
                comment = commentBuilder(candidate.numbers, candidate.score),
            )
        }
    }

    private fun pickDiverseTopGames(
        candidates: List<ScoredCandidate>,
        gameCount: Int,
        strategy: CoverageStrategy,
    ): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val selected = mutableListOf<ScoredCandidate>()
        val remaining = candidates.toMutableList()

        selected += remaining.removeFirst()

        while (selected.size < gameCount && remaining.isNotEmpty()) {
            val coverage = selected.flatMap { it.numbers }.toSet()
            val numberUsage = selected.flatMap { it.numbers }.groupingBy { it }.eachCount()
            val selectedPairs = selected.flatMap { drawPairs(it.numbers) }.toSet()
            val next = remaining.maxByOrNull { candidate ->
                val overlapPenalty = selected.sumOf { picked ->
                    val overlap = candidate.numbers.intersect(picked.numbers.toSet()).size
                    when {
                        overlap >= 4 -> 10.0
                        overlap == 3 -> 5.0
                        overlap == 2 -> 1.5
                        else -> 0.0
                    }
                } * strategy.selectionOverlapWeight
                val repeatedNumberPenalty = candidate.numbers.sumOf { number ->
                    when (numberUsage[number] ?: 0) {
                        0 -> 0.0
                        1 -> strategy.repeatedNumberPenalty
                        else -> strategy.repeatedNumberPenalty * 2.4
                    }
                }
                val repeatedPairPenalty = drawPairs(candidate.numbers).count { it in selectedPairs } * strategy.repeatedPairPenalty
                val newCoverage = candidate.numbers.count { it !in coverage } * strategy.newCoverageWeight
                val spacingBonus = decadeBucketCount(candidate.numbers) * strategy.bucketBonusWeight
                candidate.score + newCoverage - overlapPenalty - repeatedNumberPenalty - repeatedPairPenalty + spacingBonus
            } ?: break
            selected += next
            remaining.remove(next)
        }

        return selected.take(gameCount)
    }

    private fun scoreCoverageCandidate(
        numbers: List<Int>,
        history: List<List<Int>>,
        trendProfile: TrendProfile,
        lastDraw: List<Int>,
        strategy: CoverageStrategy,
    ): Double {
        val sum = numbers.sum()
        val oddCount = numbers.count { it % 2 != 0 }
        val lowCount = numbers.count { it <= 22 }
        val bucketCount = decadeBucketCount(numbers)
        val overlapWithLast = numbers.count(lastDraw::contains)
        val consecutiveRun = maxConsecutiveRun(numbers)
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
        val spread = numbers.last() - numbers.first()
        val variance = numberVariance(numbers)
        val pairs = drawPairs(numbers)
        val longFrequencyAverage = numbers.sumOf { trendProfile.longFrequency.getValue(it).toDouble() } / pickCount
        val recentFrequencyAverage = numbers.sumOf { trendProfile.recentFrequency.getValue(it).toDouble() } / pickCount
        val pairFrequencyAverage = pairs.sumOf { trendProfile.pairFrequency.getValue(it).toDouble() } / pairs.size
        val gapAverage = numbers.sumOf { trendProfile.lastSeenGap.getValue(it).toDouble() } / pickCount
        val gapTarget = trendProfile.lastSeenGap.values.average()
        val longFrequencyScore = 6.0 - abs(longFrequencyAverage - trendProfile.longFrequencyMean) * 0.35
        val recentFrequencyScore = when (strategy) {
            CoverageStrategy.BALANCED -> {
                val belowRecentAverage = numbers.count { trendProfile.recentFrequency.getValue(it).toDouble() <= trendProfile.recentFrequencyMean }
                (belowRecentAverage * 0.5) + (4.0 - abs(recentFrequencyAverage - trendProfile.recentFrequencyMean) * 0.35)
            }
            CoverageStrategy.DIVERSIFIED -> {
                val belowRecentAverage = numbers.count { trendProfile.recentFrequency.getValue(it).toDouble() <= trendProfile.recentFrequencyMean }
                belowRecentAverage * 1.1
            }
        }
        val recencyGapScore = 5.0 - abs(gapAverage - gapTarget) * 0.18
        val pairFreshnessScore =
            4.0 - max(0.0, pairFrequencyAverage - trendProfile.pairFrequencyMean) * strategy.pairFreshnessPenalty
        val historyAnalysisScore = scoreHistoryAnalysisCandidate(numbers, trendProfile.historyAnalysis)
        val backtestFitScore = scoreBacktestFit(historyAnalysisScore, trendProfile.backtestProfile)
        val trendShapeScore =
            (10.0 - abs(sum - trendProfile.recentSumAverage) / 8.0) +
                (3.0 - abs(oddCount - trendProfile.recentOddAverage) * 0.7) +
                (3.0 - abs(lowCount - trendProfile.recentLowAverage) * 0.6) +
                (3.0 - abs(bucketCount - trendProfile.recentBucketAverage) * 0.8) +
                (4.0 - abs(overlapWithLast - trendProfile.recentCarryAverage) * 1.2)
        val distributionScore = when (strategy) {
            CoverageStrategy.BALANCED ->
                (5.0 - abs(spread - targetSpread) * 0.2) +
                    (5.0 - abs(variance - targetVariance) / 28.0) +
                    rangeBalanceScore(numbers) +
                    lowMiddleHighBalanceScore(numbers)
            CoverageStrategy.DIVERSIFIED ->
                (5.0 - abs(spread - 38.0) * 0.16) +
                    (5.0 - abs(variance - 170.0) / 34.0) +
                    rangeDiversityScore(numbers)
        }
        val coverageShapeScore = (bucketCount * 1.8) + (acValue(numbers) * 0.8)
        val historyPenalty = history.count { past -> past.intersect(numbers.toSet()).size >= 4 } * 1.2
        val duplicateTailPenalty = if (tailDuplicates >= 3) 1.5 else 0.0
        val consecutivePenalty = if (consecutiveRun >= 3) 1.5 else 0.0
        val simplePatternPenalty = if (isSimplePattern(numbers)) 4.0 else 0.0

        return trendShapeScore * strategy.trendShapeWeight +
            distributionScore +
            coverageShapeScore +
            longFrequencyScore +
            recentFrequencyScore +
            recencyGapScore +
            pairFreshnessScore +
            historyAnalysisScore * strategy.historyAnalysisWeight +
            backtestFitScore * strategy.backtestFitWeight +
            publicPickAvoidanceScore(numbers) * strategy.publicPickAvoidanceWeight -
            historyPenalty -
            duplicateTailPenalty -
            consecutivePenalty -
            simplePatternPenalty
    }

    private fun buildFrequencyMap(history: List<List<Int>>): Map<Int, Int> {
        val frequency = (1..maxNumber).associateWith { 0 }.toMutableMap()
        history.flatten().forEach { number ->
            frequency[number] = frequency.getValue(number) + 1
        }
        return frequency
    }

    private fun buildPairFrequencyMap(history: List<List<Int>>): Map<Pair<Int, Int>, Int> {
        val frequency = mutableMapOf<Pair<Int, Int>, Int>()
        for (first in 1 until maxNumber) {
            for (second in first + 1..maxNumber) {
                frequency[first to second] = 0
            }
        }
        history.forEach { draw ->
            drawPairs(draw).forEach { pair ->
                frequency[pair] = frequency.getValue(pair) + 1
            }
        }
        return frequency
    }

    private fun buildHistoryAnalysisProfile(history: List<List<Int>>): HistoryAnalysisProfile {
        val analysisWindow = history.take(minOf(180, history.size)).ifEmpty { history }
        return HistoryAnalysisProfile(
            drawCount = analysisWindow.size,
            sumAverage = analysisWindow.map(List<Int>::sum).average(),
            spreadAverage = analysisWindow.map { draw -> draw.last() - draw.first() }.average(),
            varianceAverage = analysisWindow.map(::numberVariance).average(),
            acAverage = analysisWindow.map(::acValue).average(),
            oddAverage = analysisWindow.map { draw -> draw.count { it % 2 != 0 } }.average(),
            lowAverage = analysisWindow.map { draw -> draw.count { it <= 22 } }.average(),
            highAverage = analysisWindow.map { draw -> draw.count { it >= 32 } }.average(),
            bucketAverage = analysisWindow.map(::decadeBucketCount).average(),
            sumBuckets = analysisWindow.groupingBy { draw -> draw.sum() / 10 }.eachCount(),
            spreadBuckets = analysisWindow.groupingBy { draw -> (draw.last() - draw.first()) / 5 }.eachCount(),
            acBuckets = analysisWindow.groupingBy { draw -> acValue(draw) }.eachCount(),
        )
    }

    private fun buildBacktestProfile(history: List<List<Int>>): BacktestProfile {
        val scores = mutableListOf<Double>()
        val maxSamples = minOf(120, history.size - minimumBacktestTrainingDraws)
        if (maxSamples <= 0) return BacktestProfile()

        for (targetIndex in 0 until maxSamples) {
            val trainingHistory = history.drop(targetIndex + 1)
            if (trainingHistory.size < minimumBacktestTrainingDraws) continue
            val trainingProfile = buildHistoryAnalysisProfile(trainingHistory)
            scores += scoreHistoryAnalysisCandidate(history[targetIndex], trainingProfile)
        }

        if (scores.size < minimumBacktestSamples) return BacktestProfile(sampleCount = scores.size)
        val sortedScores = scores.sorted()
        return BacktestProfile(
            sampleCount = scores.size,
            minimumAcceptedScore = quantile(sortedScores, 0.4),
            strongScore = quantile(sortedScores, 0.65),
            averageScore = scores.average(),
        )
    }

    private fun buildLastSeenGap(history: List<List<Int>>): Map<Int, Int> {
        return (1..maxNumber).associateWith { number ->
            history.indexOfFirst { draw -> number in draw }.takeIf { it >= 0 } ?: history.size
        }
    }

    private fun scoreHistoryAnalysisCandidate(numbers: List<Int>, profile: HistoryAnalysisProfile): Double {
        if (profile.drawCount == 0) return 0.0
        val sum = numbers.sum()
        val spread = numbers.last() - numbers.first()
        val variance = numberVariance(numbers)
        val ac = acValue(numbers)
        val oddCount = numbers.count { it % 2 != 0 }
        val lowCount = numbers.count { it <= 22 }
        val highCount = numbers.count { it >= 32 }
        val bucketCount = decadeBucketCount(numbers)

        val shapeScore =
            max(0.0, 4.0 - abs(sum - profile.sumAverage) / 14.0) +
                max(0.0, 3.0 - abs(spread - profile.spreadAverage) * 0.18) +
                max(0.0, 3.0 - abs(variance - profile.varianceAverage) / 42.0) +
                max(0.0, 2.5 - abs(ac - profile.acAverage) * 0.45) +
                max(0.0, 2.0 - abs(oddCount - profile.oddAverage) * 0.55) +
                max(0.0, 2.0 - abs(lowCount - profile.lowAverage) * 0.5) +
                max(0.0, 2.0 - abs(highCount - profile.highAverage) * 0.55) +
                max(0.0, 1.8 - abs(bucketCount - profile.bucketAverage) * 0.65)
        val bucketScore =
            patternBucketScore(profile.sumBuckets, sum / 10) * 2.0 +
                patternBucketScore(profile.spreadBuckets, spread / 5) * 1.4 +
                patternBucketScore(profile.acBuckets, ac) * 1.2

        return shapeScore + bucketScore
    }

    private fun passesBacktestFloor(numbers: List<Int>, trendProfile: TrendProfile): Boolean {
        val backtestProfile = trendProfile.backtestProfile
        if (backtestProfile.sampleCount < minimumBacktestSamples) return true
        return scoreHistoryAnalysisCandidate(numbers, trendProfile.historyAnalysis) >= backtestProfile.minimumAcceptedScore
    }

    private fun scoreBacktestFit(analysisScore: Double, profile: BacktestProfile): Double {
        if (profile.sampleCount < minimumBacktestSamples) return 0.0
        return when {
            analysisScore >= profile.strongScore -> 2.4
            analysisScore >= profile.averageScore -> 1.4
            analysisScore >= profile.minimumAcceptedScore -> 0.6
            else -> -(profile.minimumAcceptedScore - analysisScore) * 0.7
        }
    }

    private fun backtestFitLabel(analysisScore: Double, profile: BacktestProfile): String {
        if (profile.sampleCount < minimumBacktestSamples) return "표본부족"
        return when {
            analysisScore >= profile.strongScore -> "강함"
            analysisScore >= profile.averageScore -> "평균상회"
            analysisScore >= profile.minimumAcceptedScore -> "통과"
            else -> "미달"
        }
    }

    private fun patternBucketScore(buckets: Map<Int, Int>, bucket: Int): Double {
        val maxCount = buckets.values.maxOrNull()?.takeIf { it > 0 } ?: return 0.0
        val weightedCount =
            buckets.getOrDefault(bucket, 0).toDouble() +
                buckets.getOrDefault(bucket - 1, 0) * 0.45 +
                buckets.getOrDefault(bucket + 1, 0) * 0.45
        return weightedCount / maxCount
    }

    private fun quantile(sortedValues: List<Double>, ratio: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = ((sortedValues.lastIndex) * ratio).toInt().coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun publicPickAvoidanceScore(numbers: List<Int>): Double {
        val highNumberCount = numbers.count { it >= 32 }
        val birthdayHeavyCount = numbers.count { it <= 31 }
        val birthdayPenalty = when (highNumberCount) {
            0 -> 6.0
            1 -> 2.5
            else -> 0.0
        }
        val birthdayHeavyPenalty = when {
            birthdayHeavyCount >= 6 -> 4.0
            birthdayHeavyCount == 5 -> 1.8
            else -> 0.0
        }
        val simplePatternPenalty =
            if (maxConsecutiveRun(numbers) >= 3 || numbers.zipWithNext().map { it.second - it.first }.distinct().size <= 2) 3.0 else 0.0
        val sameTailPenalty = maxOf(0, (numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1) - 2) * 1.2
        val roundNumberPenalty = maxOf(0, numbers.count { it % 5 == 0 } - 2) * 0.8
        val spreadBonus = highNumberCount * 1.1 + decadeBucketCount(numbers) * 0.7 + acValue(numbers) * 0.35

        return spreadBonus - birthdayPenalty - birthdayHeavyPenalty - simplePatternPenalty - sameTailPenalty - roundNumberPenalty
    }

    private fun isTooSimilarToHistory(numbers: List<Int>, history: List<List<Int>>): Boolean =
        history.any { past -> past.intersect(numbers.toSet()).size >= 5 }

    private fun acValue(numbers: List<Int>): Int {
        val diffs = mutableSetOf<Int>()
        for (i in numbers.indices) {
            for (j in i + 1 until numbers.size) {
                diffs += abs(numbers[i] - numbers[j])
            }
        }
        return diffs.size - (numbers.size - 1)
    }

    private fun maxConsecutiveRun(numbers: List<Int>): Int {
        if (numbers.isEmpty()) return 0
        var longest = 1
        var current = 1

        for (index in 1 until numbers.size) {
            if (numbers[index] == numbers[index - 1] + 1) {
                current++
                longest = max(longest, current)
            } else {
                current = 1
            }
        }
        return longest
    }

    private fun isSimplePattern(numbers: List<Int>): Boolean {
        val gaps = numbers.zipWithNext { first, second -> second - first }
        if (gaps.distinct().size <= 2) return true
        if (numbers.all { it <= 31 }) return true
        if (numbers.count { it % 10 == numbers.first() % 10 } >= 4) return true
        return numbers.count { it % 5 == 0 } >= 4
    }

    private fun rangeBalanceScore(numbers: List<Int>): Double {
        val bucketCounts = decadeBucketCounts(numbers)
        val concentrationPenalty = bucketCounts.values.sumOf { count -> maxOf(0, count - 2) * 1.2 }
        val edgePenalty = maxOf(0, numbers.count { it <= 5 || it >= 41 } - 2) * 0.9
        val middleCoverageBonus = numbers.count { it in 16..30 } * 0.45
        return decadeBucketCount(numbers) * 0.9 + middleCoverageBonus - concentrationPenalty - edgePenalty
    }

    private fun rangeDiversityScore(numbers: List<Int>): Double {
        val bucketCounts = decadeBucketCounts(numbers)
        val concentrationPenalty = bucketCounts.values.sumOf { count -> maxOf(0, count - 2) * 1.6 }
        val highNumberBonus = numbers.count { it >= 32 } * 0.75
        return decadeBucketCount(numbers) * 1.25 + acValue(numbers) * 0.45 + highNumberBonus - concentrationPenalty
    }

    private fun lowMiddleHighBalanceScore(numbers: List<Int>): Double {
        val lowCount = numbers.count { it <= 15 }
        val middleCount = numbers.count { it in 16..30 }
        val highCount = numbers.count { it >= 31 }
        val counts = listOf(lowCount, middleCount, highCount)
        return 3.0 - ((counts.maxOrNull() ?: 0) - (counts.minOrNull() ?: 0)) * 0.8
    }

    private fun numberVariance(numbers: List<Int>): Double {
        val average = numbers.average()
        return numbers.sumOf { number ->
            val diff = number - average
            diff * diff
        } / numbers.size
    }

    private fun drawPairs(numbers: List<Int>): List<Pair<Int, Int>> {
        val sorted = numbers.sorted()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (firstIndex in sorted.indices) {
            for (secondIndex in firstIndex + 1 until sorted.size) {
                pairs += sorted[firstIndex] to sorted[secondIndex]
            }
        }
        return pairs
    }

    private fun decadeBucketCount(numbers: List<Int>): Int =
        numbers.map { (it - 1) / 10 }.distinct().size

    private fun decadeBucketCounts(numbers: List<Int>): Map<Int, Int> =
        numbers.groupingBy { (it - 1) / 10 }.eachCount()

    private data class TrendProfile(
        val recentWindowSize: Int,
        val recentSumAverage: Double,
        val recentOddAverage: Double,
        val recentLowAverage: Double,
        val recentBucketAverage: Double,
        val recentCarryAverage: Double,
        val longFrequencyMean: Double,
        val recentFrequencyMean: Double,
        val pairFrequencyMean: Double,
        val longFrequency: Map<Int, Int>,
        val recentFrequency: Map<Int, Int>,
        val pairFrequency: Map<Pair<Int, Int>, Int>,
        val historyAnalysis: HistoryAnalysisProfile,
        val backtestProfile: BacktestProfile,
        val lastSeenGap: Map<Int, Int>,
    )

    private data class HistoryAnalysisProfile(
        val drawCount: Int,
        val sumAverage: Double,
        val spreadAverage: Double,
        val varianceAverage: Double,
        val acAverage: Double,
        val oddAverage: Double,
        val lowAverage: Double,
        val highAverage: Double,
        val bucketAverage: Double,
        val sumBuckets: Map<Int, Int>,
        val spreadBuckets: Map<Int, Int>,
        val acBuckets: Map<Int, Int>,
    )

    private data class BacktestProfile(
        val sampleCount: Int = 0,
        val minimumAcceptedScore: Double = 0.0,
        val strongScore: Double = 0.0,
        val averageScore: Double = 0.0,
    )

    private data class ScoredCandidate(
        val numbers: List<Int>,
        val score: Double,
    )

    private enum class CoverageStrategy(
        val selectionOverlapWeight: Double,
        val repeatedNumberPenalty: Double,
        val repeatedPairPenalty: Double,
        val newCoverageWeight: Double,
        val bucketBonusWeight: Double,
        val pairFreshnessPenalty: Double,
        val publicPickAvoidanceWeight: Double,
        val trendShapeWeight: Double,
        val historyAnalysisWeight: Double,
        val backtestFitWeight: Double,
    ) {
        BALANCED(
            selectionOverlapWeight = 1.0,
            repeatedNumberPenalty = 0.7,
            repeatedPairPenalty = 1.3,
            newCoverageWeight = 1.0,
            bucketBonusWeight = 0.45,
            pairFreshnessPenalty = 0.45,
            publicPickAvoidanceWeight = 0.75,
            trendShapeWeight = 1.15,
            historyAnalysisWeight = 0.95,
            backtestFitWeight = 1.0,
        ),
        DIVERSIFIED(
            selectionOverlapWeight = 1.45,
            repeatedNumberPenalty = 1.15,
            repeatedPairPenalty = 2.25,
            newCoverageWeight = 1.55,
            bucketBonusWeight = 0.7,
            pairFreshnessPenalty = 0.8,
            publicPickAvoidanceWeight = 1.35,
            trendShapeWeight = 0.65,
            historyAnalysisWeight = 0.7,
            backtestFitWeight = 0.8,
        ),
    }
}
