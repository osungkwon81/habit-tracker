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
        val analysis = analyze(normalizedHistory)
        val excluded = deriveExcludedNumbers(analysis)
        val frequencyMap = buildFrequencyMap(normalizedHistory)
        val lastDraw = normalizedHistory.first()

        return generateRankedTickets(
            history = normalizedHistory,
            gameCount = gameCount,
            generator = { generateWeightedCombination(analysis, excluded) },
            validator = ::isBalanced,
            scorer = { numbers ->
                scoreCandidate(
                    numbers = numbers,
                    history = normalizedHistory,
                    frequencyMap = frequencyMap,
                    lastDraw = lastDraw,
                    strategyBonus = { candidate ->
                        val recentCount = candidate.count { analysis.score.getValue(it) >= analysis.averageScore }
                        (recentCount * 0.35) - (candidate.count(excluded::contains) * 2.0)
                    },
                )
            },
            commentBuilder = { numbers, score ->
                val overlap = numbers.count(lastDraw::contains)
                "${mode.label} 모드, 후보 ${mode.candidatePoolSize}건 재선별, 점수 ${"%.1f".format(score)}, 이월 ${overlap}개, 제외수 ${excluded.sorted().joinToString(", ")} 반영"
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
        val recentNumbers = normalizedHistory.take(5).flatten().toSet()
        val longGapNumbers = getLongGapNumbers(normalizedHistory, 15)
        val leastFrequentNumbers = getLeastFrequentNumbers(normalizedHistory, 52, 6)
        val excluded = (longGapNumbers + leastFrequentNumbers).toSet()
        val hotNumbers = recentNumbers.filterNot(excluded::contains)
        val coldNumbers = (1..maxNumber).filterNot(recentNumbers::contains).filterNot(excluded::contains)
        val frequencyMap = buildFrequencyMap(normalizedHistory)
        val lastDraw = normalizedHistory.first()

        return generateRankedTickets(
            history = normalizedHistory,
            gameCount = gameCount,
            generator = { generateBalancedCombination(hotNumbers, coldNumbers, excluded) },
            validator = ::isBalanced,
            scorer = { numbers ->
                scoreCandidate(
                    numbers = numbers,
                    history = normalizedHistory,
                    frequencyMap = frequencyMap,
                    lastDraw = lastDraw,
                    strategyBonus = { candidate ->
                        val hotCount = candidate.count(recentNumbers::contains)
                        val coldCount = candidate.count(coldNumbers::contains)
                        val carryCount = candidate.count(lastDraw::contains)
                        (4.0 - abs(hotCount - 3.5) * 1.4) +
                            (2.5 - abs(coldCount - 2.5) * 1.2) -
                            max(0, carryCount - 2) * 1.5
                    },
                )
            },
            commentBuilder = { numbers, score ->
                val hotCount = numbers.count(recentNumbers::contains)
                val coldCount = numbers.count(coldNumbers::contains)
                val carryCount = numbers.count(lastDraw::contains)
                "${mode.label} 모드, 후보 ${mode.candidatePoolSize}건 재선별, 점수 ${"%.1f".format(score)}, 최근수 ${hotCount}개, 미출현 ${coldCount}개, 이월 ${carryCount}개"
            },
            mode = mode,
        )
    }

    private fun analyze(history: List<List<Int>>): Analysis {
        val frequency = (1..maxNumber).associateWith { 0 }.toMutableMap()
        val lastSeenGap = (1..maxNumber).associateWith { history.size }.toMutableMap()

        history.forEachIndexed { index, draw ->
            draw.forEach { number ->
                frequency[number] = frequency.getValue(number) + 1
                lastSeenGap[number] = minOf(lastSeenGap.getValue(number), index)
            }
        }

        val score = (1..maxNumber).associateWith { number ->
            (frequency.getValue(number) * 1.1) + (lastSeenGap.getValue(number) * 0.7)
        }

        return Analysis(score = score)
    }

    private fun deriveExcludedNumbers(analysis: Analysis): Set<Int> {
        return analysis.score.entries
            .sortedBy(Map.Entry<Int, Double>::value)
            .take(6)
            .mapTo(linkedSetOf(), Map.Entry<Int, Double>::key)
    }

    private fun generateWeightedCombination(analysis: Analysis, excluded: Set<Int>): List<Int> {
        val result = linkedSetOf<Int>()
        while (result.size < pickCount) {
            result += weightedPick(analysis, excluded)
        }
        return result.sorted()
    }

    private fun weightedPick(analysis: Analysis, excluded: Set<Int>): Int {
        val weights = IntArray(maxNumber + 1)
        var totalWeight = 0

        for (number in 1..maxNumber) {
            if (number in excluded) continue
            val weight = max((analysis.score.getValue(number) * 100).toInt(), 1)
            weights[number] = weight
            totalWeight += weight
        }

        var target = Random.nextInt(totalWeight)
        for (number in 1..maxNumber) {
            val weight = weights[number]
            if (weight == 0) continue
            target -= weight
            if (target < 0) return number
        }
        return Random.nextInt(1, maxNumber + 1)
    }

    private fun generateBalancedCombination(hotNumbers: List<Int>, coldNumbers: List<Int>, excluded: Set<Int>): List<Int> {
        val result = linkedSetOf<Int>()
        hotNumbers.shuffled().take(3).forEach(result::add)
        coldNumbers.shuffled().take(2).forEach(result::add)

        while (result.size < pickCount) {
            val candidate = Random.nextInt(1, maxNumber + 1)
            if (candidate !in excluded) {
                result += candidate
            }
        }

        return result.sorted()
    }

    private fun isBalanced(numbers: List<Int>): Boolean {
        if (numbers.size != pickCount) return false
        val sum = numbers.sum()
        if (sum !in 95..180) return false
        val oddCount = numbers.count { it % 2 != 0 }
        if (oddCount !in 2..4) return false
        if (maxConsecutiveRun(numbers) >= 3) return false
        return acValue(numbers) >= 7
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

    private fun scoreCandidate(
        numbers: List<Int>,
        history: List<List<Int>>,
        frequencyMap: Map<Int, Int>,
        lastDraw: List<Int>,
        strategyBonus: (List<Int>) -> Double,
    ): Double {
        val sum = numbers.sum()
        val oddCount = numbers.count { it % 2 != 0 }
        val ac = acValue(numbers)
        val decadeBuckets = decadeBucketCount(numbers)
        val overlapWithLast = numbers.count(lastDraw::contains)
        val consecutiveRun = maxConsecutiveRun(numbers)
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
        val frequencyConsensus = numbers.sumOf { frequencyMap.getValue(it).toDouble() } / pickCount
        val frequencyMean = frequencyMap.values.average()
        val centeredSumScore = 20.0 - abs(sum - 140) / 3.0
        val oddEvenScore = 8.0 - abs(oddCount - 3)
        val acScore = ac * 1.4
        val bucketScore = decadeBuckets * 2.0
        val frequencyScore = 10.0 - abs(frequencyConsensus - frequencyMean)
        val historyPenalty = history.count { past -> past.intersect(numbers.toSet()).size >= 4 } * 1.8
        val overlapPenalty = overlapWithLast * 1.7
        val consecutivePenalty = if (consecutiveRun >= 3) 6.0 else if (consecutiveRun == 2) 1.0 else 0.0
        val tailPenalty = if (tailDuplicates >= 3) 4.0 else if (tailDuplicates == 2) 0.5 else 0.0

        return centeredSumScore +
            oddEvenScore +
            acScore +
            bucketScore +
            frequencyScore +
            strategyBonus(numbers) -
            historyPenalty -
            overlapPenalty -
            consecutivePenalty -
            tailPenalty
    }

    private fun buildFrequencyMap(history: List<List<Int>>): Map<Int, Int> {
        val frequency = (1..maxNumber).associateWith { 0 }.toMutableMap()
        history.flatten().forEach { number ->
            frequency[number] = frequency.getValue(number) + 1
        }
        return frequency
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

    private fun getLongGapNumbers(history: List<List<Int>>, weeks: Int): Set<Int> {
        val appeared = history.take(weeks).flatten().toSet()
        return (1..maxNumber).filterNot(appeared::contains).toSet()
    }

    private fun getLeastFrequentNumbers(history: List<List<Int>>, weeks: Int, count: Int): Set<Int> {
        val frequencyMap = (1..maxNumber).associateWith { 0 }.toMutableMap()
        history.take(weeks).flatten().forEach { number ->
            frequencyMap[number] = frequencyMap.getValue(number) + 1
        }
        return frequencyMap.entries.sortedBy { it.value }.take(count).mapTo(linkedSetOf(), Map.Entry<Int, Int>::key)
    }

    private data class Analysis(
        val score: Map<Int, Double>,
    ) {
        val averageScore: Double = score.values.average()
    }

    private data class ScoredCandidate(
        val numbers: List<Int>,
        val score: Double,
    )
}
