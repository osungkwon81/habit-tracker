package com.habittracker.ui.lotto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PensionLotteryTab(val label: String) {
    INPUT("당첨번호 입력"),
    MATCH("번호 일치"),
    STATS("당첨번호 통계"),
    SCORE("번호 점수"),
}

enum class PensionLotteryRange(val weeks: Int, val label: String) {
    FOUR(4, "4주"),
    EIGHT(8, "8주"),
    TWELVE(12, "12주"),
    SIXTEEN(16, "16주"),
    TWENTY_FOUR(24, "24주"),
    FIFTY_TWO(52, "52주"),
}

class PensionLotteryViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(PensionLotteryTab.INPUT)
    private val selectedRange = MutableStateFlow(PensionLotteryRange.SIXTEEN)
    private val roundInput = MutableStateFlow("")
    private val groupInput = MutableStateFlow("")
    private val numberInputs = MutableStateFlow(List(6) { "" })
    private val recentDrawLimit = MutableStateFlow(RECENT_DRAW_PAGE_SIZE)
    private val matchNumberInput = MutableStateFlow("")
    private val statusMessage = MutableStateFlow<String?>(null)

    private val draws = repository.observeAllPensionLotteryDraws()
        .onEach { savedDraws ->
            if (roundInput.value.isBlank() && savedDraws.isNotEmpty()) {
                roundInput.value = (savedDraws.first().roundNo + 1).toString()
            }
        }

    val uiState: StateFlow<PensionLotteryUiState> = combine(
        draws,
        selectedTab,
        selectedRange,
        roundInput,
        groupInput,
        numberInputs,
        recentDrawLimit,
        matchNumberInput,
        statusMessage,
    ) { values ->
        val savedDraws = values[0] as List<PensionLotteryDrawEntity>
        val tab = values[1] as PensionLotteryTab
        val range = values[2] as PensionLotteryRange
        val round = values[3] as String
        val group = values[4] as String
        val numbers = values[5] as List<String>
        val recentLimit = values[6] as Int
        val matchNumber = values[7] as String
        val message = values[8] as String?
        val rangeDraws = savedDraws.take(range.weeks)
        val recentDraws = savedDraws.take(recentLimit)
        val sixteenWeekScoreBandSummary = buildSixteenWeekScoreBandSummary(savedDraws)

        PensionLotteryUiState(
            selectedTab = tab,
            selectedRange = range,
            roundInput = round,
            groupInput = group,
            numberInputs = numbers,
            matchNumberInput = matchNumber,
            statusMessage = message,
            latestRoundNo = savedDraws.firstOrNull()?.roundNo,
            totalDrawCount = savedDraws.size,
            recentDraws = recentDraws,
            hasMoreRecentDraws = recentDraws.size < savedDraws.size,
            recentDigitScores = buildRecentDigitScores(savedDraws, recentDraws, range.weeks),
            sixteenWeekScoreBandStats = sixteenWeekScoreBandSummary.stats,
            sixteenWeekScoreBandDrawCount = sixteenWeekScoreBandSummary.drawCount,
            sixteenWeekScoreBandsByRound = sixteenWeekScoreBandSummary.bandsByRound,
            sixteenWeekZeroScoreCountStats = sixteenWeekScoreBandSummary.zeroScoreCountStats,
            matchResults = buildMatchResults(rangeDraws, matchNumber),
            exactMatchRounds = findExactMatchRounds(savedDraws, matchNumber),
            matchDigitScores = calculatePensionNumberScores(rangeDraws, matchNumber),
            duplicateStats = buildDuplicateStats(rangeDraws, savedDraws),
            positionStats = buildPositionStats(rangeDraws, savedDraws),
            positionScores = buildPositionScores(rangeDraws),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PensionLotteryUiState(),
    )

    fun selectTab(tab: PensionLotteryTab) {
        selectedTab.value = tab
        statusMessage.value = null
    }

    fun selectRange(range: PensionLotteryRange) {
        selectedRange.value = range
    }

    fun updateRoundInput(value: String) {
        roundInput.value = value.filter(Char::isDigit)
    }

    fun updateGroupInput(value: String) {
        groupInput.value = value.filter(Char::isDigit).take(1)
    }

    fun updateNumberInput(index: Int, value: String) {
        if (index !in 0..5) return
        numberInputs.value = numberInputs.value.toMutableList().also { inputs ->
            inputs[index] = value.filter(Char::isDigit).takeLast(1)
        }
    }

    fun updateMatchNumberInput(value: String) {
        matchNumberInput.value = value.filter(Char::isDigit).take(6)
    }

    fun loadMoreRecentDraws() {
        recentDrawLimit.value += RECENT_DRAW_PAGE_SIZE
    }

    fun saveDraw() {
        val roundNo = roundInput.value.toIntOrNull()
        val groupNo = groupInput.value.toIntOrNull()
        val winningNumber = numberInputs.value.joinToString("")
        viewModelScope.launch {
            runCatching {
                require(roundNo != null && roundNo > 0) { "회차를 1 이상으로 입력해 주세요." }
                require(groupNo != null && groupNo in 1..5) { "조는 1부터 5 사이로 입력해 주세요." }
                require(winningNumber.length == 6) { "1등 당첨번호 6자리를 모두 입력해 주세요." }
                repository.savePensionLotteryDraw(roundNo, groupNo, winningNumber)
            }.onSuccess {
                roundInput.value = (roundNo!! + 1).toString()
                numberInputs.value = List(6) { "" }
                statusMessage.value = "${roundNo}회 연금복권 1등 당첨번호가 저장되었습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "연금복권 당첨번호 저장에 실패했습니다."
            }
        }
    }

    private companion object {
        const val RECENT_DRAW_PAGE_SIZE = 12
    }
}

