package com.habittracker.ui.lotto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

class PensionLotteryGeneratorViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val draws = repository.observeAllPensionLotteryDraws()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val storedGeneratedNumbers = repository.observePensionLotteryGeneratedNumbers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val pendingGeneratedNumbers = MutableStateFlow<List<PensionLotteryGeneratedNumber>>(emptyList())
    private val statusMessage = MutableStateFlow<String?>(null)
    private val isGenerating = MutableStateFlow(false)
    private val isSaving = MutableStateFlow(false)
    private val regeneratingType = MutableStateFlow<PensionLotteryGenerationType?>(null)
    private val operationState = combine(
        statusMessage,
        isGenerating,
        isSaving,
        regeneratingType,
    ) { message, generating, saving, type ->
        PensionLotteryGeneratorOperationState(message, generating, saving, type)
    }

    val uiState: StateFlow<PensionLotteryGeneratorUiState> = combine(
        draws,
        storedGeneratedNumbers,
        pendingGeneratedNumbers,
        operationState,
    ) { savedDraws, storedNumbers, pendingNumbers, operation ->
        val analysis = buildGeneratorAnalysis(savedDraws)
        val generationHistory = buildGenerationHistory(storedNumbers)
        PensionLotteryGeneratorUiState(
            latestRoundNo = savedDraws.firstOrNull()?.roundNo,
            totalDrawCount = savedDraws.size,
            targetScoreBand = analysis?.targetScoreBand,
            targetScoreBandDrawCount = analysis?.targetScoreBandDrawCount ?: 0,
            targetDuplicateLabel = analysis?.targetDuplicateLabel,
            targetDuplicateDrawCount = analysis?.targetDuplicateDrawCount ?: 0,
            targetZeroScoreCount = analysis?.targetZeroScoreCount,
            targetZeroScoreDrawCount = analysis?.targetZeroScoreDrawCount ?: 0,
            generatedNumbers = pendingNumbers.ifEmpty { generationHistory.firstOrNull()?.numbers.orEmpty() },
            generationHistory = generationHistory,
            statusMessage = operation.statusMessage,
            isGenerating = operation.isGenerating,
            isSaving = operation.isSaving,
            regeneratingType = operation.regeneratingType,
            hasUnsavedGeneration = pendingNumbers.isNotEmpty(),
            canGenerate = analysis != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PensionLotteryGeneratorUiState(),
    )

    fun generate() {
        if (isGenerating.value || isSaving.value) return
        val savedDraws = draws.value
        viewModelScope.launch(Dispatchers.Default) {
            isGenerating.value = true
            statusMessage.value = null
            runCatching {
                val analysis = requireNotNull(buildGeneratorAnalysis(savedDraws)) {
                    "번호 생성을 위해 당첨번호가 17회 이상 필요합니다."
                }
                val appeared = generateCandidate(
                    analysis = analysis,
                    type = PensionLotteryGenerationType.APPEARED,
                )
                val coldMix = generateCandidate(
                    analysis = analysis,
                    type = PensionLotteryGenerationType.COLD_MIX,
                    comparisonNumber = appeared?.winningNumber,
                )
                val results = listOfNotNull(appeared, coldMix)
                require(results.isNotEmpty()) { "현재 조건을 만족하는 번호를 찾지 못했습니다." }
                results
            }.onSuccess { results ->
                pendingGeneratedNumbers.value = results
                statusMessage.value = if (results.size == PensionLotteryGenerationType.entries.size) {
                    "출현형과 미출현 혼합형 번호를 생성했습니다. 확인 후 저장해 주세요."
                } else {
                    "한 가지 유형만 조건을 만족했습니다. 다시 생성하면 다른 후보를 탐색합니다."
                }
            }.onFailure { error ->
                statusMessage.value = error.message ?: "연금번호 생성에 실패했습니다."
            }
            isGenerating.value = false
        }
    }

    fun regenerate(type: PensionLotteryGenerationType) {
        if (isGenerating.value || isSaving.value) return
        val savedDraws = draws.value
        val currentNumbers = pendingGeneratedNumbers.value.ifEmpty {
            buildGenerationHistory(storedGeneratedNumbers.value).firstOrNull()?.numbers.orEmpty()
        }
        val comparisonNumber = currentNumbers
            .firstOrNull { number -> number.type != type }
            ?.winningNumber
        viewModelScope.launch(Dispatchers.Default) {
            isGenerating.value = true
            regeneratingType.value = type
            statusMessage.value = null
            runCatching {
                val analysis = requireNotNull(buildGeneratorAnalysis(savedDraws)) {
                    "번호 생성을 위해 당첨번호가 17회 이상 필요합니다."
                }
                val regenerated = requireNotNull(
                    generateCandidate(
                        analysis = analysis,
                        type = type,
                        comparisonNumber = comparisonNumber,
                    ),
                ) { "현재 조건을 만족하는 ${type.label} 번호를 찾지 못했습니다." }
                (currentNumbers.filterNot { number -> number.type == type } + regenerated)
                    .sortedBy { number -> number.type.ordinal }
            }.onSuccess { results ->
                pendingGeneratedNumbers.value = results
                statusMessage.value = "${type.label} 번호를 다시 생성했습니다. 확인 후 저장해 주세요."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "${type.label} 번호 재생성에 실패했습니다."
            }
            regeneratingType.value = null
            isGenerating.value = false
        }
    }

    fun saveGeneratedNumbers() {
        if (isSaving.value || isGenerating.value) return
        val numbers = pendingGeneratedNumbers.value
        if (numbers.isEmpty()) {
            statusMessage.value = "저장할 연금 생성번호가 없습니다."
            return
        }
        viewModelScope.launch {
            isSaving.value = true
            statusMessage.value = null
            runCatching {
                val generationId = UUID.randomUUID().toString()
                val generatedAt = LocalDateTime.now()
                repository.savePensionLotteryGeneratedNumbers(
                    numbers.map { result -> result.toEntity(generationId, generatedAt) },
                )
            }.onSuccess {
                if (pendingGeneratedNumbers.value == numbers) {
                    pendingGeneratedNumbers.value = emptyList()
                }
                statusMessage.value = "연금 생성번호 ${numbers.size}개를 저장했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "연금 생성번호 저장에 실패했습니다."
            }
            isSaving.value = false
        }
    }

    fun deleteGeneration(generationId: String) {
        viewModelScope.launch {
            runCatching {
                repository.deletePensionLotteryGeneration(generationId)
            }.onSuccess {
                statusMessage.value = "연금번호 생성 히스토리를 삭제했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "연금번호 생성 히스토리 삭제에 실패했습니다."
            }
        }
    }
}

