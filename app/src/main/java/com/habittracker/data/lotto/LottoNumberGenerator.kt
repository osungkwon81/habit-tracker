package com.habittracker.data.lotto

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
            validator = ::isCoverageCandidate,
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
                "${mode.label} 모드, 과거 빈도/최근 간격 반영, 점수 ${"%.1f".format(score)}, 고번호 ${highCount}개, 직전겹침 ${overlap}개, 조합 $covered"
            },
            mode = mode,
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
            validator = ::isCoverageCandidate,
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
                "${mode.label} 모드, 비인기 조합 우선, 점수 ${"%.1f".format(score)}, 평균이하빈도 ${rareCount}개, 공유회피 ${"%.1f".format(unpopularScore)}, 이월 ${carryCount}개"
            },
            mode = mode,
        )
    }

    private fun buildTrendProfile(history: List<List<Int>>): TrendProfile {
        val recentWindow = history.take(minOf(30, history.size)).ifEmpty { history }
        val longFrequency = buildFrequencyMap(history)
        val recentFrequency = buildFrequencyMap(recentWindow)
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
            longFrequency = longFrequency,
            recentFrequency = recentFrequency,
            lastSeenGap = lastSeenGap,
        )
    }

    private fun generateRandomCombination(): List<Int> =
        (1..maxNumber).shuffled().take(pickCount).sorted()

    private fun isCoverageCandidate(numbers: List<Int>): Boolean {
        if (numbers.size != pickCount || numbers.distinct().size != pickCount) return false
        val sum = numbers.sum()
        if (sum !in 80..210) return false
        val oddCount = numbers.count { it % 2 != 0 }
        if (oddCount !in 1..5) return false
        val lowCount = numbers.count { it <= 22 }
        if (lowCount !in 1..5) return false
        if (decadeBucketCount(numbers) < 3) return false
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
        if (tailDuplicates > 3) return false
        if (maxConsecutiveRun(numbers) > 3) return false
        return acValue(numbers) >= 4
    }

    private fun generateRankedTickets(
        history: List<List<Int>>,
        gameCount: Int,
        generator: () -> List<Int>,
        validator: (List<Int>) -> Boolean,
        scorer: (List<Int>) -> Double,
        commentBuilder: (List<Int>, Double) -> String,
        mode: LottoGenerationMode,
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

        return pickDiverseTopGames(scored, gameCount).map { candidate ->
            LottoGeneratedTicket(
                numbers = candidate.numbers,
                comment = commentBuilder(candidate.numbers, candidate.score),
            )
        }
    }

    private fun pickDiverseTopGames(
        candidates: List<ScoredCandidate>,
        gameCount: Int,
    ): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val selected = mutableListOf<ScoredCandidate>()
        val remaining = candidates.toMutableList()

        selected += remaining.removeFirst()

        while (selected.size < gameCount && remaining.isNotEmpty()) {
            val coverage = selected.flatMap { it.numbers }.toSet()
            val next = remaining.maxByOrNull { candidate ->
                val overlapPenalty = selected.sumOf { picked ->
                    val overlap = candidate.numbers.intersect(picked.numbers.toSet()).size
                    when {
                        overlap >= 4 -> 10.0
                        overlap == 3 -> 5.0
                        overlap == 2 -> 1.5
                        else -> 0.0
                    }
                }
                val newCoverage = candidate.numbers.count { it !in coverage } * 1.2
                val spacingBonus = decadeBucketCount(candidate.numbers) * 0.5
                candidate.score + newCoverage - overlapPenalty + spacingBonus
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
        val longFrequencyAverage = numbers.sumOf { trendProfile.longFrequency.getValue(it).toDouble() } / pickCount
        val recentFrequencyAverage = numbers.sumOf { trendProfile.recentFrequency.getValue(it).toDouble() } / pickCount
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
        val trendShapeScore =
            (10.0 - abs(sum - trendProfile.recentSumAverage) / 8.0) +
                (3.0 - abs(oddCount - trendProfile.recentOddAverage) * 0.7) +
                (3.0 - abs(lowCount - trendProfile.recentLowAverage) * 0.6) +
                (3.0 - abs(bucketCount - trendProfile.recentBucketAverage) * 0.8) +
                (4.0 - abs(overlapWithLast - trendProfile.recentCarryAverage) * 1.2)
        val coverageShapeScore = (bucketCount * 1.8) + (acValue(numbers) * 0.8)
        val historyPenalty = history.count { past -> past.intersect(numbers.toSet()).size >= 4 } * 1.2
        val duplicateTailPenalty = if (tailDuplicates >= 3) 1.5 else 0.0
        val consecutivePenalty = if (consecutiveRun >= 3) 1.5 else 0.0

        return trendShapeScore +
            coverageShapeScore +
            longFrequencyScore +
            recentFrequencyScore +
            recencyGapScore +
            publicPickAvoidanceScore(numbers) -
            historyPenalty -
            duplicateTailPenalty -
            consecutivePenalty
    }

    private fun buildFrequencyMap(history: List<List<Int>>): Map<Int, Int> {
        val frequency = (1..maxNumber).associateWith { 0 }.toMutableMap()
        history.flatten().forEach { number ->
            frequency[number] = frequency.getValue(number) + 1
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
        val birthdayPenalty = when (highNumberCount) {
            0 -> 6.0
            1 -> 2.5
            else -> 0.0
        }
        val simplePatternPenalty =
            if (maxConsecutiveRun(numbers) >= 3 || numbers.zipWithNext().map { it.second - it.first }.distinct().size <= 2) 3.0 else 0.0
        val sameTailPenalty = maxOf(0, (numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1) - 2) * 1.2
        val spreadBonus = highNumberCount * 1.1 + decadeBucketCount(numbers) * 0.7 + acValue(numbers) * 0.35

        return spreadBonus - birthdayPenalty - simplePatternPenalty - sameTailPenalty
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

    private fun decadeBucketCount(numbers: List<Int>): Int =
        numbers.map { (it - 1) / 10 }.distinct().size

    private data class TrendProfile(
        val recentWindowSize: Int,
        val recentSumAverage: Double,
        val recentOddAverage: Double,
        val recentLowAverage: Double,
        val recentBucketAverage: Double,
        val recentCarryAverage: Double,
        val longFrequencyMean: Double,
        val recentFrequencyMean: Double,
        val longFrequency: Map<Int, Int>,
        val recentFrequency: Map<Int, Int>,
        val lastSeenGap: Map<Int, Int>,
    )

    private data class ScoredCandidate(
        val numbers: List<Int>,
        val score: Double,
    )

    private enum class CoverageStrategy {
        BALANCED,
        DIVERSIFIED,
    }
}