data class PensionLotteryUiState(
    val selectedTab: PensionLotteryTab = PensionLotteryTab.INPUT,
    val selectedRange: PensionLotteryRange = PensionLotteryRange.SIXTEEN,
    val roundInput: String = "",
    val groupInput: String = "",
    val numberInputs: List<String> = List(6) { "" },
    val matchNumberInput: String = "",
    val statusMessage: String? = null,
    val latestRoundNo: Int? = null,
    val totalDrawCount: Int = 0,
    val recentDraws: List<PensionLotteryDrawEntity> = emptyList(),
    val hasMoreRecentDraws: Boolean = false,
    val recentDigitScores: Map<Int, List<Int>> = emptyMap(),
    val sixteenWeekScoreBandStats: List<PensionLotteryScoreBandStat> = emptyList(),
    val sixteenWeekScoreBandDrawCount: Int = 0,
    val sixteenWeekScoreBandsByRound: Map<Int, String> = emptyMap(),
    val sixteenWeekZeroScoreCountStats: List<PensionLotteryZeroScoreCountStat> = emptyList(),
    val matchResults: List<PensionLotteryMatchResult> = emptyList(),
    val exactMatchRounds: List<Int> = emptyList(),
    val matchDigitScores: List<Int> = emptyList(),
    val duplicateStats: List<PensionLotteryDuplicateStat> = emptyList(),
    val positionStats: List<PensionLotteryPositionStat> = emptyList(),
    val positionScores: List<PensionLotteryPositionScore> = emptyList(),
)

data class PensionLotteryMatchResult(
    val draw: PensionLotteryDrawEntity,
    val matchedPositions: Set<Int>,
) {
    val matchCount: Int = matchedPositions.size
}

data class PensionLotteryDigitCount(
    val digit: Int,
    val count: Int,
    val totalCount: Int,
)

data class PensionLotteryScoreBandStat(
    val label: String,
    val drawCount: Int,
)

data class PensionLotteryZeroScoreCountStat(
    val zeroScoreCount: Int,
    val drawCount: Int,
)

data class PensionLotteryDuplicateStat(
    val label: String,
    val count: Int,
    val totalCount: Int,
)

data class PensionLotteryPositionStat(
    val position: Int,
    val digits: List<PensionLotteryDigitCount>,
)

data class PensionLotteryDigitScore(
    val digit: Int,
    val score: Int,
)

data class PensionLotteryPositionScore(
    val position: Int,
    val digits: List<PensionLotteryDigitScore>,
)

private fun buildMatchResults(
    draws: List<PensionLotteryDrawEntity>,
    winningNumber: String,
): List<PensionLotteryMatchResult> {
    if (winningNumber.length != 6) return emptyList()
    return draws.map { draw ->
        PensionLotteryMatchResult(
            draw = draw,
            matchedPositions = winningNumber.indices
                .filter { index -> winningNumber[index] == draw.winningNumber[index] }
                .toSet(),
        )
    }
}

private fun findExactMatchRounds(
    draws: List<PensionLotteryDrawEntity>,
    winningNumber: String,
): List<Int> = if (winningNumber.length == 6) {
    draws.filter { draw -> draw.winningNumber == winningNumber }.map(PensionLotteryDrawEntity::roundNo)
} else {
    emptyList()
}

internal fun calculatePensionNumberScores(
    draws: List<PensionLotteryDrawEntity>,
    winningNumber: String,
): List<Int> {
    if (winningNumber.length != 6) return emptyList()
    val scoresByPosition = List(6) { IntArray(10) }
    draws.forEachIndexed { index, draw ->
        val weight = draws.size - index
        draw.winningNumber.forEachIndexed { position, digit ->
            scoresByPosition[position][digit.digitToInt()] += weight
        }
    }
    return winningNumber.mapIndexed { position, digit ->
        scoresByPosition[position][digit.digitToInt()]
    }
}