enum class PensionLotteryGenerationType(
    val label: String,
    val description: String,
) {
    APPEARED(
        label = "출현형 추천",
        description = "최근 16주에 출현한 자리별 숫자만 사용합니다.",
    ),
    COLD_MIX(
        label = "미출현 혼합형 추천",
        description = "16주 통계의 최빈 0점 개수를 적용하고, 0점이 없으면 최저점 숫자를 1~2자리에 적용합니다.",
    ),
}

data class PensionLotteryGeneratedNumber(
    val type: PensionLotteryGenerationType,
    val groupNo: Int,
    val winningNumber: String,
    val digitScores: List<Int>,
    val totalScore: Int,
    val scoreBand: String,
    val duplicateLabel: String,
    val coldPositions: Set<Int>,
    val coldPriorityScores: Map<Int, Int>,
)

data class PensionLotteryGeneratorUiState(
    val latestRoundNo: Int? = null,
    val totalDrawCount: Int = 0,
    val targetScoreBand: String? = null,
    val targetScoreBandDrawCount: Int = 0,
    val targetDuplicateLabel: String? = null,
    val targetDuplicateDrawCount: Int = 0,
    val targetZeroScoreCount: Int? = null,
    val targetZeroScoreDrawCount: Int = 0,
    val generatedNumbers: List<PensionLotteryGeneratedNumber> = emptyList(),
    val generationHistory: List<PensionLotteryGenerationHistory> = emptyList(),
    val statusMessage: String? = null,
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val regeneratingType: PensionLotteryGenerationType? = null,
    val hasUnsavedGeneration: Boolean = false,
    val canGenerate: Boolean = false,
)

private data class PensionLotteryGeneratorOperationState(
    val statusMessage: String?,
    val isGenerating: Boolean,
    val isSaving: Boolean,
    val regeneratingType: PensionLotteryGenerationType?,
)

data class PensionLotteryGenerationHistory(
    val generationId: String,
    val generatedAt: LocalDateTime,
    val numbers: List<PensionLotteryGeneratedNumber>,
)

