package com.habittracker.data.lotto

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
        val normalizedHistory = history.map { it.sorted() }
        val analysis = analyze(normalizedHistory)
        val excluded = deriveExcludedNumbers(analysis)

        return buildList {
            while (size < gameCount) {
                val combo = generateChatGptCombination(analysis, excluded)
                if (isChatGptValid(combo) && none { it.numbers == combo }) {
                    add(LottoGeneratedTicket(numbers = combo, comment = "제외수 ${excluded.sorted()} 반영"))
                }
            }
        }
    }

    fun generateGemini(history: List<List<Int>>, gameCount: Int = defaultGameCount): List<LottoGeneratedTicket> {
        if (history.isEmpty()) return emptyList()

        val recent5WeeksNumbers = getRecentNumbers(history, 5)
        val overUsedNumbers = getOverUsedNumbers(history, 3)
        val longTermMissing = getLongTermMissingNumbers(history, 15)
        val leastFrequentNumbers = getLeastFrequentNumbers(history, 52, 6)

        val finalExcluded = (overUsedNumbers + longTermMissing + (leastFrequentNumbers - recent5WeeksNumbers)).toSet()
        val hotNumbers = (recent5WeeksNumbers - finalExcluded).sorted()
        val coldNumbers = (1..45)
            .filterNot(recent5WeeksNumbers::contains)
            .filterNot(longTermMissing::contains)
            .filterNot(leastFrequentNumbers::contains)
        val lastWeekNumbers = history.first()

        return buildList {
            repeat(gameCount) {
                var attempt = 0
                var result: List<Int>
                do {
                    result = generateGeminiCombination(finalExcluded, coldNumbers, hotNumbers, lastWeekNumbers)
                    attempt++
                } while (
                    attempt < 50_000 &&
                    (!validateGeminiStatistics(result) ||
                        containsSimilarLotto(history, result) ||
                        any { it.numbers == result.sorted() })
                )

                val sortedResult = result.sorted()
                val coldCount = sortedResult.count(coldNumbers::contains)
                val hotCount = sortedResult.count(hotNumbers::contains)
                val carryCount = sortedResult.count(lastWeekNumbers::contains)
                add(
                    LottoGeneratedTicket(
                        numbers = sortedResult,
                        comment = "최근수 ${hotCount}개, 이월 ${carryCount}개, 미출현 ${coldCount}개",
                    ),
                )
            }
        }
    }

    private fun deriveExcludedNumbers(analysis: Analysis): Set<Int> {
        return (1..maxNumber)
            .map { number ->
                val freq = analysis.allFreq.getValue(number)
                val gap = analysis.lastSeenGap.getValue(number)
                NumberScore(number = number, score = (freq * 1.0) - (gap * 1.5))
            }
            .sortedBy(NumberScore::score)
            .take(6)
            .mapTo(linkedSetOf(), NumberScore::number)
    }

    private fun generateChatGptCombination(analysis: Analysis, excluded: Set<Int>): List<Int> {
        val result = mutableSetOf<Int>()
        while (result.size < pickCount) {
            val picked = weightedPick(analysis, excluded)
            if (picked !in excluded) {
                result += picked
            }
        }
        return result.sorted()
    }

    private fun weightedPick(analysis: Analysis, excluded: Set<Int>): Int {
        val weights = IntArray(maxNumber + 1)
        var totalWeight = 0

        for (number in 1..maxNumber) {
            if (number in excluded) continue
            val weight = max((analysis.numberScore.getValue(number) * 100).toInt(), 1)
            weights[number] = weight
            totalWeight += weight
        }

        var randomPoint = Random.nextInt(totalWeight)
        for (number in 1..maxNumber) {
            val weight = weights[number]
            if (weight == 0) continue
            randomPoint -= weight
            if (randomPoint < 0) return number
        }

        return Random.nextInt(1, maxNumber + 1)
    }

    private fun isChatGptValid(combo: List<Int>): Boolean {
        val sum = combo.sum()
        if (sum !in 90..185) return false

        val oddCount = combo.count { it % 2 == 1 }
        return oddCount in 2..4
    }

    private fun analyze(history: List<List<Int>>): Analysis {
        val shortFreq = initIntMap()
        val allFreq = initIntMap()
        val lastSeenGap = initIntMap()
        val numberScore = mutableMapOf<Int, Double>()
        val total = history.size

        history.forEachIndexed { index, row ->
            row.forEach { number ->
                allFreq[number] = allFreq.getValue(number) + 1
                if (index < 10) {
                    shortFreq[number] = shortFreq.getValue(number) + 1
                }
            }
        }

        for (number in 1..maxNumber) {
            var gap = total
            history.forEachIndexed outer@{ index, row ->
                if (number in row) {
                    gap = index
                    return@outer
                }
            }
            lastSeenGap[number] = gap
            numberScore[number] = (allFreq.getValue(number) * 1.2) + (gap * 0.8)
        }

        return Analysis(shortFreq = shortFreq, allFreq = allFreq, lastSeenGap = lastSeenGap, numberScore = numberScore)
    }

    private fun initIntMap(): MutableMap<Int, Int> = (1..maxNumber).associateWith { 0 }.toMutableMap()

    private fun generateGeminiCombination(
        excluded: Set<Int>,
        coldNumbers: List<Int>,
        hotNumbers: List<Int>,
        lastWeek: List<Int>,
    ): List<Int> {
        val result = mutableListOf<Int>()
        val targetHotCount = if (Random.nextBoolean()) 3 else 4
        val targetColdCount = pickCount - targetHotCount

        val validLastWeek = lastWeek.filterNot(excluded::contains).shuffled()
        var carryOverCount = 0
        if (validLastWeek.isNotEmpty() && Random.nextBoolean()) {
            result += validLastWeek.first()
            carryOverCount = 1
        }

        hotNumbers.shuffled().filterNot(result::contains).take(targetHotCount - carryOverCount).forEach(result::add)
        coldNumbers.shuffled().filterNot(result::contains).take(targetColdCount).forEach(result::add)

        if (result.size < pickCount) {
            (1..maxNumber)
                .filterNot(excluded::contains)
                .filterNot(result::contains)
                .shuffled()
                .take(pickCount - result.size)
                .forEach(result::add)
        }

        return result.distinct().take(pickCount)
    }

    private fun validateGeminiStatistics(numbers: List<Int>): Boolean {
        if (numbers.size != pickCount) return false

        val sum = numbers.sum()
        if (sum !in 100..175) return false

        val oddCount = numbers.count { it % 2 != 0 }
        if (oddCount !in 2..4) return false

        return calculateAc(numbers) >= 7
    }

    private fun calculateAc(numbers: List<Int>): Int {
        val diffs = mutableSetOf<Int>()
        for (i in numbers.indices) {
            for (j in i + 1 until numbers.size) {
                diffs += kotlin.math.abs(numbers[i] - numbers[j])
            }
        }
        return diffs.size - (numbers.size - 1)
    }

    private fun getLongTermMissingNumbers(history: List<List<Int>>, weeks: Int): Set<Int> {
        val appeared = history.take(weeks).flatten().toSet()
        return (1..maxNumber).filterNot(appeared::contains).toSet()
    }

    private fun getRecentNumbers(history: List<List<Int>>, weeks: Int): Set<Int> =
        history.take(weeks).flatten().toSet()

    private fun getOverUsedNumbers(history: List<List<Int>>, threshold: Int): Set<Int> =
        history.take(5)
            .flatten()
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= threshold }
            .keys

    private fun getLeastFrequentNumbers(history: List<List<Int>>, weeks: Int, count: Int): Set<Int> {
        val frequencyMap = (1..maxNumber).associateWith { 0 }.toMutableMap()
        history.take(weeks).flatten().forEach { number ->
            frequencyMap[number] = frequencyMap.getValue(number) + 1
        }
        return frequencyMap.entries
            .sortedBy { it.value }
            .take(count)
            .mapTo(linkedSetOf(), Map.Entry<Int, Int>::key)
    }

    private fun containsSimilarLotto(history: List<List<Int>>, generated: List<Int>): Boolean {
        val generatedSet = generated.toSet()
        return history.any { past ->
            past.count(generatedSet::contains) >= 5
        }
    }

    private data class Analysis(
        val shortFreq: Map<Int, Int>,
        val allFreq: Map<Int, Int>,
        val lastSeenGap: Map<Int, Int>,
        val numberScore: Map<Int, Double>,
    )

    private data class NumberScore(
        val number: Int,
        val score: Double,
    )
}