private fun buildPositionStats(
    draws: List<PensionLotteryDrawEntity>,
    allDraws: List<PensionLotteryDrawEntity>,
): List<PensionLotteryPositionStat> =
    (0 until 6).map { position ->
        PensionLotteryPositionStat(
            position = position,
            digits = (0..9).map { digit ->
                PensionLotteryDigitCount(
                    digit = digit,
                    count = draws.count { draw -> draw.winningNumber[position].digitToInt() == digit },
                    totalCount = allDraws.count { draw -> draw.winningNumber[position].digitToInt() == digit },
                )
            },
        )
    }

private fun buildDuplicateStats(
    draws: List<PensionLotteryDrawEntity>,
    allDraws: List<PensionLotteryDrawEntity>,
): List<PensionLotteryDuplicateStat> = PENSION_DUPLICATE_LABELS.map { label ->
    PensionLotteryDuplicateStat(
        label = label,
        count = draws.count { draw -> pensionDuplicateLabel(draw.winningNumber) == label },
        totalCount = allDraws.count { draw -> pensionDuplicateLabel(draw.winningNumber) == label },
    )
}

internal fun pensionDuplicateLabel(winningNumber: String): String {
    val maxDuplicateCount = winningNumber.groupingBy { digit -> digit }.eachCount().values.maxOrNull() ?: 0
    return when {
        maxDuplicateCount >= 3 -> "3자리 이상"
        maxDuplicateCount == 2 -> "2자리"
        else -> "중복 없음"
    }
}

internal val PENSION_DUPLICATE_LABELS = listOf("3자리 이상", "2자리", "중복 없음")

private fun buildPositionScores(draws: List<PensionLotteryDrawEntity>): List<PensionLotteryPositionScore> =
    (0 until 6).map { position ->
        val scores = IntArray(10)
        draws.forEachIndexed { index, draw ->
            val weight = draws.size - index
            scores[draw.winningNumber[position].digitToInt()] += weight
        }
        PensionLotteryPositionScore(
            position = position,
            digits = scores.mapIndexed { digit, score -> PensionLotteryDigitScore(digit, score) }
                .sortedWith(compareByDescending<PensionLotteryDigitScore> { it.score }.thenBy { it.digit }),
        )
    }

private fun buildRecentDigitScores(
    draws: List<PensionLotteryDrawEntity>,
    recentDraws: List<PensionLotteryDrawEntity>,
    weeks: Int,
): Map<Int, List<Int>> {
    return recentDraws.mapIndexed { startIndex, draw ->
        val scoringDraws = draws.drop(startIndex + 1).take(weeks)
        val scoresByPosition = List(6) { IntArray(10) }
        scoringDraws.forEachIndexed { index, scoringDraw ->
            val weight = scoringDraws.size - index
            scoringDraw.winningNumber.forEachIndexed { position, digit ->
                scoresByPosition[position][digit.digitToInt()] += weight
            }
        }
        draw.roundNo to draw.winningNumber.mapIndexed { position, digit ->
            scoresByPosition[position][digit.digitToInt()]
        }
    }.toMap()
}

private fun buildSixteenWeekScoreBandSummary(
    draws: List<PensionLotteryDrawEntity>,
): PensionLotteryScoreBandSummary {
    val scoresByRound = draws.mapIndexedNotNull { startIndex, draw ->
        val scoringDraws = draws.drop(startIndex + 1).take(PensionLotteryRange.SIXTEEN.weeks)
        if (scoringDraws.size < PensionLotteryRange.SIXTEEN.weeks) {
            null
        } else {
            draw.roundNo to calculatePensionNumberScores(scoringDraws, draw.winningNumber)
        }
    }
    return PensionLotteryScoreBandSummary(
        drawCount = scoresByRound.size,
        stats = PENSION_SCORE_BAND_LABELS.map { label ->
            PensionLotteryScoreBandStat(
                label = label,
                drawCount = scoresByRound.count { (_, scores) -> pensionScoreBandLabel(scores.sum()) == label },
            )
        },
        bandsByRound = scoresByRound.associate { (roundNo, scores) ->
            roundNo to pensionScoreBandLabel(scores.sum())
        },
        zeroScoreCountStats = (0..6).map { zeroScoreCount ->
            PensionLotteryZeroScoreCountStat(
                zeroScoreCount = zeroScoreCount,
                drawCount = scoresByRound.count { (_, scores) ->
                    scores.count { score -> score == 0 } == zeroScoreCount
                },
            )
        },
    )
}

internal fun pensionScoreBandLabel(score: Int): String = when {
    score <= 50 -> "50점 이하"
    score <= 70 -> "51~70점"
    score <= 90 -> "71~90점"
    score <= 110 -> "91~110점"
    else -> "111점 이상"
}

internal val PENSION_SCORE_BAND_LABELS = listOf(
    "50점 이하",
    "51~70점",
    "71~90점",
    "91~110점",
    "111점 이상",
)

private data class PensionLotteryScoreBandSummary(
    val drawCount: Int,
    val stats: List<PensionLotteryScoreBandStat>,
    val bandsByRound: Map<Int, String>,
    val zeroScoreCountStats: List<PensionLotteryZeroScoreCountStat>,
)