private fun PensionLotteryGeneratedNumber.toEntity(
    generationId: String,
    generatedAt: LocalDateTime,
): PensionLotteryGeneratedNumberEntity = PensionLotteryGeneratedNumberEntity(
    generationId = generationId,
    generationType = type.name,
    groupNo = groupNo,
    winningNumber = winningNumber,
    digitScores = digitScores.joinToString(","),
    totalScore = totalScore,
    scoreBand = scoreBand,
    duplicateLabel = duplicateLabel,
    coldPositions = coldPositions.sorted().joinToString(","),
    coldPriorityScores = coldPriorityScores.entries
        .sortedBy { entry -> entry.key }
        .joinToString(",") { entry -> "${entry.key}:${entry.value}" },
    generatedAt = generatedAt,
)

private fun buildGenerationHistory(
    entities: List<PensionLotteryGeneratedNumberEntity>,
): List<PensionLotteryGenerationHistory> = entities
    .groupBy(PensionLotteryGeneratedNumberEntity::generationId)
    .mapNotNull { (generationId, batchEntities) ->
        val numbers = batchEntities.mapNotNull(PensionLotteryGeneratedNumberEntity::toGeneratedNumber)
            .sortedBy { number -> number.type.ordinal }
        if (numbers.isEmpty()) {
            null
        } else {
            PensionLotteryGenerationHistory(
                generationId = generationId,
                generatedAt = batchEntities.first().generatedAt,
                numbers = numbers,
            )
        }
    }
    .sortedByDescending(PensionLotteryGenerationHistory::generatedAt)

private fun PensionLotteryGeneratedNumberEntity.toGeneratedNumber(): PensionLotteryGeneratedNumber? {
    val type = runCatching { PensionLotteryGenerationType.valueOf(generationType) }.getOrNull() ?: return null
    val parsedDigitScores = digitScores.split(',').mapNotNull(String::toIntOrNull)
    if (parsedDigitScores.size != 6) return null
    val parsedColdPositions = coldPositions
        .split(',')
        .mapNotNull(String::toIntOrNull)
        .filter { position -> position in 0..5 }
        .toSet()
    val parsedColdPriorityScores = coldPriorityScores
        .split(',')
        .mapNotNull { value ->
            val parts = value.split(':', limit = 2)
            val position = parts.getOrNull(0)?.toIntOrNull()
            val score = parts.getOrNull(1)?.toIntOrNull()
            if (position != null && position in 0..5 && score != null) position to score else null
        }
        .toMap()
    return PensionLotteryGeneratedNumber(
        type = type,
        groupNo = groupNo,
        winningNumber = winningNumber,
        digitScores = parsedDigitScores,
        totalScore = totalScore,
        scoreBand = scoreBand,
        duplicateLabel = duplicateLabel,
        coldPositions = parsedColdPositions,
        coldPriorityScores = parsedColdPriorityScores,
    )
}

private data class PensionLotteryGeneratorAnalysis(
    val latestDraws: List<PensionLotteryDrawEntity>,
    val pastWinningNumbers: Set<String>,
    val targetScoreBand: String,
    val targetScoreBandDrawCount: Int,
    val targetDuplicateLabel: String,
    val targetDuplicateDrawCount: Int,
    val targetZeroScoreCount: Int,
    val targetZeroScoreDrawCount: Int,
    val allTimeCounts: List<IntArray>,
    val appearedDigits: List<List<Int>>,
    val topAppearedLastDigits: List<Int>,
    val zeroScoreDigits: List<List<Int>>,
    val lowestPositiveScoreDigits: List<List<Int>>,
)

private data class CandidateSelection(
    val winningNumber: String,
    val coldPositions: Set<Int>,
    val coldPriorityScores: Map<Int, Int>,
)

