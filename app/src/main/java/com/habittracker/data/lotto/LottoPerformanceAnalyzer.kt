package com.habittracker.data.lotto

import kotlin.math.sqrt

data class LottoPerformanceSample(
    val sourceLabel: String,
    val generationVersion: String,
    val totalScore: Double,
    val dataScore: Double?,
    val patternScore: Double?,
    val distributionScore: Double?,
    val avoidanceScore: Double?,
    val validationScore: Double?,
    val matchCount: Int,
)

data class LottoScorePerformance(
    val sourceLabel: String,
    val generationVersion: String,
    val sampleCount: Int,
    val averageMatchCount: Double,
    val scoreBands: List<LottoScoreBandPerformance>,
    val correlations: List<LottoScoreCorrelation>,
)

data class LottoScoreBandPerformance(
    val label: String,
    val sampleCount: Int,
    val averageMatchCount: Double,
    val threePlusMatchCount: Int,
    val maximumMatchCount: Int,
)

data class LottoScoreCorrelation(
    val component: LottoScoreComponent,
    val sampleCount: Int,
    val coefficient: Double?,
)

data class LottoControlComparison(
    val sourceLabel: String,
    val generationVersion: String,
    val pairedRoundCount: Int,
    val strategyAverageMatchCount: Double,
    val controlAverageMatchCount: Double,
    val averageMatchDifference: Double,
    val betterRoundCount: Int,
    val tiedRoundCount: Int,
    val worseRoundCount: Int,
)

enum class LottoScoreComponent(val label: String) {
    TOTAL("분석"),
    DATA("데이터"),
    PATTERN("패턴"),
    DISTRIBUTION("구조"),
    AVOIDANCE("공동당첨회피"),
    VALIDATION("과거검증"),
}

object LottoPerformanceAnalyzer {
    private const val minimumCorrelationSamples = 20

    fun analyze(samples: List<LottoPerformanceSample>): List<LottoScorePerformance> = samples
        .groupBy { sample -> sample.sourceLabel to sample.generationVersion }
        .map { (sourceAndVersion, groupSamples) ->
            val (sourceLabel, generationVersion) = sourceAndVersion
            LottoScorePerformance(
                sourceLabel = sourceLabel,
                generationVersion = generationVersion,
                sampleCount = groupSamples.size,
                averageMatchCount = groupSamples.map(LottoPerformanceSample::matchCount).average(),
                scoreBands = buildScoreBands(groupSamples),
                correlations = LottoScoreComponent.entries.map { component ->
                    buildCorrelation(groupSamples, component)
                },
            )
        }
        .sortedWith(compareBy<LottoScorePerformance> { sourceOrder(it.sourceLabel) }.thenByDescending { it.generationVersion })

    private fun buildScoreBands(samples: List<LottoPerformanceSample>): List<LottoScoreBandPerformance> = samples
        .groupBy { sample -> scoreBand(sample.totalScore) }
        .map { (band, bandSamples) ->
            LottoScoreBandPerformance(
                label = band.label,
                sampleCount = bandSamples.size,
                averageMatchCount = bandSamples.map(LottoPerformanceSample::matchCount).average(),
                threePlusMatchCount = bandSamples.count { it.matchCount >= 3 },
                maximumMatchCount = bandSamples.maxOf(LottoPerformanceSample::matchCount),
            )
        }
        .sortedByDescending { band -> scoreBandOrder(band.label) }

    private fun buildCorrelation(
        samples: List<LottoPerformanceSample>,
        component: LottoScoreComponent,
    ): LottoScoreCorrelation {
        val values = samples.mapNotNull { sample ->
            componentScore(sample, component)?.let { score -> score to sample.matchCount.toDouble() }
        }
        return LottoScoreCorrelation(
            component = component,
            sampleCount = values.size,
            coefficient = pearsonCorrelation(values),
        )
    }

    private fun pearsonCorrelation(values: List<Pair<Double, Double>>): Double? {
        if (values.size < minimumCorrelationSamples) return null
        val xAverage = values.map { it.first }.average()
        val yAverage = values.map { it.second }.average()
        val numerator = values.sumOf { (x, y) -> (x - xAverage) * (y - yAverage) }
        val xSquared = values.sumOf { (x, _) -> (x - xAverage) * (x - xAverage) }
        val ySquared = values.sumOf { (_, y) -> (y - yAverage) * (y - yAverage) }
        val denominator = sqrt(xSquared * ySquared)
        if (denominator == 0.0) return null
        return (numerator / denominator).coerceIn(-1.0, 1.0)
    }

    private fun componentScore(sample: LottoPerformanceSample, component: LottoScoreComponent): Double? = when (component) {
        LottoScoreComponent.TOTAL -> sample.totalScore
        LottoScoreComponent.DATA -> sample.dataScore
        LottoScoreComponent.PATTERN -> sample.patternScore
        LottoScoreComponent.DISTRIBUTION -> sample.distributionScore
        LottoScoreComponent.AVOIDANCE -> sample.avoidanceScore
        LottoScoreComponent.VALIDATION -> sample.validationScore
    }

    private fun scoreBand(score: Double): ScoreBand = when {
        score >= 90.0 -> ScoreBand("90~100")
        score >= 80.0 -> ScoreBand("80~89")
        score >= 70.0 -> ScoreBand("70~79")
        score >= 60.0 -> ScoreBand("60~69")
        else -> ScoreBand("0~59")
    }

    private fun scoreBandOrder(label: String): Int = label.substringBefore("~").toIntOrNull() ?: 0

    private fun sourceOrder(sourceLabel: String): Int = when (sourceLabel) {
        "균형형" -> 0
        "분산형" -> 1
        else -> 2
    }

    private data class ScoreBand(val label: String)
}
