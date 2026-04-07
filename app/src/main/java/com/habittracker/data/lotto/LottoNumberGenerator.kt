package com.habittracker.data.lotto

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class LottoGeneratedTicket(
    val numbers: List<Int>,
    val comment: String? = null,
)

object LottoNumberGenerator {
    private const val maxNumber = 45
    private const val pickCount = 6
    private const val defaultGameCount = 5

    fun generateChatGpt(history: List<List<Int>>, gameCount: Int = defaultGameCount): List<LottoGeneratedTicket> {
        if (history.isEmpty()) return emptyList()
        val analysis = analyze(history.map { it.sorted() })
        val excluded = deriveExcludedNumbers(analysis)

        return buildList {
            while (size < gameCount) {
                val candidate = generateWeightedCombination(analysis, excluded)
                if (none { it.numbers == candidate }) {
                    add(
                        LottoGeneratedTicket(
                            numbers = candidate,
                            comment = "\uC81C\uC678\uC218 ${excluded.sorted().joinToString(", ")} \uBC18\uC601",
                        ),
                    )
                }
            }
        }
    }

    fun generateGemini(history: List<List<Int>>, gameCount: Int = defaultGameCount): List<LottoGeneratedTicket> {
        if (history.isEmpty()) return emptyList()

        val recentNumbers = history.take(5).flatten().toSet()
        val longGapNumbers = getLongGapNumbers(history, 15)
        val leastFrequentNumbers = getLeastFrequentNumbers(history, 52, 6)
        val excluded = (longGapNumbers + leastFrequentNumbers).toSet()
        val hotNumbers = recentNumbers.filterNot(excluded::contains)
        val coldNumbers = (1..maxNumber).filterNot(recentNumbers::contains).filterNot(excluded::contains)

        return buildList {
            while (size < gameCount) {
                val candidate = generateBalancedCombination(hotNumbers, coldNumbers, excluded)
                if (isBalanced(candidate) && none { it.numbers == candidate }) {
                    add(
                        LottoGeneratedTicket(
                            numbers = candidate,
                            comment = "\uCD5C\uADFC\uC218 ${candidate.count(recentNumbers::contains)}\uAC1C, \uBE44\uCD9C\uC218 ${candidate.count(coldNumbers::contains)}\uAC1C",
                        ),
                    )
                }
            }
        }
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
        return acValue(numbers) >= 7
    }

    private fun acValue(numbers: List<Int>): Int {
        val diffs = mutableSetOf<Int>()
        for (i in numbers.indices) {
            for (j in i + 1 until numbers.size) {
                diffs += abs(numbers[i] - numbers[j])
            }
        }
        return diffs.size - (numbers.size - 1)
    }

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
    )
}