private fun buildGeneratorAnalysis(
    draws: List<PensionLotteryDrawEntity>,
): PensionLotteryGeneratorAnalysis? {
    if (draws.size <= GENERATOR_ANALYSIS_WEEKS) return null

    val historicalDigitScores = draws.mapIndexedNotNull { index, draw ->
        val previousDraws = draws.drop(index + 1).take(GENERATOR_ANALYSIS_WEEKS)
        if (previousDraws.size < GENERATOR_ANALYSIS_WEEKS) {
            null
        } else {
            calculatePensionNumberScores(previousDraws, draw.winningNumber)
        }
    }
    if (historicalDigitScores.isEmpty()) return null

    val scoreBandCounts = PENSION_SCORE_BAND_LABELS.associateWith { label ->
        historicalDigitScores.count { scores -> pensionScoreBandLabel(scores.sum()) == label }
    }
    val targetScoreBand = PENSION_SCORE_BAND_LABELS.maxBy { label -> scoreBandCounts.getValue(label) }
    val zeroScoreCounts = (0..6).associateWith { zeroScoreCount ->
        historicalDigitScores.count { scores -> scores.count { score -> score == 0 } == zeroScoreCount }
    }
    val targetZeroScoreCount = (0..6).maxBy { zeroScoreCount -> zeroScoreCounts.getValue(zeroScoreCount) }
    val latestDraws = draws.take(GENERATOR_ANALYSIS_WEEKS)
    val duplicateCounts = PENSION_DUPLICATE_LABELS.associateWith { label ->
        latestDraws.count { draw -> pensionDuplicateLabel(draw.winningNumber) == label }
    }
    val targetDuplicateLabel = PENSION_DUPLICATE_LABELS.maxBy { label -> duplicateCounts.getValue(label) }
    val recentWeightedScores = buildPositionWeightedScores(latestDraws)
    val allTimeCounts = buildPositionDigitCounts(draws)
    val appearedDigits = recentWeightedScores.map { scores ->
        scores.indices.filter { digit -> scores[digit] > 0 }
    }
    val zeroScoreDigits = recentWeightedScores.map { scores ->
        scores.indices.filter { digit -> scores[digit] == 0 }
    }
    val lowestPositiveScoreDigits = recentWeightedScores.map { scores ->
        val lowestPositiveScore = scores.filter { score -> score > 0 }.minOrNull() ?: 0
        scores.indices.filter { digit -> scores[digit] == lowestPositiveScore }
    }

    return PensionLotteryGeneratorAnalysis(
        latestDraws = latestDraws,
        pastWinningNumbers = draws.map(PensionLotteryDrawEntity::winningNumber).toSet(),
        targetScoreBand = targetScoreBand,
        targetScoreBandDrawCount = scoreBandCounts.getValue(targetScoreBand),
        targetDuplicateLabel = targetDuplicateLabel,
        targetDuplicateDrawCount = duplicateCounts.getValue(targetDuplicateLabel),
        targetZeroScoreCount = targetZeroScoreCount,
        targetZeroScoreDrawCount = zeroScoreCounts.getValue(targetZeroScoreCount),
        allTimeCounts = allTimeCounts,
        appearedDigits = appearedDigits,
        topAppearedLastDigits = appearedDigits.last()
            .sortedByDescending { digit -> recentWeightedScores.last()[digit] }
            .take(3),
        zeroScoreDigits = zeroScoreDigits,
        lowestPositiveScoreDigits = lowestPositiveScoreDigits,
    )
}

private fun generateCandidate(
    analysis: PensionLotteryGeneratorAnalysis,
    type: PensionLotteryGenerationType,
    comparisonNumber: String? = null,
    random: Random = Random.Default,
): PensionLotteryGeneratedNumber? {
    if (
        type == PensionLotteryGenerationType.COLD_MIX &&
        analysis.targetZeroScoreCount > 0 &&
        analysis.zeroScoreDigits.count { digits -> digits.isNotEmpty() } in 1 until analysis.targetZeroScoreCount
    ) {
        return null
    }
    val fixedAppearedLastDigit = if (type == PensionLotteryGenerationType.APPEARED) {
        analysis.topAppearedLastDigits.random(random)
    } else {
        null
    }

    repeat(MAX_GENERATION_ATTEMPTS) {
        val selection = when (type) {
            PensionLotteryGenerationType.APPEARED -> buildAppearedSelection(
                analysis = analysis,
                fixedLastDigit = requireNotNull(fixedAppearedLastDigit),
                random = random,
            )

            PensionLotteryGenerationType.COLD_MIX -> buildColdMixSelection(
                analysis = analysis,
                comparisonLastDigit = comparisonNumber?.lastOrNull()?.digitToInt(),
                random = random,
            ) ?: return@repeat
        }
        if (selection.winningNumber in analysis.pastWinningNumbers) return@repeat
        if (comparisonNumber != null && differingPositionCount(selection.winningNumber, comparisonNumber) < 3) {
            return@repeat
        }
        if (comparisonNumber != null && selection.winningNumber.last() == comparisonNumber.last()) return@repeat
        if (pensionDuplicateLabel(selection.winningNumber) != analysis.targetDuplicateLabel) return@repeat

        val digitScores = calculatePensionNumberScores(analysis.latestDraws, selection.winningNumber)
        val totalScore = digitScores.sum()
        if (pensionScoreBandLabel(totalScore) != analysis.targetScoreBand) return@repeat

        return PensionLotteryGeneratedNumber(
            type = type,
            groupNo = random.nextInt(1, 6),
            winningNumber = selection.winningNumber,
            digitScores = digitScores,
            totalScore = totalScore,
            scoreBand = analysis.targetScoreBand,
            duplicateLabel = analysis.targetDuplicateLabel,
            coldPositions = selection.coldPositions,
            coldPriorityScores = selection.coldPriorityScores,
        )
    }

    return null
}

