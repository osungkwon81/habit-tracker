package com.habittracker.data.lotto

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

data class LottoGeneratedTicket(
    val numbers: List<Int>,
    val comment: String? = null,
    val score: LottoAnalysisScore? = null,
    val generationMode: String? = null,
    val generationSeed: Long? = null,
    val featureSnapshotJson: String? = null,
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
    // 사용자에게 노출하는 생성기 버전이다. 세부 설정 차이는 저장된 config hash로 구분한다.
    const val CURRENT_GENERATION_VERSION = "2026-08-04-v3"
    const val CURRENT_FEATURE_SNAPSHOT_SCHEMA_VERSION = 4

    private const val maxNumber = 45
    private const val pickCount = 6
    private const val defaultGameCount = 5
    private const val minimumBacktestTrainingDraws = 36
    private const val minimumBacktestSamples = 24
    private const val backtestRandomCandidateCount = 48
    private const val backtestStrategyCandidateCount = 48
    private const val backtestSampleCount = 60
    private const val minimumBacktestWeightTrainingSamples = 12
    private const val minimumBacktestHoldoutSamples = 12
    private const val adaptiveMinimumTrainingDraws = 45
    private const val adaptiveMinimumEvaluationRounds = 240
    private const val adaptiveMinimumOpportunities = 600
    private const val adaptivePriorOpportunities = 900.0
    private const val adaptiveSignificanceZ = 2.40
    private const val adaptiveMaximumLift = 0.08
    private const val adaptiveSegmentCount = 3
    private const val recentSumWindow = 30
    private const val minimumSumImprovementRate = 0.01
    private val baseAppearanceRate = pickCount.toDouble() / maxNumber
    private val theoreticalSumAverage = pickCount * (maxNumber + 1) / 2.0
    private val gapThresholds = listOf(5, 10, 15)
    private const val historyAnalysisMaximumScore = 27.4
    private const val maximumBacktestWeightAdjustment = 0.10
    private const val previousDrawTwoMatchPenalty = 4.0
    private const val previousDrawThreeMatchPenalty = 12.0
    private const val previousDrawFourPlusMatchPenalty = 20.0
    private const val maximumPreviousDrawTwoPlusGamesPerBatch = 1
    private val random = Random.Default

    fun generateBalanced(
        history: List<List<Int>>,
        gameCount: Int = defaultGameCount,
        mode: LottoGenerationMode = LottoGenerationMode.BASIC,
        seed: Long = random.nextLong(),
    ): List<LottoGeneratedTicket> {
        if (history.isEmpty()) return emptyList()
        val randomSource = Random(seed)
        val normalizedHistory = history.map { it.sorted() }
        val trendProfile = buildTrendProfile(
            history = normalizedHistory,
            backtestStrategy = CoverageStrategy.BALANCED,
        )
        val lastDraw = normalizedHistory.first()

        return generateRankedTickets(
            history = normalizedHistory,
            gameCount = gameCount,
            generator = {
                generatePredictedCombination(
                    trendProfile = trendProfile,
                    strategy = CoverageStrategy.BALANCED,
                    randomSource = randomSource,
                )
            },
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
                "${mode.label} 모드 · 적합 ${formatScore(score.totalScore)} · 데이터 ${formatScore(score.dataScore)} · " +
                    "패턴 ${formatScore(score.patternScore)} · 균형 ${formatScore(score.distributionScore)} · " +
                    "공동당첨회피 ${formatScore(score.avoidanceScore)} · 과거검증 ${validationLabel(trendProfile.backtestProfile)} · " +
                    "직전겹침 ${overlap}개"
            },
            mode = mode,
            strategy = CoverageStrategy.BALANCED,
            lastDraw = lastDraw,
            generationSeed = seed,
        )
    }

    fun generateDiversified(
        history: List<List<Int>>,
        gameCount: Int = defaultGameCount,
        mode: LottoGenerationMode = LottoGenerationMode.BASIC,
        seed: Long = random.nextLong(),
    ): List<LottoGeneratedTicket> {
        if (history.isEmpty()) return emptyList()

        val randomSource = Random(seed)
        val normalizedHistory = history.map { it.sorted() }
        val trendProfile = buildTrendProfile(
            history = normalizedHistory,
            backtestStrategy = CoverageStrategy.DIVERSIFIED,
        )
        val lastDraw = normalizedHistory.first()

        return generateRankedTickets(
            history = normalizedHistory,
            gameCount = gameCount,
            generator = {
                generatePredictedCombination(
                    trendProfile = trendProfile,
                    strategy = CoverageStrategy.DIVERSIFIED,
                    randomSource = randomSource,
                )
            },
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
                "${mode.label} 모드 · 적합 ${formatScore(score.totalScore)} · 데이터 ${formatScore(score.dataScore)} · " +
                    "패턴 ${formatScore(score.patternScore)} · 분산 ${formatScore(score.distributionScore)} · " +
                    "공동당첨회피 ${formatScore(score.avoidanceScore)} · 과거검증 ${validationLabel(trendProfile.backtestProfile)} · " +
                    "이월 ${carryCount}개"
            },
            mode = mode,
            strategy = CoverageStrategy.DIVERSIFIED,
            lastDraw = lastDraw,
            generationSeed = seed,
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

    fun generateRandomControl(
        gameCount: Int = defaultGameCount,
        seed: Long = random.nextLong(),
    ): List<LottoGeneratedTicket> {
        val randomSource = Random(seed)
        val tickets = linkedSetOf<List<Int>>()
        while (tickets.size < gameCount) {
            tickets += generateRandomCombination(randomSource)
        }
        return tickets.map { numbers ->
            LottoGeneratedTicket(
                numbers = numbers,
                comment = "완전 무작위 대조군",
                generationMode = "CONTROL",
                generationSeed = seed,
            )
        }
    }

    fun configurationSnapshot(): String =
        """
        {
          "snapshotSchema": 2,
          "generationVersion": "$CURRENT_GENERATION_VERSION",
          "ticketFeatureSnapshotSchema": $CURRENT_FEATURE_SNAPSHOT_SCHEMA_VERSION,
          "numberRange": [1, $maxNumber],
          "pickCount": $pickCount,
          "defaultGameCount": $defaultGameCount,
          "candidateMaxAttemptMultiplier": 20,
          "history": {
            "recentWindow": $recentSumWindow,
            "analysisWindow": 180,
            "minimumBacktestTrainingDraws": $minimumBacktestTrainingDraws,
            "minimumBacktestSamples": $minimumBacktestSamples,
            "backtestRandomCandidateCount": $backtestRandomCandidateCount,
            "backtestStrategyCandidateCount": $backtestStrategyCandidateCount,
            "backtestSampleCount": $backtestSampleCount,
            "historyAnalysisMaximumScore": $historyAnalysisMaximumScore,
            "evidenceWindows": [
              {"size": 10, "weight": 0.40, "priorDraws": 18.0},
              {"size": 30, "weight": 0.30, "priorDraws": 28.0},
              {"size": 90, "weight": 0.20, "priorDraws": 45.0},
              {"size": "ALL", "weight": 0.10, "priorDraws": 80.0}
            ]
          },
          "modes": {
            "FAST": {"candidatePoolSize": ${LottoGenerationMode.FAST.candidatePoolSize}, "finalistPoolSize": ${LottoGenerationMode.FAST.finalistPoolSize}},
            "BASIC": {"candidatePoolSize": ${LottoGenerationMode.BASIC.candidatePoolSize}, "finalistPoolSize": ${LottoGenerationMode.BASIC.finalistPoolSize}},
            "PRECISE": {"candidatePoolSize": ${LottoGenerationMode.PRECISE.candidatePoolSize}, "finalistPoolSize": ${LottoGenerationMode.PRECISE.finalistPoolSize}}
          },
          "baseFilter": {
            "sum": [65, 215],
            "oddCount": [0, 6],
            "lowNumberMax": 22,
            "lowCount": [0, 6],
            "highNumberMin": 32,
            "highCountMax": 5,
            "middleRange": [16, 30],
            "middleCount": [0, 5],
            "spread": [13, 44],
            "variance": [20.0, 290.0],
            "minimumDecadeBuckets": 2,
            "maximumSameTailCount": 3,
            "maximumConsecutiveRun": 3,
            "minimumAcValue": 3
          },
          "weightedSelection": {
            "minimumWeight": 0.05,
            "numberFit": {"base": 0.72, "evidenceDivisor": 180.0, "range": [0.72, 1.28]},
            "gapFit": {"inactiveWeight": 1.0, "maximumVerifiedLift": $adaptiveMaximumLift},
            "pairFit": {"base": 0.78, "evidenceDivisor": 230.0, "range": [0.78, 1.22]},
            "diversifiedHighNumberMinimum": 32,
            "diversifiedHighNumberMultiplier": 1.05
          },
          "evidence": {
            "numberRateCenter": 50.0,
            "numberRateScale": 120.0,
            "pairSmoothing": 2.0,
            "pairLogScale": 28.0,
            "transitionPrior": 24.0,
            "transitionLogScale": 18.0,
            "shapeProfiles": ["sortedPosition", "adjacentGapPosition", "consecutivePairCount"]
          },
          "adaptiveValidation": {
            "historyBasis": "MAIN_ONLY",
            "gapThresholds": [5, 10, 15],
            "minimumTrainingDraws": $adaptiveMinimumTrainingDraws,
            "minimumEvaluationRounds": $adaptiveMinimumEvaluationRounds,
            "minimumOpportunities": $adaptiveMinimumOpportunities,
            "priorOpportunities": $adaptivePriorOpportunities,
            "segmentCount": $adaptiveSegmentCount,
            "significanceZ": $adaptiveSignificanceZ,
            "sum": {
              "recentWindow": $recentSumWindow,
              "fixedAverage": $theoreticalSumAverage,
              "minimumImprovementRate": $minimumSumImprovementRate,
              "inactiveBehavior": "STRUCTURAL_ONLY"
            }
          },
          "scoreComposition": {
            "data": {"number": 0.55, "pair": 0.30, "gap": 0.15},
            "pattern": {"history": 0.65, "transition": 0.35},
            "backtest": {"data": 0.60, "pattern": 0.40},
            "backtestStrategyMatched": true,
            "backtestEligibleCandidateBaseline": true,
            "backtestWeightCalibration": {
              "components": ["data", "pattern", "distribution"],
              "minimumSamples": $minimumBacktestSamples,
              "minimumWeightTrainingSamples": $minimumBacktestWeightTrainingSamples,
              "minimumHoldoutSamples": $minimumBacktestHoldoutSamples,
              "maximumAdjustment": $maximumBacktestWeightAdjustment,
              "normalized": true,
              "walkForwardGamesPerGroup": $defaultGameCount,
              "applyRule": "holdout_not_worse_than_base_and_control"
            },
            "avoidanceCenter": 50.0,
            "avoidanceScale": 5.0
          },
          "finalSelection": {
            "overlapPenalties": {"two": 1.5, "three": 5.0, "fourOrMore": 10.0},
            "multipleUsePenaltyMultiplier": 2.4,
            "diversifiedMaximumPairwiseOverlap": 2,
            "previousDrawOverlap": {
              "twoMatchPenalty": $previousDrawTwoMatchPenalty,
              "threeMatchPenalty": $previousDrawThreeMatchPenalty,
              "fourPlusMatchPenalty": $previousDrawFourPlusMatchPenalty,
              "maximumTwoPlusGamesPerBatch": $maximumPreviousDrawTwoPlusGamesPerBatch
            }
          },
          "balanced": {
            "verifiedRecentSumTolerance": 42.0,
            "recentOddTolerance": 2.5,
            "recentLowTolerance": 2.5,
            "maximumPerDecadeBucket": 3,
            "variance": [35.0, 263.0],
            "selectionOverlapWeight": 1.0,
            "repeatedNumberPenalty": 0.7,
            "repeatedPairPenalty": 1.3,
            "newCoverageWeight": 1.0,
            "bucketBonusWeight": 0.45,
            "selectionAvoidanceWeight": 0.0,
            "dataWeight": 0.45,
            "patternWeight": 0.35,
            "distributionWeight": 0.20
          },
          "diversified": {
            "minimumHighCount": 2,
            "minimumSpread": 27,
            "minimumDecadeBuckets": 4,
            "maximumSameTailCount": 3,
            "minimumAcValue": 5,
            "distributionScoring": "HISTORY_FIT",
            "selectionOverlapWeight": 1.45,
            "repeatedNumberPenalty": 1.15,
            "repeatedPairPenalty": 2.25,
            "newCoverageWeight": 1.55,
            "bucketBonusWeight": 0.7,
            "selectionAvoidanceWeight": 0.08,
            "dataWeight": 0.40,
            "patternWeight": 0.20,
            "distributionWeight": 0.40
          },
          "publicPickAvoidance": {
            "highNumberMinimum": 32,
            "birthdayMaximum": 31,
            "birthdayPenaltyByHighCount": {"zero": 6.0, "one": 2.5},
            "birthdayHeavyPenalty": {"six": 4.0, "five": 1.8},
            "simplePatternPenalty": 3.0,
            "sameTailPenalty": 1.2,
            "roundNumberPenalty": 0.8,
            "highNumberBonus": 1.1,
            "bucketBonus": 0.7,
            "acValueBonus": 0.35
          },
          "randomControl": {
            "gameCount": $defaultGameCount,
            "seedPersisted": true,
            "randomImplementation": "kotlin.random.Random",
            "uniformWithoutReplacementWithinTicket": true,
            "uniqueTicketsWithinRound": true
          }
        }
        """.trimIndent()

    private fun buildTrendProfile(
        history: List<List<Int>>,
        includeBacktest: Boolean = true,
        backtestStrategy: CoverageStrategy,
    ): TrendProfile {
        val recentWindow = history.take(minOf(recentSumWindow, history.size)).ifEmpty { history }
        val longFrequency = buildFrequencyMap(history)
        val longPairFrequency = buildPairFrequencyMap(history)
        val historyAnalysis = buildHistoryAnalysisProfile(history)
        val lastSeenGap = buildLastSeenGap(history)
        val gapValidationProfile = buildGapValidationProfile(history)
        val sumValidationProfile = buildSumValidationProfile(history)
        val backtestProfile = if (includeBacktest) {
            buildBacktestProfile(history, backtestStrategy)
        } else {
            BacktestProfile()
        }

        return TrendProfile(
            recentSumAverage = recentWindow.map(List<Int>::sum).average(),
            recentOddAverage = recentWindow.map { draw -> draw.count { it % 2 != 0 } }.average(),
            recentLowAverage = recentWindow.map { draw -> draw.count { it <= 22 } }.average(),
            recentBucketAverage = recentWindow.map(::decadeBucketCount).average(),
            numberEvidence = buildNumberEvidence(history),
            pairEvidence = buildPairEvidence(history, longFrequency, longPairFrequency),
            currentGaps = lastSeenGap,
            gapEvidence = buildGapEvidence(lastSeenGap, gapValidationProfile),
            gapWeight = buildGapWeight(lastSeenGap, gapValidationProfile),
            gapValidationProfile = gapValidationProfile,
            sumValidationProfile = sumValidationProfile,
            transitionProfile = buildTransitionProfile(history),
            historyAnalysis = historyAnalysis,
            backtestProfile = backtestProfile,
            scoreWeights = calibratedScoreWeights(backtestStrategy, backtestProfile),
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
        randomSource: Random,
    ): List<Int> {
        val selected = mutableSetOf<Int>()

        while (selected.size < pickCount) {
            val candidates = (1..maxNumber).filter { number -> number !in selected }
            if (candidates.isEmpty()) return generateRandomCombination(randomSource)
            selected += pickWeightedNumber(
                candidates = candidates,
                selected = selected,
                trendProfile = trendProfile,
                strategy = strategy,
                randomSource = randomSource,
            )
        }

        return selected.sorted()
    }

    private fun pickWeightedNumber(
        candidates: List<Int>,
        selected: Set<Int>,
        trendProfile: TrendProfile,
        strategy: CoverageStrategy,
        randomSource: Random,
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
        if (totalWeight <= 0.0) return candidates[randomSource.nextInt(candidates.size)]

        var threshold = randomSource.nextDouble() * totalWeight
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
        val gapFit = trendProfile.gapWeight.getValue(number)
        val highNumberFit = when {
            number >= 32 && strategy == CoverageStrategy.DIVERSIFIED -> 1.05
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
        if (sum !in 65..215) return false
        val highCount = numbers.count { it >= 32 }
        if (highCount > 5) return false
        val middleCount = numbers.count { it in 16..30 }
        if (middleCount > 5) return false
        val spread = numbers.last() - numbers.first()
        if (spread !in 13..44) return false
        if (numberVariance(numbers) !in 20.0..290.0) return false
        if (decadeBucketCount(numbers) < 2) return false
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
        if (tailDuplicates > 3) return false
        if (maxConsecutiveRun(numbers) > 3) return false
        return acValue(numbers) >= 3
    }

    private fun isBalancedCandidate(numbers: List<Int>, trendProfile: TrendProfile): Boolean {
        if (!isBaseCoverageCandidate(numbers)) return false
        val sum = numbers.sum()
        val oddCount = numbers.count { it % 2 != 0 }
        val lowCount = numbers.count { it <= 22 }
        val highCount = numbers.count { it >= 32 }
        val bucketCounts = decadeBucketCounts(numbers)

        if (
            trendProfile.sumValidationProfile.applied &&
            abs(sum - trendProfile.recentSumAverage) > 42.0
        ) {
            return false
        }
        if (abs(oddCount - trendProfile.recentOddAverage) > 2.5) return false
        if (abs(lowCount - trendProfile.recentLowAverage) > 2.5) return false
        if (highCount !in 0..5) return false
        if (bucketCounts.values.any { it > 3 }) return false
        return numberVariance(numbers) in 35.0..263.0
    }

    private fun isDiversifiedCandidate(numbers: List<Int>): Boolean {
        if (!isBaseCoverageCandidate(numbers)) return false
        val highCount = numbers.count { it >= 32 }
        val tailDuplicates = numbers.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1

        if (highCount < 2) return false
        if (numbers.last() - numbers.first() < 27) return false
        if (decadeBucketCount(numbers) < 4) return false
        if (tailDuplicates > 3) return false
        return acValue(numbers) >= 5
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
        lastDraw: List<Int>,
        generationSeed: Long,
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

        return pickDiverseTopGames(scored, gameCount, strategy, lastDraw).map { candidate ->
            LottoGeneratedTicket(
                numbers = candidate.numbers,
                comment = commentBuilder(candidate.numbers, candidate.score),
                score = candidate.score.toAnalysisScore(),
                generationMode = mode.name,
                generationSeed = generationSeed,
                featureSnapshotJson = candidate.score.featureSnapshotJson,
            )
        }
    }

    private fun pickDiverseTopGames(
        candidates: List<ScoredCandidate>,
        gameCount: Int,
        strategy: CoverageStrategy,
        lastDraw: List<Int>,
    ): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val selected = mutableListOf<ScoredCandidate>()
        val remaining = candidates.toMutableList()

        selected += remaining.removeFirst()

        while (selected.size < gameCount && remaining.isNotEmpty()) {
            val pairwiseSelectable = strategy.maximumPairwiseOverlap?.let { maximumOverlap ->
                remaining.filter { candidate ->
                    selected.all { picked ->
                        candidate.numbers.intersect(picked.numbers.toSet()).size <= maximumOverlap
                    }
                }
            } ?: remaining
            val selectedTwoPlusCount = selected.count { candidate ->
                candidate.numbers.count(lastDraw::contains) >= 2
            }
            val selectable = if (selectedTwoPlusCount >= maximumPreviousDrawTwoPlusGamesPerBatch) {
                pairwiseSelectable.filter { candidate -> candidate.numbers.count(lastDraw::contains) < 2 }
            } else {
                pairwiseSelectable
            }
            if (selectable.isEmpty()) break

            val coverage = selected.flatMap { it.numbers }.toSet()
            val numberUsage = selected.flatMap { it.numbers }.groupingBy { it }.eachCount()
            val selectedPairs = selected.flatMap { drawPairs(it.numbers) }.toSet()
            val next = selectable.maxByOrNull { candidate ->
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
        captureFeatureSnapshot: Boolean = true,
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
        val previousDrawOverlapPenalty = previousDrawOverlapPenalty(numbers.count(lastDraw::contains))
        val weights = trendProfile.scoreWeights
        val totalScore = (
            dataScore * weights.data +
                patternScore * weights.pattern +
                distributionScore * weights.distribution -
                previousDrawOverlapPenalty
            ).coerceIn(0.0, 100.0)
        val featureSnapshotJson = if (captureFeatureSnapshot) {
            buildFeatureSnapshotJson(
                numbers = numbers,
                lastDraw = lastDraw,
                trendProfile = trendProfile,
                numberScore = numberScore,
                pairScore = pairScore,
                gapScore = gapScore,
                historyPatternScore = historyPatternScore,
                transitionScore = transitionScore,
            )
        } else {
            null
        }

        return CandidateScore(
            totalScore = totalScore,
            dataScore = dataScore,
            patternScore = patternScore,
            distributionScore = distributionScore,
            avoidanceScore = avoidanceScore,
            previousDrawOverlapPenalty = previousDrawOverlapPenalty,
            validationScore = trendProfile.backtestProfile.averagePercentile,
            featureSnapshotJson = featureSnapshotJson,
        )
    }

    private fun previousDrawOverlapPenalty(overlapCount: Int): Double = when {
        overlapCount >= 4 -> previousDrawFourPlusMatchPenalty
        overlapCount == 3 -> previousDrawThreeMatchPenalty
        overlapCount == 2 -> previousDrawTwoMatchPenalty
        else -> 0.0
    }

    private fun buildFeatureSnapshotJson(
        numbers: List<Int>,
        lastDraw: List<Int>,
        trendProfile: TrendProfile,
        numberScore: Double,
        pairScore: Double,
        gapScore: Double,
        historyPatternScore: Double,
        transitionScore: Double,
    ): String {
        val candidateGaps = numbers.map { number -> trendProfile.currentGaps.getValue(number) }
        val candidateVariance = numberVariance(numbers)
        val maximumDecadeBucketCount = decadeBucketCounts(numbers).values.maxOrNull() ?: 0
        val maximumTailCount = numbers.groupingBy { number -> number % 10 }.eachCount().values.maxOrNull() ?: 0
        val gapEvidenceJson = gapThresholds.joinToString(separator = ",") { threshold ->
            val evidence = trendProfile.gapValidationProfile.evidenceByThreshold[threshold]
            "\"$threshold\":" + if (evidence == null) {
                "null"
            } else {
                buildString {
                    append("{")
                    append("\"roundCount\":").append(evidence.roundCount).append(",")
                    append("\"opportunityCount\":").append(evidence.opportunityCount).append(",")
                    append("\"actualHitCount\":").append(evidence.actualHitCount).append(",")
                    append("\"expectedHitCount\":").append(evidence.expectedHitCount.toJsonNumber()).append(",")
                    append("\"observedRate\":").append(evidence.observedRate.toJsonNumber()).append(",")
                    append("\"smoothedLift\":").append(evidence.smoothedLift.toJsonNumber()).append(",")
                    append("\"zScore\":").append(evidence.zScore.toJsonNumber()).append(",")
                    append("\"stableDirection\":").append(evidence.stableDirection).append(",")
                    append("\"applied\":").append(evidence.applied).append(",")
                    append("\"appliedLift\":").append(evidence.appliedLift.toJsonNumber())
                    append("}")
                }
            }
        }
        val sumValidation = trendProfile.sumValidationProfile

        return buildString {
            append("{")
            append("\"schemaVersion\":").append(CURRENT_FEATURE_SNAPSHOT_SCHEMA_VERSION).append(",")
            append("\"historyBasis\":\"MAIN_ONLY\",")
            append("\"candidate\":{")
            append("\"sum\":").append(numbers.sum()).append(",")
            append("\"oddCount\":").append(numbers.count { it % 2 != 0 }).append(",")
            append("\"lowCount\":").append(numbers.count { it <= 22 }).append(",")
            append("\"highCount\":").append(numbers.count { it >= 32 }).append(",")
            append("\"spread\":").append(numbers.last() - numbers.first()).append(",")
            append("\"variance\":").append(candidateVariance.toJsonNumber()).append(",")
            append("\"decadeBucketCount\":").append(decadeBucketCount(numbers)).append(",")
            append("\"maximumDecadeBucketCount\":").append(maximumDecadeBucketCount).append(",")
            append("\"maximumTailCount\":").append(maximumTailCount).append(",")
            append("\"consecutivePairCount\":").append(consecutivePairCount(numbers)).append(",")
            append("\"acValue\":").append(acValue(numbers)).append(",")
            val carryCount = numbers.count(lastDraw::contains)
            append("\"carryCount\":").append(carryCount).append(",")
            append("\"carryPenalty\":").append(previousDrawOverlapPenalty(carryCount).toJsonNumber()).append(",")
            append("\"recentSumAverage\":").append(trendProfile.recentSumAverage.toJsonNumber()).append(",")
            append("\"recentSumDeviation\":")
                .append(abs(numbers.sum() - trendProfile.recentSumAverage).toJsonNumber()).append(",")
            append("\"gap5Count\":").append(candidateGaps.count { it >= 5 }).append(",")
            append("\"gap10Count\":").append(candidateGaps.count { it >= 10 }).append(",")
            append("\"gap15Count\":").append(candidateGaps.count { it >= 15 })
            append("},")
            append("\"componentScores\":{")
            append("\"number\":").append(numberScore.toJsonNumber()).append(",")
            append("\"pair\":").append(pairScore.toJsonNumber()).append(",")
            append("\"gap\":").append(gapScore.toJsonNumber()).append(",")
            append("\"historyPattern\":").append(historyPatternScore.toJsonNumber()).append(",")
            append("\"transition\":").append(transitionScore.toJsonNumber())
            append("},")
            append("\"gapValidation\":{").append(gapEvidenceJson).append("},")
            append("\"sumValidation\":{")
            append("\"sampleCount\":").append(sumValidation.sampleCount).append(",")
            append("\"recentMeanAbsoluteError\":")
                .append(sumValidation.recentMeanAbsoluteError.toJsonNumber()).append(",")
            append("\"fixedMeanAbsoluteError\":")
                .append(sumValidation.fixedMeanAbsoluteError.toJsonNumber()).append(",")
            append("\"improvementRate\":").append(sumValidation.improvementRate.toJsonNumber()).append(",")
            append("\"stableImprovement\":").append(sumValidation.stableImprovement).append(",")
            append("\"applied\":").append(sumValidation.applied)
            append("},")
            append("\"scoreCalibration\":{")
            append("\"dataWeight\":").append(trendProfile.scoreWeights.data.toJsonNumber()).append(",")
            append("\"patternWeight\":").append(trendProfile.scoreWeights.pattern.toJsonNumber()).append(",")
            append("\"distributionWeight\":").append(trendProfile.scoreWeights.distribution.toJsonNumber()).append(",")
            append("\"walkForwardSamples\":").append(trendProfile.backtestProfile.simulationSampleCount).append(",")
            append("\"strategyAverageMatchCount\":")
                .append(trendProfile.backtestProfile.strategyAverageMatchCount.toJsonNumber()).append(",")
            append("\"controlAverageMatchCount\":")
                .append(trendProfile.backtestProfile.controlAverageMatchCount.toJsonNumber()).append(",")
            append("\"averageMatchDifference\":")
                .append(trendProfile.backtestProfile.averageMatchDifference.toJsonNumber()).append(",")
            append("\"learnedWeightsApplied\":").append(trendProfile.backtestProfile.learnedScoreWeights != null)
            append("}")
            append("}")
        }
    }

    private fun Double.toJsonNumber(): String = if (isFinite()) toString() else "null"

    private fun calibratedScoreWeights(
        strategy: CoverageStrategy,
        backtestProfile: BacktestProfile,
    ): ScoreWeights = backtestProfile.learnedScoreWeights ?: baseScoreWeights(strategy)

    private fun baseScoreWeights(strategy: CoverageStrategy): ScoreWeights =
        ScoreWeights(
            data = strategy.dataWeight,
            pattern = strategy.patternWeight,
            distribution = strategy.distributionWeight,
        )

    private fun proposeBacktestScoreWeights(
        strategy: CoverageStrategy,
        backtestProfile: BacktestProfile,
    ): ScoreWeights {
        val baseWeights = baseScoreWeights(strategy)
        if (backtestProfile.sampleCount < minimumBacktestWeightTrainingSamples) return baseWeights

        val reliability =
            (backtestProfile.sampleCount - minimumBacktestWeightTrainingSamples + 1).toDouble() /
                (backtestSampleCount - minimumBacktestHoldoutSamples - minimumBacktestWeightTrainingSamples + 1).toDouble()

        fun adjustment(percentile: Double): Double =
            1.0 + ((percentile - 50.0) / 50.0) * maximumBacktestWeightAdjustment * reliability.coerceIn(0.0, 1.0)

        val adjustedData = baseWeights.data * adjustment(backtestProfile.dataAveragePercentile)
        val adjustedPattern = baseWeights.pattern * adjustment(backtestProfile.patternAveragePercentile)
        val adjustedDistribution =
            baseWeights.distribution * adjustment(backtestProfile.distributionAveragePercentile)
        val total = adjustedData + adjustedPattern + adjustedDistribution
        if (total <= 0.0) return baseWeights

        return ScoreWeights(
            data = adjustedData / total,
            pattern = adjustedPattern / total,
            distribution = adjustedDistribution / total,
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
        currentGaps: Map<Int, Int>,
        validationProfile: GapValidationProfile,
    ): Map<Int, Double> = (1..maxNumber).associateWith { number ->
        val evidence = validationProfile.evidenceForGap(currentGaps.getValue(number))
        val appliedLift = evidence?.appliedLift ?: 0.0
        (50.0 + appliedLift * 100.0).coerceIn(0.0, 100.0)
    }

    private fun buildGapWeight(
        currentGaps: Map<Int, Int>,
        validationProfile: GapValidationProfile,
    ): Map<Int, Double> = (1..maxNumber).associateWith { number ->
        1.0 + (validationProfile.evidenceForGap(currentGaps.getValue(number))?.appliedLift ?: 0.0)
    }

    private fun buildGapValidationProfile(history: List<List<Int>>): GapValidationProfile {
        // 특정 미출현 구간의 우연한 단기 상승을 예측력으로 오인하지 않도록 시간 구간별 방향까지 확인한다.
        val chronologicalHistory = history.asReversed()
        val evaluationRoundCount = chronologicalHistory.size - adaptiveMinimumTrainingDraws
        if (evaluationRoundCount <= 0) return GapValidationProfile()

        val currentGaps = (1..maxNumber).associateWith { 0 }.toMutableMap()
        val seenNumbers = mutableSetOf<Int>()
        val totals = gapThresholds.associateWith { GapEvidenceAccumulator() }
        val segments = gapThresholds.associateWith {
            List(adaptiveSegmentCount) { GapEvidenceAccumulator() }
        }

        chronologicalHistory.forEachIndexed { index, draw ->
            if (index >= adaptiveMinimumTrainingDraws) {
                val segmentIndex = (
                    (index - adaptiveMinimumTrainingDraws) * adaptiveSegmentCount /
                        evaluationRoundCount
                    ).coerceIn(0, adaptiveSegmentCount - 1)
                val winningNumbers = draw.toSet()
                gapThresholds.forEach { threshold ->
                    val candidateCount = seenNumbers.count { number -> currentGaps.getValue(number) >= threshold }
                    val hitCount = winningNumbers.count { number ->
                        number in seenNumbers && currentGaps.getValue(number) >= threshold
                    }
                    totals.getValue(threshold).record(candidateCount, hitCount)
                    segments.getValue(threshold)[segmentIndex].record(candidateCount, hitCount)
                }
            }

            for (number in 1..maxNumber) {
                if (number in draw) {
                    currentGaps[number] = 0
                    seenNumbers += number
                } else if (number in seenNumbers) {
                    currentGaps[number] = currentGaps.getValue(number) + 1
                }
            }
        }

        return GapValidationProfile(
            evidenceByThreshold = gapThresholds.associateWith { threshold ->
                totals.getValue(threshold).toEvidence(
                    threshold = threshold,
                    segmentAccumulators = segments.getValue(threshold),
                )
            },
        )
    }

    private fun buildSumValidationProfile(history: List<List<Int>>): SumValidationProfile {
        // 최근 합 평균은 고정 이론 평균보다 지속적으로 오차가 작을 때만 생성 조건에 사용한다.
        val chronologicalHistory = history.asReversed()
        val evaluationRoundCount = chronologicalHistory.size - recentSumWindow
        if (evaluationRoundCount <= 0) return SumValidationProfile()

        var recentAbsoluteErrorTotal = 0.0
        var fixedAbsoluteErrorTotal = 0.0
        val segmentRecentErrors = DoubleArray(adaptiveSegmentCount)
        val segmentFixedErrors = DoubleArray(adaptiveSegmentCount)
        val segmentSamples = IntArray(adaptiveSegmentCount)

        for (targetIndex in recentSumWindow until chronologicalHistory.size) {
            val recentAverage = chronologicalHistory
                .subList(targetIndex - recentSumWindow, targetIndex)
                .map(List<Int>::sum)
                .average()
            val actualSum = chronologicalHistory[targetIndex].sum().toDouble()
            val recentError = abs(actualSum - recentAverage)
            val fixedError = abs(actualSum - theoreticalSumAverage)
            val segmentIndex = (
                (targetIndex - recentSumWindow) * adaptiveSegmentCount /
                    evaluationRoundCount
                ).coerceIn(0, adaptiveSegmentCount - 1)

            recentAbsoluteErrorTotal += recentError
            fixedAbsoluteErrorTotal += fixedError
            segmentRecentErrors[segmentIndex] += recentError
            segmentFixedErrors[segmentIndex] += fixedError
            segmentSamples[segmentIndex]++
        }

        val recentMeanAbsoluteError = recentAbsoluteErrorTotal / evaluationRoundCount
        val fixedMeanAbsoluteError = fixedAbsoluteErrorTotal / evaluationRoundCount
        val improvementRate = if (fixedMeanAbsoluteError > 0.0) {
            (fixedMeanAbsoluteError - recentMeanAbsoluteError) / fixedMeanAbsoluteError
        } else {
            0.0
        }
        val stableImprovement = segmentSamples.indices.all { index ->
            segmentSamples[index] > 0 &&
                segmentRecentErrors[index] / segmentSamples[index] <
                segmentFixedErrors[index] / segmentSamples[index]
        }

        return SumValidationProfile(
            sampleCount = evaluationRoundCount,
            recentMeanAbsoluteError = recentMeanAbsoluteError,
            fixedMeanAbsoluteError = fixedMeanAbsoluteError,
            improvementRate = improvementRate,
            stableImprovement = stableImprovement,
            applied = evaluationRoundCount >= adaptiveMinimumEvaluationRounds &&
                improvementRate >= minimumSumImprovementRate &&
                stableImprovement,
        )
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
        val analysisWindow = history.take(minOf(180, history.size)).ifEmpty { history }.map(List<Int>::sorted)
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
            positionBuckets = (0 until pickCount).map { position ->
                analysisWindow.groupingBy { draw -> draw[position] }.eachCount()
            },
            adjacentGapBuckets = (0 until pickCount - 1).map { position ->
                analysisWindow.groupingBy { draw -> draw[position + 1] - draw[position] }.eachCount()
            },
            consecutivePairBuckets = analysisWindow.groupingBy(::consecutivePairCount).eachCount(),
        )
    }

    private fun buildBacktestProfile(
        history: List<List<Int>>,
        strategy: CoverageStrategy,
    ): BacktestProfile {
        val roundEvidence = mutableListOf<BacktestRoundEvidence>()
        val maxSamples = minOf(backtestSampleCount, history.size - minimumBacktestTrainingDraws)
        if (maxSamples <= 0) return BacktestProfile()

        for (targetIndex in 0 until maxSamples) {
            val trainingHistory = history.drop(targetIndex + 1)
            if (trainingHistory.size < minimumBacktestTrainingDraws) continue
            val trainingProfile = buildTrendProfile(
                history = trainingHistory,
                includeBacktest = false,
                backtestStrategy = strategy,
            )
            val lastTrainingDraw = trainingHistory.first()
            val actualNumbers = history[targetIndex]
            val baselineSeed = targetIndex * 10_007 + actualNumbers.sum() * 97
            val baselineRandom = Random(seed = baselineSeed)
            val randomScores = mutableListOf<CandidateScore>()
            var randomAttempt = 0
            val maximumRandomAttempts = backtestRandomCandidateCount * 200
            while (randomScores.size < backtestRandomCandidateCount && randomAttempt < maximumRandomAttempts) {
                randomAttempt++
                val randomNumbers = generateRandomCombination(baselineRandom)
                val randomEligible = when (strategy) {
                    CoverageStrategy.BALANCED -> isBalancedCandidate(randomNumbers, trainingProfile)
                    CoverageStrategy.DIVERSIFIED -> isDiversifiedCandidate(randomNumbers)
                } && !isHistoricalDuplicate(randomNumbers, trainingHistory)
                if (!randomEligible) continue
                randomScores += scoreCandidate(
                    numbers = randomNumbers,
                    trendProfile = trainingProfile,
                    lastDraw = lastTrainingDraw,
                    strategy = strategy,
                    captureFeatureSnapshot = false,
                )
            }
            if (randomScores.isEmpty()) continue

            val actualEligible = when (strategy) {
                CoverageStrategy.BALANCED -> isBalancedCandidate(actualNumbers, trainingProfile)
                CoverageStrategy.DIVERSIFIED -> isDiversifiedCandidate(actualNumbers)
            } && !isHistoricalDuplicate(actualNumbers, trainingHistory)
            val actualCandidateScore = actualNumbers.takeIf { actualEligible }?.let { numbers ->
                scoreCandidate(
                    numbers = numbers,
                    trendProfile = trainingProfile,
                    lastDraw = lastTrainingDraw,
                    strategy = strategy,
                    captureFeatureSnapshot = false,
                )
            }
            val strategyCandidates = generateBacktestStrategyCandidates(
                trainingHistory = trainingHistory,
                trainingProfile = trainingProfile,
                lastTrainingDraw = lastTrainingDraw,
                strategy = strategy,
                randomSource = Random(seed = baselineSeed xor ((strategy.ordinal + 1) * 0x1F123BB5)),
            )
            val controlTickets = generateBacktestControlTickets(
                randomSource = Random(seed = baselineSeed xor 0x5F3759DF),
            )
            roundEvidence += BacktestRoundEvidence(
                actualNumbers = actualNumbers,
                previousDraw = lastTrainingDraw,
                totalPercentile = actualCandidateScore?.let { score ->
                    scorePercentile(
                        actualScore = score.dataScore * 0.60 + score.patternScore * 0.40,
                        baselineScores = randomScores.map { it.dataScore * 0.60 + it.patternScore * 0.40 },
                    )
                } ?: 0.0,
                dataPercentile = actualCandidateScore?.let { score ->
                    scorePercentile(score.dataScore, randomScores.map(CandidateScore::dataScore))
                } ?: 0.0,
                patternPercentile = actualCandidateScore?.let { score ->
                    scorePercentile(score.patternScore, randomScores.map(CandidateScore::patternScore))
                } ?: 0.0,
                distributionPercentile = actualCandidateScore?.let { score ->
                    scorePercentile(score.distributionScore, randomScores.map(CandidateScore::distributionScore))
                } ?: 0.0,
                strategyCandidates = strategyCandidates,
                controlTickets = controlTickets,
            )
        }

        if (roundEvidence.size < minimumBacktestSamples) {
            return BacktestProfile(sampleCount = roundEvidence.size)
        }

        val holdoutCount = maxOf(minimumBacktestHoldoutSamples, roundEvidence.size / 3)
            .coerceAtMost(roundEvidence.size - minimumBacktestWeightTrainingSamples)
        val holdoutEvidence = roundEvidence.take(holdoutCount)
        val weightTrainingEvidence = roundEvidence.drop(holdoutCount)
        val weightTrainingProfile = buildBacktestPercentileProfile(weightTrainingEvidence)
        val baseWeights = baseScoreWeights(strategy)
        val proposedWeights = proposeBacktestScoreWeights(strategy, weightTrainingProfile)
        val baseEvaluation = evaluateBacktestWeights(holdoutEvidence, strategy, baseWeights)
        val proposedEvaluation = evaluateBacktestWeights(holdoutEvidence, strategy, proposedWeights)
        val learnedWeights = proposedWeights.takeIf {
            proposedWeights != baseWeights &&
                proposedEvaluation.sampleCount >= minimumBacktestHoldoutSamples &&
                proposedEvaluation.strategyAverageMatchCount >= baseEvaluation.strategyAverageMatchCount &&
                proposedEvaluation.strategyAverageMatchCount >= proposedEvaluation.controlAverageMatchCount
        }
        val selectedEvaluation = if (learnedWeights != null) proposedEvaluation else baseEvaluation
        val percentileProfile = buildBacktestPercentileProfile(roundEvidence)

        return percentileProfile.copy(
            simulationSampleCount = selectedEvaluation.sampleCount,
            strategyAverageMatchCount = selectedEvaluation.strategyAverageMatchCount,
            controlAverageMatchCount = selectedEvaluation.controlAverageMatchCount,
            averageMatchDifference =
                selectedEvaluation.strategyAverageMatchCount - selectedEvaluation.controlAverageMatchCount,
            learnedScoreWeights = learnedWeights,
        )
    }

    private fun generateBacktestStrategyCandidates(
        trainingHistory: List<List<Int>>,
        trainingProfile: TrendProfile,
        lastTrainingDraw: List<Int>,
        strategy: CoverageStrategy,
        randomSource: Random,
    ): List<ScoredCandidate> {
        val candidates = linkedSetOf<List<Int>>()
        val maximumAttempts = backtestStrategyCandidateCount * 200
        var attempt = 0
        while (candidates.size < backtestStrategyCandidateCount && attempt < maximumAttempts) {
            attempt++
            val numbers = generatePredictedCombination(
                trendProfile = trainingProfile,
                strategy = strategy,
                randomSource = randomSource,
            )
            val eligible = when (strategy) {
                CoverageStrategy.BALANCED -> isBalancedCandidate(numbers, trainingProfile)
                CoverageStrategy.DIVERSIFIED -> isDiversifiedCandidate(numbers)
            } && !isHistoricalDuplicate(numbers, trainingHistory)
            if (eligible) candidates += numbers
        }
        return candidates.map { numbers ->
            ScoredCandidate(
                numbers = numbers,
                score = scoreCandidate(
                    numbers = numbers,
                    trendProfile = trainingProfile,
                    lastDraw = lastTrainingDraw,
                    strategy = strategy,
                    captureFeatureSnapshot = false,
                ),
            )
        }
    }

    private fun generateBacktestControlTickets(randomSource: Random): List<List<Int>> {
        val tickets = linkedSetOf<List<Int>>()
        while (tickets.size < defaultGameCount) {
            tickets += generateRandomCombination(randomSource)
        }
        return tickets.toList()
    }

    private fun buildBacktestPercentileProfile(evidence: List<BacktestRoundEvidence>): BacktestProfile {
        if (evidence.isEmpty()) return BacktestProfile()
        return BacktestProfile(
            sampleCount = evidence.size,
            averagePercentile = evidence.map(BacktestRoundEvidence::totalPercentile).average(),
            aboveRandomRate = evidence.count { it.totalPercentile > 50.0 }.toDouble() / evidence.size * 100.0,
            dataAveragePercentile = evidence.map(BacktestRoundEvidence::dataPercentile).average(),
            patternAveragePercentile = evidence.map(BacktestRoundEvidence::patternPercentile).average(),
            distributionAveragePercentile = evidence.map(BacktestRoundEvidence::distributionPercentile).average(),
        )
    }

    private fun evaluateBacktestWeights(
        evidence: List<BacktestRoundEvidence>,
        strategy: CoverageStrategy,
        weights: ScoreWeights,
    ): BacktestEvaluation {
        val roundResults = evidence.mapNotNull { round ->
            val reweightedCandidates = round.strategyCandidates
                .map { candidate ->
                    candidate.copy(
                        score = candidate.score.copy(
                            totalScore = (
                                candidate.score.dataScore * weights.data +
                                    candidate.score.patternScore * weights.pattern +
                                    candidate.score.distributionScore * weights.distribution -
                                    candidate.score.previousDrawOverlapPenalty
                                ).coerceIn(0.0, 100.0),
                        ),
                    )
                }
                .sortedByDescending { candidate -> candidate.score.totalScore }
            val strategyTickets = pickDiverseTopGames(
                candidates = reweightedCandidates,
                gameCount = defaultGameCount,
                strategy = strategy,
                lastDraw = round.previousDraw,
            )
            if (strategyTickets.size < defaultGameCount || round.controlTickets.size < defaultGameCount) {
                return@mapNotNull null
            }
            val strategyAverage = strategyTickets
                .map { ticket -> ticket.numbers.count(round.actualNumbers::contains) }
                .average()
            val controlAverage = round.controlTickets
                .map { numbers -> numbers.count(round.actualNumbers::contains) }
                .average()
            strategyAverage to controlAverage
        }
        if (roundResults.isEmpty()) return BacktestEvaluation()
        return BacktestEvaluation(
            sampleCount = roundResults.size,
            strategyAverageMatchCount = roundResults.map { result -> result.first }.average(),
            controlAverageMatchCount = roundResults.map { result -> result.second }.average(),
        )
    }

    private fun scorePercentile(actualScore: Double, baselineScores: List<Double>): Double =
        baselineScores.count { it <= actualScore }.toDouble() / baselineScores.size * 100.0

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
                patternBucketScore(profile.acBuckets, ac) * 1.2 +
                positionalPatternScore(numbers, profile.positionBuckets) * 1.0 +
                positionalPatternScore(adjacentGaps(numbers), profile.adjacentGapBuckets) * 0.8 +
                patternBucketScore(profile.consecutivePairBuckets, consecutivePairCount(numbers)) * 0.7

        return shapeScore + bucketScore
    }

    private fun positionalPatternScore(
        values: List<Int>,
        buckets: List<Map<Int, Int>>,
    ): Double {
        if (values.size != buckets.size || values.isEmpty()) return 0.0
        return values.indices.map { index ->
            patternBucketScore(buckets[index], values[index])
        }.average()
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
        val calibration = if (profile.learnedScoreWeights != null) "보정적용" else "기본유지"
        return "$level ${formatScore(profile.averagePercentile)} · " +
            "모의 ${formatScore(profile.strategyAverageMatchCount)}/무작위 ${formatScore(profile.controlAverageMatchCount)} · " +
            calibration
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
                val verifiedRecentSumScore = if (trendProfile.sumValidationProfile.applied) {
                    fitScore(numbers.sum().toDouble(), trendProfile.recentSumAverage, 52.0)
                } else {
                    50.0
                }
                listOf(
                    verifiedRecentSumScore,
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
                val profile = trendProfile.historyAnalysis
                listOf(
                    fitScore(spread.toDouble(), profile.spreadAverage, 20.0),
                    fitScore(variance, profile.varianceAverage, 120.0),
                    fitScore(bucketCount.toDouble(), profile.bucketAverage, 2.0),
                    fitScore(acValue(numbers).toDouble(), profile.acAverage, 5.0),
                    fitScore(numbers.count { it >= 32 }.toDouble(), profile.highAverage, 3.0),
                    fitScore(tailDuplicates.toDouble(), 1.9, 3.0),
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

    private fun adjacentGaps(numbers: List<Int>): List<Int> =
        numbers.sorted().zipWithNext { first, second -> second - first }

    private fun consecutivePairCount(numbers: List<Int>): Int =
        adjacentGaps(numbers).count { gap -> gap == 1 }

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
        val currentGaps: Map<Int, Int>,
        val gapEvidence: Map<Int, Double>,
        val gapWeight: Map<Int, Double>,
        val gapValidationProfile: GapValidationProfile,
        val sumValidationProfile: SumValidationProfile,
        val transitionProfile: TransitionProfile,
        val historyAnalysis: HistoryAnalysisProfile,
        val backtestProfile: BacktestProfile,
        val scoreWeights: ScoreWeights,
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
        val positionBuckets: List<Map<Int, Int>>,
        val adjacentGapBuckets: List<Map<Int, Int>>,
        val consecutivePairBuckets: Map<Int, Int>,
    )

    private data class BacktestProfile(
        val sampleCount: Int = 0,
        val averagePercentile: Double = 50.0,
        val aboveRandomRate: Double = 0.0,
        val dataAveragePercentile: Double = 50.0,
        val patternAveragePercentile: Double = 50.0,
        val distributionAveragePercentile: Double = 50.0,
        val simulationSampleCount: Int = 0,
        val strategyAverageMatchCount: Double = 0.0,
        val controlAverageMatchCount: Double = 0.0,
        val averageMatchDifference: Double = 0.0,
        val learnedScoreWeights: ScoreWeights? = null,
    )

    private data class BacktestRoundEvidence(
        val actualNumbers: List<Int>,
        val previousDraw: List<Int>,
        val totalPercentile: Double,
        val dataPercentile: Double,
        val patternPercentile: Double,
        val distributionPercentile: Double,
        val strategyCandidates: List<ScoredCandidate>,
        val controlTickets: List<List<Int>>,
    )

    private data class BacktestEvaluation(
        val sampleCount: Int = 0,
        val strategyAverageMatchCount: Double = 0.0,
        val controlAverageMatchCount: Double = 0.0,
    )

    private data class GapValidationProfile(
        val evidenceByThreshold: Map<Int, GapThresholdEvidence> = emptyMap(),
    ) {
        fun evidenceForGap(gap: Int): GapThresholdEvidence? =
            evidenceByThreshold.entries
                .sortedByDescending { entry -> entry.key }
                .firstOrNull { (threshold, evidence) -> gap >= threshold && evidence.applied }
                ?.value
    }

    private data class GapThresholdEvidence(
        val threshold: Int,
        val roundCount: Int,
        val opportunityCount: Int,
        val actualHitCount: Int,
        val expectedHitCount: Double,
        val observedRate: Double,
        val smoothedLift: Double,
        val zScore: Double,
        val stableDirection: Boolean,
        val applied: Boolean,
    ) {
        val appliedLift: Double
            get() = if (applied) {
                smoothedLift.coerceIn(
                    -LottoNumberGenerator.adaptiveMaximumLift,
                    LottoNumberGenerator.adaptiveMaximumLift,
                )
            } else {
                0.0
            }
    }

    private class GapEvidenceAccumulator {
        var roundCount: Int = 0
            private set
        var opportunityCount: Int = 0
            private set
        var actualHitCount: Int = 0
            private set
        var expectedHitCount: Double = 0.0
            private set
        var varianceTotal: Double = 0.0
            private set

        fun record(candidateCount: Int, hitCount: Int) {
            roundCount++
            opportunityCount += candidateCount
            actualHitCount += hitCount
            expectedHitCount += candidateCount * LottoNumberGenerator.baseAppearanceRate
            val candidateRate = candidateCount.toDouble() / LottoNumberGenerator.maxNumber
            varianceTotal +=
                LottoNumberGenerator.pickCount * candidateRate * (1.0 - candidateRate) *
                    (LottoNumberGenerator.maxNumber - LottoNumberGenerator.pickCount).toDouble() /
                    (LottoNumberGenerator.maxNumber - 1)
        }

        fun toEvidence(
            threshold: Int,
            segmentAccumulators: List<GapEvidenceAccumulator>,
        ): GapThresholdEvidence {
            val observedRate = if (opportunityCount > 0) {
                actualHitCount.toDouble() / opportunityCount
            } else {
                LottoNumberGenerator.baseAppearanceRate
            }
            val smoothedRate =
                (actualHitCount +
                    LottoNumberGenerator.adaptivePriorOpportunities * LottoNumberGenerator.baseAppearanceRate) /
                    (opportunityCount + LottoNumberGenerator.adaptivePriorOpportunities)
            val smoothedLift = smoothedRate / LottoNumberGenerator.baseAppearanceRate - 1.0
            val zScore = if (varianceTotal > 0.0) {
                (actualHitCount - expectedHitCount) / sqrt(varianceTotal)
            } else {
                0.0
            }
            val direction = zScore.compareTo(0.0)
            val stableDirection = direction != 0 && segmentAccumulators.all { segment ->
                segment.opportunityCount > 0 &&
                    (segment.actualHitCount - segment.expectedHitCount).compareTo(0.0) == direction
            }
            val applied = roundCount >= LottoNumberGenerator.adaptiveMinimumEvaluationRounds &&
                opportunityCount >= LottoNumberGenerator.adaptiveMinimumOpportunities &&
                abs(zScore) >= LottoNumberGenerator.adaptiveSignificanceZ &&
                stableDirection

            return GapThresholdEvidence(
                threshold = threshold,
                roundCount = roundCount,
                opportunityCount = opportunityCount,
                actualHitCount = actualHitCount,
                expectedHitCount = expectedHitCount,
                observedRate = observedRate,
                smoothedLift = smoothedLift,
                zScore = zScore,
                stableDirection = stableDirection,
                applied = applied,
            )
        }
    }

    private data class SumValidationProfile(
        val sampleCount: Int = 0,
        val recentMeanAbsoluteError: Double = 0.0,
        val fixedMeanAbsoluteError: Double = 0.0,
        val improvementRate: Double = 0.0,
        val stableImprovement: Boolean = false,
        val applied: Boolean = false,
    )

    private data class ScoreWeights(
        val data: Double,
        val pattern: Double,
        val distribution: Double,
    )

    private data class CandidateScore(
        val totalScore: Double,
        val dataScore: Double,
        val patternScore: Double,
        val distributionScore: Double,
        val avoidanceScore: Double,
        val previousDrawOverlapPenalty: Double,
        val validationScore: Double,
        val featureSnapshotJson: String?,
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
        val maximumPairwiseOverlap: Int?,
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
            maximumPairwiseOverlap = null,
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
            maximumPairwiseOverlap = 2,
            dataWeight = 0.40,
            patternWeight = 0.20,
            distributionWeight = 0.40,
        ),
    }
}
