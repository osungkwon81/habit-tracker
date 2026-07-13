package com.habittracker.data.lotto

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

data class LottoGeneratedTicket(
    val numbers: List<Int>,
    val comment: String? = null,
    val score: LottoAnalysisScore? = null,
    val generationMode: String? = null,
)

data class LottoAnalysisScore(
    val totalScore: Double,
    val dataScore: Double,
    val patternScore: Double,
    val distributionScore: Double,
    val avoidanceScore: Double,
    val validationScore: Double,
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
    const val CURRENT_GENERATION_VERSION = "2026-07-13-v2"

    private const val maxNumber = 45
    private const val pickCount = 6
    private const val defaultGameCount = 5
    private const val minimumBacktestTrainingDraws = 36
    private const val minimumBacktestSamples = 24
    private const val backtestRandomCandidateCount = 48
    private const val backtestSampleCount = 60
    private val baseAppearanceRate = pickCount.toDouble() / maxNumber
    private const val historyAnalysisMaximumScore = 24.9
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
                scoreCandidate(
                    numbers = numbers,
                    trendProfile = trendProfile,
                    lastDraw = lastDraw,
                    strategy = CoverageStrategy.BALANCED,
                )
            },
            commentBuilder = { numbers, score ->
                val overlap = numbers.count(lastDraw::contains)
                "${mode.label} 모드 · 분석 ${formatScore(score.totalScore)} · 데이터 ${formatScore(score.dataScore)} · " +
                    "패턴 ${formatScore(score.patternScore)} · 균형 ${formatScore(score.distributionScore)} · " +
                    "공동당첨회피 ${formatScore(score.avoidanceScore)} · 과거검증 ${validationLabel(trendProfile.backtestProfile)} · " +
                    "직전겹침 ${overlap}개"
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
            validator = ::isDiversifiedCandidate,
            scorer = { numbers ->
                scoreCandidate(
                    numbers = numbers,
                    trendProfile = trendProfile,
                    lastDraw = lastDraw,
                    strategy = CoverageStrategy.DIVERSIFIED,
                )
            },
            commentBuilder = { numbers, score ->
                val carryCount = numbers.count(lastDraw::contains)
                "${mode.label} 모드 · 분석 ${formatScore(score.totalScore)} · 데이터 ${formatScore(score.dataScore)} · " +
                    "패턴 ${formatScore(score.patternScore)} · 분산 ${formatScore(score.distributionScore)} · " +
                    "공동당첨회피 ${formatScore(score.avoidanceScore)} · 과거검증 ${validationLabel(trendProfile.backtestProfile)} · " +
                    "이월 ${carryCount}개"
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

    private fun buildTrendProfile(history: List<List<Int>>, includeBacktest: Boolean = true): TrendProfile {
        val recentWindow = history.take(minOf(30, history.size)).ifEmpty { history }
        val longFrequency = buildFrequencyMap(history)
        val longPairFrequency = buildPairFrequencyMap(history)
        val historyAnalysis = buildHistoryAnalysisProfile(history)
        val lastSeenGap = buildLastSeenGap(history)

        return TrendProfile(
            recentSumAverage = recentWindow.map(List<Int>::sum).average(),
            recentOddAverage = recentWindow.map { draw -> draw.count { it % 2 != 0 } }.average(),
            recentLowAverage = recentWindow.map { draw -> draw.count { it <= 22 } }.average(),
            recentBucketAverage = recentWindow.map(::decadeBucketCount).average(),
            numberEvidence = buildNumberEvidence(history),
            pairEvidence = buildPairEvidence(history, longFrequency, longPairFrequency),
            gapEvidence = buildGapEvidence(history, lastSeenGap),
            transitionProfile = buildTransitionProfile(history),
            historyAnalysis = historyAnalysis,
            backtestProfile = if (includeBacktest) buildBacktestProfile(history) else BacktestProfile(),
        )
    }

    private fun generateRandomCombination(randomSource: Random = random): List<Int> {
        val selected = mutableSetOf<Int>()
        while (selected.size < pickCount) {
            selected += randomSource.nextInt(maxNumber) + 1
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
        val numberFit = (0.72 + trendProfile.numberEvidence.getValue(number) / 180.0).coerceIn(0.72, 1.28)
        val gapFit = (0.86 + trendProfile.gapEvidence.getValue(number) / 360.0).coerceIn(0.86, 1.14)
        val highNumberFit = when {
            number >= 32 && strategy == CoverageStrategy.DIVERSIFIED -> 1.18
            else -> 1.0
        }

        return numberFit * gapFit * highNumberFit * selectedPairEvidenceFit(number, selected, trendProfile)
    }

    private fun selectedPairEvidenceFit(
        number: Int,
        selected: Set<Int>,
        trendProfile: TrendProfile,
    ): Double {
        if (selected.isEmpty()) return 1.0

        val averageEvidence = selected.map { picked ->
            val pair = if (number < picked) number to picked else picked to number
            trendProfile.pairEvidence.getValue(pair)
        }.average()
        return (0.78 + averageEvidence / 230.0).coerceIn(0.78, 1.22)
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
        if (highCount > 4) return false
        val middleCount = numbers.count { it in 16..30 }
        if (middleCount !in 1..4) return false
        val spread = numbers.last() - numbers.first()
        if (spread !in 18..42) return false
        if (numberVariance(numbers) !in 45.0..230.0) return false
        if (decadeBucketCount(numbers) < 3) return false
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
        if (tailDuplicates > 3) return false
        if (maxConsecutiveRun(numbers) > 3) return false
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
        if (lowCount !in 1..5) return false
        if (highCount !in 0..4) return false
        if (bucketCounts.values.any { it > 2 }) return false
        return numberVariance(numbers) in 65.0..190.0
    }

    private fun isDiversifiedCandidate(numbers: List<Int>): Boolean {
        if (!isBaseCoverageCandidate(numbers)) return false
        val highCount = numbers.count { it >= 32 }
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1

        if (highCount < 2) return false
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
        scorer: (List<Int>) -> CandidateScore,
        commentBuilder: (List<Int>, CandidateScore) -> String,
        mode: LottoGenerationMode,
        strategy: CoverageStrategy,
    ): List<LottoGeneratedTicket> {
        val candidates = linkedSetOf<List<Int>>()
        val maxAttempts = mode.candidatePoolSize * 20
        var attempt = 0

        while (candidates.size < mode.candidatePoolSize && attempt < maxAttempts) {
            val candidate = generator().sorted()
            if (validator(candidate) && !isHistoricalDuplicate(candidate, history)) {
                candidates += candidate
            }
            attempt++
        }

        val scored = candidates
            .map { numbers -> ScoredCandidate(numbers = numbers, score = scorer(numbers)) }
            .sortedByDescending { candidate -> candidate.score.totalScore }
            .take(mode.finalistPoolSize)

        return pickDiverseTopGames(scored, gameCount, strategy).map { candidate ->
            LottoGeneratedTicket(
                numbers = candidate.numbers,
                comment = commentBuilder(candidate.numbers, candidate.score),
                score = candidate.score.toAnalysisScore(),
                generationMode = mode.name,
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
                candidate.score.totalScore +
                    candidate.score.avoidanceScore * strategy.selectionAvoidanceWeight +
                    newCoverage - overlapPenalty - repeatedNumberPenalty - repeatedPairPenalty + spacingBonus
            } ?: break
            selected += next
            remaining.remove(next)
        }

        return selected.take(gameCount)
    }

    private fun scoreCandidate(
        numbers: List<Int>,
        trendProfile: TrendProfile,
        lastDraw: List<Int>,
        strategy: CoverageStrategy,
    ): CandidateScore {
        val pairs = drawPairs(numbers)
        val numberScore = numbers.map { trendProfile.numberEvidence.getValue(it) }.average()
        val pairScore = pairs.map { trendProfile.pairEvidence.getValue(it) }.average()
        val gapScore = numbers.map { trendProfile.gapEvidence.getValue(it) }.average()
        val dataScore = (numberScore * 0.55 + pairScore * 0.30 + gapScore * 0.15).coerceIn(0.0, 100.0)
        val historyPatternScore =
            (scoreHistoryAnalysisCandidate(numbers, trendProfile.historyAnalysis) / historyAnalysisMaximumScore * 100.0)
                .coerceIn(0.0, 100.0)
        val transitionScore = scoreTransitionPattern(numbers, lastDraw, trendProfile.transitionProfile)
        val patternScore = (historyPatternScore * 0.65 + transitionScore * 0.35).coerceIn(0.0, 100.0)
        val distributionScore = scoreDistribution(numbers, trendProfile, strategy)
        val avoidanceScore = (50.0 + publicPickAvoidanceScore(numbers) * 5.0).coerceIn(0.0, 100.0)
        val totalScore = (
            dataScore * strategy.dataWeight +
                patternScore * strategy.patternWeight +
                distributionScore * strategy.distributionWeight
            ).coerceIn(0.0, 100.0)

        return CandidateScore(
            totalScore = totalScore,
            dataScore = dataScore,
            patternScore = patternScore,
            distributionScore = distributionScore,
            avoidanceScore = avoidanceScore,
            validationScore = trendProfile.backtestProfile.averagePercentile,
        )
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

    private fun buildNumberEvidence(history: List<List<Int>>): Map<Int, Double> {
        val windows = listOf(
            EvidenceWindow(size = 10, weight = 0.40, priorDraws = 18.0),
            EvidenceWindow(size = 30, weight = 0.30, priorDraws = 28.0),
            EvidenceWindow(size = 90, weight = 0.20, priorDraws = 45.0),
            EvidenceWindow(size = history.size, weight = 0.10, priorDraws = 80.0),
        )

        return (1..maxNumber).associateWith { number ->
            val estimatedRate = windows.sumOf { window ->
                val draws = history.take(minOf(window.size, history.size))
                val occurrenceCount = draws.count { number in it }
                val smoothedRate =
                    (occurrenceCount + window.priorDraws * baseAppearanceRate) /
                        (draws.size + window.priorDraws)
                smoothedRate * window.weight
            }
            (50.0 + (estimatedRate / baseAppearanceRate - 1.0) * 120.0).coerceIn(0.0, 100.0)
        }
    }

    private fun buildPairEvidence(
        history: List<List<Int>>,
        numberFrequency: Map<Int, Int>,
        pairFrequency: Map<Pair<Int, Int>, Int>,
    ): Map<Pair<Int, Int>, Double> {
        if (history.isEmpty()) return pairFrequency.mapValues { 50.0 }
        val withinDrawCorrection =
            maxNumber.toDouble() * (pickCount - 1) / (pickCount * (maxNumber - 1))

        return pairFrequency.mapValues { (pair, observedCount) ->
            val expectedCount =
                numberFrequency.getValue(pair.first).toDouble() *
                    numberFrequency.getValue(pair.second) /
                    history.size * withinDrawCorrection
            val smoothedLift = (observedCount + 2.0) / (expectedCount + 2.0)
            (50.0 + ln(smoothedLift) * 28.0).coerceIn(0.0, 100.0)
        }
    }

    private fun buildGapEvidence(
        history: List<List<Int>>,
        currentGaps: Map<Int, Int>,
    ): Map<Int, Double> {
        val intervalCounts = mutableMapOf<Int, Int>()
        val lastIndexes = mutableMapOf<Int, Int>()
        history.asReversed().forEachIndexed { index, draw ->
            draw.forEach { number ->
                lastIndexes[number]?.let { previousIndex ->
                    val interval = index - previousIndex
                    intervalCounts[interval] = intervalCounts.getOrDefault(interval, 0) + 1
                }
                lastIndexes[number] = index
            }
        }
        val intervals = intervalCounts.entries.flatMap { (interval, count) -> List(count) { interval } }

        return (1..maxNumber).associateWith { number ->
            val targetInterval = currentGaps.getValue(number) + 1
            val events = intervalCounts.getOrDefault(targetInterval, 0)
            val survived =
                intervals.count { it >= targetInterval } +
                    currentGaps.values.count { currentGap -> currentGap + 1 >= targetInterval }
            val estimatedHazard = (events + 20.0 * baseAppearanceRate) / (survived + 20.0)
            (50.0 + (estimatedHazard / baseAppearanceRate - 1.0) * 35.0).coerceIn(0.0, 100.0)
        }
    }

    private fun buildTransitionProfile(history: List<List<Int>>): TransitionProfile {
        if (history.size < 2) return TransitionProfile()
        val observations = (0 until history.lastIndex).map { targetIndex ->
            val target = history[targetIndex]
            val previous = history[targetIndex + 1]
            TransitionRecord(
                previousShape = drawShape(previous),
                targetPattern = drawPattern(target, previous),
            )
        }
        val currentShape = drawShape(history.first())
        val conditional = observations.filter { it.previousShape == currentShape }.map(TransitionRecord::targetPattern)
        val all = observations.map(TransitionRecord::targetPattern)
        return TransitionProfile(
            conditionalSampleCount = conditional.size,
            totalSampleCount = all.size,
            conditional = buildPatternCounts(conditional),
            all = buildPatternCounts(all),
        )
    }

    private fun buildPatternCounts(patterns: List<DrawPattern>): PatternCounts = PatternCounts(
        sumBuckets = patterns.groupingBy(DrawPattern::sumBucket).eachCount(),
        oddCounts = patterns.groupingBy(DrawPattern::oddCount).eachCount(),
        lowCounts = patterns.groupingBy(DrawPattern::lowCount).eachCount(),
        bucketCounts = patterns.groupingBy(DrawPattern::bucketCount).eachCount(),
        carryCounts = patterns.groupingBy(DrawPattern::carryCount).eachCount(),
    )

    private fun drawShape(numbers: List<Int>): DrawShape = DrawShape(
        sumBand = when {
            numbers.sum() < 120 -> 0
            numbers.sum() < 160 -> 1
            else -> 2
        },
        oddBand = when (numbers.count { it % 2 != 0 }) {
            in 0..2 -> 0
            3 -> 1
            else -> 2
        },
    )

    private fun drawPattern(numbers: List<Int>, previousDraw: List<Int>): DrawPattern = DrawPattern(
        sumBucket = numbers.sum() / 10,
        oddCount = numbers.count { it % 2 != 0 },
        lowCount = numbers.count { it <= 22 },
        bucketCount = decadeBucketCount(numbers),
        carryCount = numbers.count(previousDraw::contains),
    )

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
        val percentiles = mutableListOf<Double>()
        val maxSamples = minOf(backtestSampleCount, history.size - minimumBacktestTrainingDraws)
        if (maxSamples <= 0) return BacktestProfile()

        for (targetIndex in 0 until maxSamples) {
            val trainingHistory = history.drop(targetIndex + 1)
            if (trainingHistory.size < minimumBacktestTrainingDraws) continue
            val trainingProfile = buildTrendProfile(trainingHistory, includeBacktest = false)
            val lastTrainingDraw = trainingHistory.first()
            val actualCandidateScore = scoreCandidate(
                numbers = history[targetIndex],
                trendProfile = trainingProfile,
                lastDraw = lastTrainingDraw,
                strategy = CoverageStrategy.BALANCED,
            )
            val actualScore = actualCandidateScore.dataScore * 0.60 + actualCandidateScore.patternScore * 0.40
            val baselineRandom = Random(seed = targetIndex * 10_007 + history[targetIndex].sum() * 97)
            val randomScores = List(backtestRandomCandidateCount) {
                val randomCandidateScore = scoreCandidate(
                    numbers = generateRandomCombination(baselineRandom),
                    trendProfile = trainingProfile,
                    lastDraw = lastTrainingDraw,
                    strategy = CoverageStrategy.BALANCED,
                )
                randomCandidateScore.dataScore * 0.60 + randomCandidateScore.patternScore * 0.40
            }
            percentiles += randomScores.count { it <= actualScore }.toDouble() / randomScores.size * 100.0
        }

        if (percentiles.size < minimumBacktestSamples) return BacktestProfile(sampleCount = percentiles.size)
        return BacktestProfile(
            sampleCount = percentiles.size,
            averagePercentile = percentiles.average(),
            aboveRandomRate = percentiles.count { it > 50.0 }.toDouble() / percentiles.size * 100.0,
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

    private fun patternBucketScore(buckets: Map<Int, Int>, bucket: Int): Double {
        val maxCount = buckets.values.maxOrNull()?.takeIf { it > 0 } ?: return 0.0
        val weightedCount =
            buckets.getOrDefault(bucket, 0).toDouble() +
                buckets.getOrDefault(bucket - 1, 0) * 0.45 +
                buckets.getOrDefault(bucket + 1, 0) * 0.45
        return weightedCount / (maxCount * 1.9)
    }

    private fun validationLabel(profile: BacktestProfile): String {
        if (profile.sampleCount < minimumBacktestSamples) return "표본부족"
        val level = when {
            profile.averagePercentile >= 55.0 -> "기준상회"
            profile.averagePercentile >= 47.0 -> "기준유사"
            else -> "기준미달"
        }
        return "$level ${formatScore(profile.averagePercentile)}/50 · 우위 ${formatScore(profile.aboveRandomRate)}%"
    }

    private fun formatScore(score: Double): String = "%.1f".format(score)

    private fun scoreTransitionPattern(
        numbers: List<Int>,
        lastDraw: List<Int>,
        profile: TransitionProfile,
    ): Double {
        if (profile.totalSampleCount == 0 || profile.conditionalSampleCount == 0) return 50.0
        val pattern = drawPattern(numbers, lastDraw)
        val componentScores = listOf(
            transitionLiftScore(profile.conditional.sumBuckets, profile.all.sumBuckets, pattern.sumBucket, profile),
            transitionLiftScore(profile.conditional.oddCounts, profile.all.oddCounts, pattern.oddCount, profile),
            transitionLiftScore(profile.conditional.lowCounts, profile.all.lowCounts, pattern.lowCount, profile),
            transitionLiftScore(profile.conditional.bucketCounts, profile.all.bucketCounts, pattern.bucketCount, profile),
            transitionLiftScore(profile.conditional.carryCounts, profile.all.carryCounts, pattern.carryCount, profile),
        )
        val reliability = profile.conditionalSampleCount.toDouble() / (profile.conditionalSampleCount + 24.0)
        return (50.0 + (componentScores.average() - 50.0) * reliability).coerceIn(0.0, 100.0)
    }

    private fun transitionLiftScore(
        conditionalCounts: Map<Int, Int>,
        allCounts: Map<Int, Int>,
        value: Int,
        profile: TransitionProfile,
    ): Double {
        val categoryCount = maxOf(conditionalCounts.keys.size, allCounts.keys.size, 1) + 1
        val conditionalRate =
            (conditionalCounts.getOrDefault(value, 0) + 1.0) /
                (profile.conditionalSampleCount + categoryCount)
        val overallRate =
            (allCounts.getOrDefault(value, 0) + 1.0) /
                (profile.totalSampleCount + categoryCount)
        return (50.0 + ln(conditionalRate / overallRate) * 18.0).coerceIn(0.0, 100.0)
    }

    private fun scoreDistribution(
        numbers: List<Int>,
        trendProfile: TrendProfile,
        strategy: CoverageStrategy,
    ): Double {
        val spread = numbers.last() - numbers.first()
        val variance = numberVariance(numbers)
        val oddCount = numbers.count { it % 2 != 0 }
        val lowCount = numbers.count { it <= 22 }
        val bucketCount = decadeBucketCount(numbers)

        return when (strategy) {
            CoverageStrategy.BALANCED -> {
                val profile = trendProfile.historyAnalysis
                listOf(
                    fitScore(numbers.sum().toDouble(), trendProfile.recentSumAverage, 52.0),
                    fitScore(spread.toDouble(), profile.spreadAverage, 18.0),
                    fitScore(variance, profile.varianceAverage, 95.0),
                    fitScore(oddCount.toDouble(), trendProfile.recentOddAverage, 3.0),
                    fitScore(lowCount.toDouble(), trendProfile.recentLowAverage, 3.0),
                    fitScore(bucketCount.toDouble(), trendProfile.recentBucketAverage, 2.0),
                    (50.0 + lowMiddleHighBalanceScore(numbers) * 15.0).coerceIn(0.0, 100.0),
                ).average()
            }
            CoverageStrategy.DIVERSIFIED -> {
                val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
                listOf(
                    ((spread - 18.0) / 24.0 * 100.0).coerceIn(0.0, 100.0),
                    ((variance - 60.0) / 150.0 * 100.0).coerceIn(0.0, 100.0),
                    ((bucketCount - 2.0) / 3.0 * 100.0).coerceIn(0.0, 100.0),
                    ((acValue(numbers) - 3.0) / 7.0 * 100.0).coerceIn(0.0, 100.0),
                    (numbers.count { it >= 32 } / 3.0 * 100.0).coerceIn(0.0, 100.0),
                    (100.0 - maxOf(0, tailDuplicates - 1) * 35.0).coerceIn(0.0, 100.0),
                ).average()
            }
        }
    }

    private fun fitScore(value: Double, target: Double, tolerance: Double): Double =
        (100.0 - abs(value - target) / tolerance * 100.0).coerceIn(0.0, 100.0)

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

    private fun isHistoricalDuplicate(numbers: List<Int>, history: List<List<Int>>): Boolean =
        history.any { past -> past == numbers }

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
        val recentSumAverage: Double,
        val recentOddAverage: Double,
        val recentLowAverage: Double,
        val recentBucketAverage: Double,
        val numberEvidence: Map<Int, Double>,
        val pairEvidence: Map<Pair<Int, Int>, Double>,
        val gapEvidence: Map<Int, Double>,
        val transitionProfile: TransitionProfile,
        val historyAnalysis: HistoryAnalysisProfile,
        val backtestProfile: BacktestProfile,
    )

    private data class EvidenceWindow(
        val size: Int,
        val weight: Double,
        val priorDraws: Double,
    )

    private data class DrawShape(
        val sumBand: Int,
        val oddBand: Int,
    )

    private data class DrawPattern(
        val sumBucket: Int,
        val oddCount: Int,
        val lowCount: Int,
        val bucketCount: Int,
        val carryCount: Int,
    )

    private data class TransitionRecord(
        val previousShape: DrawShape,
        val targetPattern: DrawPattern,
    )

    private data class PatternCounts(
        val sumBuckets: Map<Int, Int> = emptyMap(),
        val oddCounts: Map<Int, Int> = emptyMap(),
        val lowCounts: Map<Int, Int> = emptyMap(),
        val bucketCounts: Map<Int, Int> = emptyMap(),
        val carryCounts: Map<Int, Int> = emptyMap(),
    )

    private data class TransitionProfile(
        val conditionalSampleCount: Int = 0,
        val totalSampleCount: Int = 0,
        val conditional: PatternCounts = PatternCounts(),
        val all: PatternCounts = PatternCounts(),
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
        val averagePercentile: Double = 50.0,
        val aboveRandomRate: Double = 0.0,
    )

    private data class CandidateScore(
        val totalScore: Double,
        val dataScore: Double,
        val patternScore: Double,
        val distributionScore: Double,
        val avoidanceScore: Double,
        val validationScore: Double,
    ) {
        fun toAnalysisScore(): LottoAnalysisScore = LottoAnalysisScore(
            totalScore = totalScore,
            dataScore = dataScore,
            patternScore = patternScore,
            distributionScore = distributionScore,
            avoidanceScore = avoidanceScore,
            validationScore = validationScore,
        )
    }

    private data class ScoredCandidate(
        val numbers: List<Int>,
        val score: CandidateScore,
    )

    private enum class CoverageStrategy(
        val selectionOverlapWeight: Double,
        val repeatedNumberPenalty: Double,
        val repeatedPairPenalty: Double,
        val newCoverageWeight: Double,
        val bucketBonusWeight: Double,
        val selectionAvoidanceWeight: Double,
        val dataWeight: Double,
        val patternWeight: Double,
        val distributionWeight: Double,
    ) {
        BALANCED(
            selectionOverlapWeight = 1.0,
            repeatedNumberPenalty = 0.7,
            repeatedPairPenalty = 1.3,
            newCoverageWeight = 1.0,
            bucketBonusWeight = 0.45,
            selectionAvoidanceWeight = 0.0,
            dataWeight = 0.45,
            patternWeight = 0.35,
            distributionWeight = 0.20,
        ),
        DIVERSIFIED(
            selectionOverlapWeight = 1.45,
            repeatedNumberPenalty = 1.15,
            repeatedPairPenalty = 2.25,
            newCoverageWeight = 1.55,
            bucketBonusWeight = 0.7,
            selectionAvoidanceWeight = 0.08,
            dataWeight = 0.40,
            patternWeight = 0.20,
            distributionWeight = 0.40,
        ),
    }
}