private fun buildAppearedSelection(
    analysis: PensionLotteryGeneratorAnalysis,
    fixedLastDigit: Int,
    random: Random,
): CandidateSelection = CandidateSelection(
    winningNumber = analysis.appearedDigits.mapIndexed { position, digits ->
        if (position == LAST_DIGIT_POSITION) {
            fixedLastDigit
        } else {
            digits.random(random)
        }
    }.joinToString(""),
    coldPositions = emptySet(),
    coldPriorityScores = emptyMap(),
)

private fun buildColdMixSelection(
    analysis: PensionLotteryGeneratorAnalysis,
    comparisonLastDigit: Int?,
    random: Random,
): CandidateSelection? {
    val zeroScorePositions = analysis.zeroScoreDigits.indices.filter { position ->
        analysis.zeroScoreDigits[position].isNotEmpty()
    }
    val useZeroScoreTarget = analysis.targetZeroScoreCount > 0 && zeroScorePositions.isNotEmpty()
    val coldPositions = if (useZeroScoreTarget) {
        if (zeroScorePositions.size < analysis.targetZeroScoreCount) return null
        zeroScorePositions.shuffled(random).take(analysis.targetZeroScoreCount).toSet()
    } else {
        (0..LAST_DIGIT_POSITION).shuffled(random).take(random.nextInt(1, 3)).toSet()
    }
    val coldPriorityScores = mutableMapOf<Int, Int>()
    val digits = mutableListOf<Int>()
    for (position in 0..LAST_DIGIT_POSITION) {
        val candidates = if (position in coldPositions) {
            if (useZeroScoreTarget) {
                analysis.zeroScoreDigits[position]
            } else {
                analysis.lowestPositiveScoreDigits[position]
            }
        } else {
            analysis.appearedDigits[position]
        }.let { positionCandidates ->
            if (position == LAST_DIGIT_POSITION) {
                positionCandidates.filter { digit -> digit != comparisonLastDigit }
            } else {
                positionCandidates
            }
        }
        if (candidates.isEmpty()) return null

        val digit = if (position in coldPositions) {
            weightedColdDigit(
                analysis = analysis,
                position = position,
                candidates = candidates,
                random = random,
            ).also { selectedDigit ->
                val maxAllTimeCount = analysis.allTimeCounts[position].maxOrNull()?.coerceAtLeast(1) ?: 1
                val priorityScore = (
                    analysis.allTimeCounts[position][selectedDigit].toDouble() / maxAllTimeCount * 100.0
                    ).roundToInt()
                coldPriorityScores[position] = priorityScore
            }
        } else {
            candidates.random(random)
        }
        digits += digit
    }
    return CandidateSelection(
        winningNumber = digits.joinToString(""),
        coldPositions = coldPositions,
        coldPriorityScores = coldPriorityScores,
    )
}

private fun weightedColdDigit(
    analysis: PensionLotteryGeneratorAnalysis,
    position: Int,
    candidates: List<Int>,
    random: Random,
): Int {
    val totalWeight = candidates.sumOf { digit -> analysis.allTimeCounts[position][digit].coerceAtLeast(1) }
    var remaining = random.nextInt(totalWeight)
    candidates.forEach { digit ->
        remaining -= analysis.allTimeCounts[position][digit].coerceAtLeast(1)
        if (remaining < 0) return digit
    }
    return candidates.last()
}

private fun buildPositionDigitCounts(draws: List<PensionLotteryDrawEntity>): List<IntArray> {
    val counts = List(6) { IntArray(10) }
    draws.forEach { draw ->
        draw.winningNumber.forEachIndexed { position, digit ->
            counts[position][digit.digitToInt()]++
        }
    }
    return counts
}

private fun buildPositionWeightedScores(draws: List<PensionLotteryDrawEntity>): List<IntArray> {
    val scores = List(6) { IntArray(10) }
    draws.forEachIndexed { index, draw ->
        val weight = draws.size - index
        draw.winningNumber.forEachIndexed { position, digit ->
            scores[position][digit.digitToInt()] += weight
        }
    }
    return scores
}

private fun differingPositionCount(first: String, second: String): Int =
    first.indices.count { index -> first[index] != second[index] }

private const val GENERATOR_ANALYSIS_WEEKS = 16
private const val MAX_GENERATION_ATTEMPTS = 20_000
private const val LAST_DIGIT_POSITION = 5
