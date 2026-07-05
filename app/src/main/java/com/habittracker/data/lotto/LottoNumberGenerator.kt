package com.habittracker.data.lotto

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.max

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
    private const val maxNumber = 45
    private const val pickCount = 6
    private const val defaultGameCount = 5
    private const val targetSpread = 34.0
    private const val targetVariance = 145.0
    private val secureRandom = SecureRandom()

    fun generateChatGpt(
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
            generator = ::generateRandomCombination,
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
                "${mode.label} 모드, 보안난수/빈도/간격/분산 반영, 점수 ${"%.1f".format(score)}, 고번호 ${highCount}개, 폭 ${spread}, 직전겹침 ${overlap}개, 조합 $covered"
            },
            mode = mode,
            strategy = CoverageStrategy.BALANCED,
        )
    }

    fun generateGemini(
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
            generator = ::generateRandomCombination,
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
                "${mode.label} 모드, 공유당첨 회피/구간분산 우선, 점수 ${"%.1f".format(score)}, 평균이하빈도 ${rareCount}개, 공유회피 ${"%.1f".format(unpopularScore)}, 폭 ${spread}, 이월 ${carryCount}개"
            },
            mode = mode,
            strategy = CoverageStrategy.DIVERSIFIED,
        )
    }

    private fun buildTrendProfile(history: List<List<Int>>): TrendProfile {
        val recentWindow = history.take(minOf(30, history.size)).ifEmpty { history }
        val longFrequency = buildFrequencyMap(history)
        val recentFrequency = buildFrequencyMap(recentWindow)
        val longPairFrequency = buildPairFrequencyMap(history)
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
            lastSeenGap = lastSeenGap,
        )
    }

    private fun generateRandomCombination(): List<Int> {
        val pool = (1..maxNumber).toMutableList()
        for (index in pool.lastIndex downTo 1) {
            val swapIndex = secureRandom.nextInt(index + 1)
            val temp = pool[index]
            pool[index] = pool[swapIndex]
            pool[swapIndex] = temp
        }
        return pool.take(pickCount).sorted()
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

    private fun buildLastSeenGap(history: List<List<Int>>): Map<Int, Int> {
        return (1..maxNumber).associateWith { number ->
            history.indexOfFirst { draw -> number in draw }.takeIf { it >= 0 } ?: history.size
        }
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
        val lastSeenGap: Map<Int, Int>,
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
        ),
    }
}
