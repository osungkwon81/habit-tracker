package com.habittracker.ui.lotto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity
import com.habittracker.data.repository.HabitRepository
import com.habittracker.data.lotto.lotteryEvaluationNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import com.habittracker.data.lotto.PensionOperatingSetting
import com.habittracker.data.lotto.PensionExperimentSummary
import java.time.LocalDateTime
import java.security.MessageDigest
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
    private val generatingBackupType = MutableStateFlow<PensionLotteryGenerationType?>(null)
    private val operatingSetting = MutableStateFlow<PensionOperatingSetting?>(null)
    private val experimentState = MutableStateFlow(PensionExperimentUiState())
    internal val experimentUiState: StateFlow<PensionExperimentUiState> = experimentState
    private var experimentJob: Job? = null

    init { refreshExperiments() }

    private suspend fun currentAnalysis(savedDraws: List<PensionLotteryDrawEntity>): PensionLotteryGeneratorAnalysis? {
        requireNotNull(operatingSetting.value) { "승인 설정을 확인하지 못했습니다. 기록·설정을 새로고침해 주세요." }
        val setting = repository.pensionExperiments.setting()
        operatingSetting.value = setting
        return buildGeneratorAnalysis(savedDraws, setting.count, setting.approvalHash)
    }

    internal fun refreshExperiments() = experimentOperation { reloadExperiments() }
    internal fun registerExperiment(document: ByteArray) = experimentOperation {
        repository.pensionExperiments.register(document, repository.getPensionExperimentInput())
        reloadExperiments(); statusMessage.value = "연금 실험을 등록했습니다. 비교 계산은 실행하지 않았습니다."
    }
    internal fun captureExperiment(id: String, target: Int) = experimentOperation {
        repository.pensionExperiments.capture(id, target, repository.getPensionExperimentInput())
        reloadExperiments(); statusMessage.value = "$target 회차의 추첨 전 입력과 추천 제외 목록을 확정했습니다."
    }
    internal fun executeExperiment(id: String) = experimentOperation {
        repository.pensionExperiments.execute(id, repository.getPensionExperimentInput())
        reloadExperiments(); statusMessage.value = "연금 비교 계산과 전체 결과 기록을 완료했습니다."
    }
    internal fun approveExperiment(id: String, reason: String) = experimentOperation {
        repository.pensionExperiments.approve(id, repository.getPensionExperimentInput(), reason)
        reloadExperiments(); statusMessage.value = "승인 설정을 운영 생성에 적용했습니다. 기존 번호와 기록은 보존됩니다."
    }
    internal fun rollbackExperimentSetting(reason: String) = experimentOperation {
        repository.pensionExperiments.rollback(reason)
        reloadExperiments(); statusMessage.value = "이전 승인 설정으로 복구했습니다. 기존 기록은 보존됩니다."
    }
    internal fun cancelExperiment() { experimentJob?.cancel() }
    internal fun reportExperimentImportError(message: String) { statusMessage.value = message }
    private suspend fun reloadExperiments() {
        val setting = repository.pensionExperiments.setting()
        val summaries = repository.pensionExperiments.list()
        operatingSetting.value = setting
        experimentState.value = experimentState.value.copy(operating = setting, experiments = summaries, issue = null)
    }
    private fun experimentOperation(action: suspend () -> Unit) {
        if (experimentState.value.busy || isGenerating.value || isSaving.value) {
            statusMessage.value = "진행 중인 생성·저장·실험 작업이 끝난 뒤 다시 시도해 주세요."
            return
        }
        experimentState.value = experimentState.value.copy(busy = true)
        experimentJob = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { statusMessage.value = "작업을 취소했습니다. 기록을 새로고침해 상태를 확인하세요."; throw cancelled }
            catch (error: Exception) {
                operatingSetting.value = null
                experimentState.value = experimentState.value.copy(operating = null, issue = error.message)
                statusMessage.value = error.message ?: "연금 실험 처리에 실패했습니다."
            } finally { experimentState.value = experimentState.value.copy(busy = false) }
        }
    }
    private val operationState = combine(
        statusMessage,
        isGenerating,
        isSaving,
        regeneratingType,
        generatingBackupType,
    ) { message, generating, saving, regenerating, generatingBackup ->
        PensionLotteryGeneratorOperationState(message, generating, saving, regenerating, generatingBackup)
    }

    private val analysisState = combine(draws, operatingSetting, experimentState) { saved, setting, experiment ->
        saved to setting?.takeUnless { experiment.busy }?.let { buildGeneratorAnalysis(saved, it.count, it.approvalHash) }
    }.flowOn(Dispatchers.Default)
    private val historyState = storedGeneratedNumbers.map { buildGenerationHistory(it) to buildBackupNumbers(it) }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<PensionLotteryGeneratorUiState> = combine(
        analysisState,
        historyState,
        pendingGeneratedNumbers,
        operationState,
    ) { (savedDraws, analysis), (generationHistory, backupNumbers), pendingNumbers, operation ->
        val generatedNumbers = pendingNumbers.ifEmpty { generationHistory.firstOrNull()?.numbers.orEmpty() }
        val hasGenerationConditionChanged = analysis != null &&
            generatedNumbers.isNotEmpty() &&
            (!matchesCurrentGenerationConditions(generatedNumbers, analysis) ||
                generatedNumbers.any { it.generationSnapshot?.inputDataHash != analysis.generationSnapshot.inputDataHash || it.generationSnapshot?.configJson != analysis.generationSnapshot.configJson })
        val currentGenerationId = generationHistory.firstOrNull()?.generationId
        PensionLotteryGeneratorUiState(
            latestRoundNo = savedDraws.firstOrNull()?.roundNo,
            totalDrawCount = savedDraws.size,
            targetScoreBand = analysis?.targetScoreBand,
            targetScoreBandDrawCount = analysis?.targetScoreBandDrawCount ?: 0,
            targetDuplicateLabel = analysis?.targetDuplicateLabel,
            targetDuplicateDrawCount = analysis?.targetDuplicateDrawCount ?: 0,
            targetZeroScoreCount = analysis?.targetZeroScoreCount,
            targetZeroScoreDrawCount = analysis?.targetZeroScoreDrawCount ?: 0,
            appearedLastDigitCandidates = analysis?.topAppearedLastDigits.orEmpty(),
            coldMixLastDigitCandidates = analysis?.let(::preferredColdMixLastDigits).orEmpty(),
            generatedNumbers = generatedNumbers,
            generationHistory = generationHistory,
            backupNumbers = backupNumbers.filter { backup -> backup.parentGenerationId == currentGenerationId },
            statusMessage = operation.statusMessage,
            isGenerating = operation.isGenerating,
            isSaving = operation.isSaving,
            regeneratingType = operation.regeneratingType,
            generatingBackupType = operation.generatingBackupType,
            hasUnsavedGeneration = pendingNumbers.isNotEmpty(),
            hasGenerationConditionChanged = hasGenerationConditionChanged,
            canGenerate = analysis != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PensionLotteryGeneratorUiState(),
    )

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    fun generate() {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        val savedDraws = draws.value
        val excludedWinningNumbers = storedGeneratedNumbers.value
            .filterNot(PensionLotteryGeneratedNumberEntity::isControl)
            .map(PensionLotteryGeneratedNumberEntity::winningNumber)
            .toSet()
        isGenerating.value = true
        viewModelScope.launch(Dispatchers.Default) {
            statusMessage.value = null
            runCatching {
                requireNotNull(operatingSetting.value) { "승인 설정을 확인하지 못했습니다. 기록·설정을 새로고침해 주세요." }
                generatePensionOperatingBatch(repository.pensionExperiments, savedDraws, excludedWinningNumbers)
            }.onSuccess { results ->
                pendingGeneratedNumbers.value = results
                statusMessage.value = "출현형 2개와 미출현 혼합형 2개를 생성했습니다. 저장하면 고정됩니다."
            }.onFailure { error ->
                propagateCancellation(error)
                statusMessage.value = error.message ?: "연금번호 생성에 실패했습니다."
            }
            isGenerating.value = false
        }
    }

    fun regenerate(type: PensionLotteryGenerationType) {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        val savedDraws = draws.value
        val currentNumbers = pendingGeneratedNumbers.value
        if (currentNumbers.isEmpty()) return
        val comparisonTicket = currentNumbers
            .firstOrNull { number -> number.type.isPairWith(type) }
        val excludedWinningNumbers = (
            storedGeneratedNumbers.value
                .filterNot(PensionLotteryGeneratedNumberEntity::isControl)
                .map(PensionLotteryGeneratedNumberEntity::winningNumber) +
                currentNumbers.map(PensionLotteryGeneratedNumber::winningNumber)
            ).toSet()
        isGenerating.value = true
        viewModelScope.launch(Dispatchers.Default) {
            regeneratingType.value = type
            statusMessage.value = null
            runCatching {
                val analysis = requireNotNull(currentAnalysis(savedDraws)) {
                    "번호 생성을 위해 당첨번호가 17회 이상 필요합니다."
                }
                require(matchesCurrentGenerationConditions(currentNumbers, analysis)) {
                    GENERATION_CONDITION_CHANGED_MESSAGE
                }
                require(currentNumbers.all { it.generationSnapshot == analysis.generationSnapshot }) {
                    GENERATION_INPUT_CHANGED_MESSAGE
                }
                val regenerated = requireNotNull(
                    generateCandidate(
                        analysis = analysis,
                        type = type,
                        comparisonNumber = comparisonTicket?.winningNumber,
                        comparisonGroupNo = comparisonTicket?.groupNo,
                        excludedWinningNumbers = excludedWinningNumbers,
                    ),
                ) { "현재 조건을 만족하는 ${type.label} 번호를 찾지 못했습니다." }
                (currentNumbers.filterNot { number -> number.type == type } + regenerated)
                    .sortedBy { number -> number.type.ordinal }
            }.onSuccess { results ->
                pendingGeneratedNumbers.value = results
                statusMessage.value = "${type.label} 번호를 다시 생성했습니다. 확인 후 저장해 주세요."
            }.onFailure { error ->
                propagateCancellation(error)
                statusMessage.value = error.message ?: "${type.label} 번호 재생성에 실패했습니다."
            }
            regeneratingType.value = null
            isGenerating.value = false
        }
    }

    fun saveGeneratedNumbers() {
        if (isSaving.value || isGenerating.value || experimentState.value.busy) return
        val numbers = pendingGeneratedNumbers.value
        if (numbers.isEmpty()) {
            statusMessage.value = "저장할 연금 생성번호가 없습니다."
            return
        }
        isSaving.value = true
        viewModelScope.launch {
            statusMessage.value = null
            runCatching {
                val savedDraws = draws.value
                val analysis = requireNotNull(withContext(Dispatchers.Default) { currentAnalysis(savedDraws) }) {
                    "번호 생성을 위해 당첨번호가 17회 이상 필요합니다."
                }
                require(matchesCurrentGenerationConditions(numbers, analysis)) {
                    GENERATION_CONDITION_CHANGED_MESSAGE
                }
                val snapshot = requireNotNull(numbers.first().generationSnapshot) { GENERATION_INPUT_CHANGED_MESSAGE }
                require(numbers.all { it.generationSnapshot == snapshot } && snapshot == analysis.generationSnapshot) {
                    GENERATION_INPUT_CHANGED_MESSAGE
                }
                val generationId = "$FIXED_GENERATION_PREFIX${UUID.randomUUID()}"
                val savedAt = lotteryEvaluationNow()
                val analysisThroughRound = snapshot.analysisThroughRound
                val targetRoundNo = snapshot.targetRoundNo
                val generationConfigHash = snapshot.configJson.sha256()
                val inputDataHash = snapshot.inputDataHash
                val recommendationEntities = numbers.map { result ->
                    result.toEntity(
                        generationId = generationId,
                        savedAt = savedAt,
                        targetRoundNo = targetRoundNo,
                        analysisThroughRound = analysisThroughRound,
                        generationConfigHash = generationConfigHash,
                        inputDataHash = inputDataHash,
                        isEvaluationTarget = true,
                    )
                }
                val controlEntities = buildPensionControlEntities(
                    generationId = generationId,
                    savedAt = savedAt,
                    targetRoundNo = targetRoundNo,
                    analysisThroughRound = analysisThroughRound,
                    generationConfigHash = generationConfigHash,
                    inputDataHash = inputDataHash,
                    analysis = analysis,
                    excludedNumbers = numbers.map(PensionLotteryGeneratedNumber::winningNumber).toSet(),
                )
                repository.savePensionLotteryGeneratedNumbers(
                    recommendationEntities + controlEntities,
                )
            }.onSuccess { isEvaluationTarget ->
                if (pendingGeneratedNumbers.value == numbers) {
                    pendingGeneratedNumbers.value = emptyList()
                }
                statusMessage.value = if (isEvaluationTarget) {
                    "이번 주 고정 번호 ${numbers.size}개를 저장했습니다."
                } else {
                    "번호 ${numbers.size}개를 사후 등록했습니다. 사전 추천 성과에서는 제외됩니다."
                }
            }.onFailure { error ->
                propagateCancellation(error)
                statusMessage.value = error.message ?: "연금 생성번호 저장에 실패했습니다."
            }
            isSaving.value = false
        }
    }

    fun deleteGeneration(generationId: String) {
        if (experimentState.value.busy) return
        viewModelScope.launch {
            runCatching {
                buildBackupNumbers(storedGeneratedNumbers.value)
                    .filter { backup -> backup.parentGenerationId == generationId }
                    .forEach { backup -> repository.deletePensionLotteryGeneration(backup.generationId) }
                repository.deletePensionLotteryGeneration(generationId)
            }.onSuccess {
                statusMessage.value = "연금번호 생성 히스토리를 삭제했습니다."
            }.onFailure { error ->
                propagateCancellation(error)
                statusMessage.value = error.message ?: "연금번호 생성 히스토리 삭제에 실패했습니다."
            }
        }
    }

    fun generateBackup(type: PensionLotteryGenerationType) {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        val currentHistory = buildGenerationHistory(storedGeneratedNumbers.value).firstOrNull() ?: return
        if (buildBackupNumbers(storedGeneratedNumbers.value).any { backup ->
                backup.parentGenerationId == currentHistory.generationId && backup.number.type == type
            }
        ) {
            statusMessage.value = "${type.label} 예비 번호가 이미 저장되어 있습니다."
            return
        }
        val comparisonTicket = currentHistory.numbers
            .firstOrNull { number -> number.type.isPairWith(type) }
        val excludedWinningNumbers = storedGeneratedNumbers.value
            .filterNot(PensionLotteryGeneratedNumberEntity::isControl)
            .map(PensionLotteryGeneratedNumberEntity::winningNumber)
            .toSet()
        isGenerating.value = true
        viewModelScope.launch(Dispatchers.Default) {
            generatingBackupType.value = type
            statusMessage.value = null
            runCatching {
                val analysis = requireNotNull(currentAnalysis(draws.value)) {
                    "번호 생성을 위해 당첨번호가 17회 이상 필요합니다."
                }
                val backup = requireNotNull(
                    generateCandidate(
                        analysis = analysis,
                        type = type,
                        comparisonNumber = comparisonTicket?.winningNumber,
                        comparisonGroupNo = comparisonTicket?.groupNo,
                        excludedWinningNumbers = excludedWinningNumbers,
                    ),
                ) { "현재 조건을 만족하는 ${type.label} 예비 번호를 찾지 못했습니다." }
                val generationId = "$BACKUP_GENERATION_PREFIX${currentHistory.generationId}:$type"
                repository.savePensionLotteryGeneratedNumbers(
                    listOf(backup.toEntity(generationId, lotteryEvaluationNow())),
                )
            }.onSuccess {
                statusMessage.value = "${type.label} 예비 번호를 저장했습니다."
            }.onFailure { error ->
                propagateCancellation(error)
                statusMessage.value = error.message ?: "예비 번호 생성에 실패했습니다."
            }
            generatingBackupType.value = null
            isGenerating.value = false
        }
    }

    fun deleteBackup(generationId: String) {
        if (experimentState.value.busy) return
        viewModelScope.launch {
            runCatching {
                repository.deletePensionLotteryGeneration(generationId)
            }.onSuccess {
                statusMessage.value = "예비 번호를 삭제했습니다."
            }.onFailure { error ->
                propagateCancellation(error)
                statusMessage.value = error.message ?: "예비 번호 삭제에 실패했습니다."
            }
        }
    }
    private fun propagateCancellation(error: Throwable) {
        if (error is CancellationException) {
            isGenerating.value = false; isSaving.value = false
            regeneratingType.value = null; generatingBackupType.value = null
            throw error
        }
    }
}

internal data class PensionExperimentUiState(
    val operating: PensionOperatingSetting? = null,
    val experiments: List<PensionExperimentSummary> = emptyList(),
    val busy: Boolean = false,
    val issue: String? = null,
)

enum class PensionLotteryGenerationType(
    val label: String,
    val description: String,
    val pattern: PensionLotteryNumberPattern,
    val pairIndex: Int,
) {
    APPEARED(
        label = "출현형 추천 1",
        description = "최근 16주에 출현한 자리별 숫자만 사용합니다.",
        pattern = PensionLotteryNumberPattern.APPEARED,
        pairIndex = 1,
    ),
    APPEARED_SECOND(
        label = "출현형 추천 2",
        description = "최근 16주에 출현한 자리별 숫자만 사용합니다.",
        pattern = PensionLotteryNumberPattern.APPEARED,
        pairIndex = 2,
    ),
    COLD_MIX(
        label = "미출현 혼합형 추천 1",
        description = "16주 통계의 최빈 0점 개수를 적용하고, 0점이 없으면 최저점 숫자를 1~2자리에 적용합니다.",
        pattern = PensionLotteryNumberPattern.COLD_MIX,
        pairIndex = 1,
    ),
    COLD_MIX_SECOND(
        label = "미출현 혼합형 추천 2",
        description = "16주 통계의 최빈 0점 개수를 적용하고, 0점이 없으면 최저점 숫자를 1~2자리에 적용합니다.",
        pattern = PensionLotteryNumberPattern.COLD_MIX,
        pairIndex = 2,
    ),
    ;

    fun isPairWith(other: PensionLotteryGenerationType): Boolean =
        pairIndex == other.pairIndex && pattern != other.pattern
}

enum class PensionLotteryNumberPattern {
    APPEARED,
    COLD_MIX,
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
    val generationSeed: Long,
    val generatedAt: LocalDateTime,
    val generationSnapshot: PensionLotteryGenerationSnapshot? = null,
)

data class PensionLotteryGenerationSnapshot(
    val analysisThroughRound: Int,
    val inputDataHash: String,
    val configJson: String,
) {
    val targetRoundNo: Int get() = analysisThroughRound + 1
}

data class PensionLotteryGeneratorUiState(
    val latestRoundNo: Int? = null,
    val totalDrawCount: Int = 0,
    val targetScoreBand: String? = null,
    val targetScoreBandDrawCount: Int = 0,
    val targetDuplicateLabel: String? = null,
    val targetDuplicateDrawCount: Int = 0,
    val targetZeroScoreCount: Int? = null,
    val targetZeroScoreDrawCount: Int = 0,
    val appearedLastDigitCandidates: List<Int> = emptyList(),
    val coldMixLastDigitCandidates: List<Int> = emptyList(),
    val generatedNumbers: List<PensionLotteryGeneratedNumber> = emptyList(),
    val generationHistory: List<PensionLotteryGenerationHistory> = emptyList(),
    val backupNumbers: List<PensionLotteryBackupNumber> = emptyList(),
    val statusMessage: String? = null,
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val regeneratingType: PensionLotteryGenerationType? = null,
    val generatingBackupType: PensionLotteryGenerationType? = null,
    val hasUnsavedGeneration: Boolean = false,
    val hasGenerationConditionChanged: Boolean = false,
    val canGenerate: Boolean = false,
)

private data class PensionLotteryGeneratorOperationState(
    val statusMessage: String?,
    val isGenerating: Boolean,
    val isSaving: Boolean,
    val regeneratingType: PensionLotteryGenerationType?,
    val generatingBackupType: PensionLotteryGenerationType?,
)

data class PensionLotteryGenerationHistory(
    val generationId: String,
    val generatedAt: LocalDateTime,
    val numbers: List<PensionLotteryGeneratedNumber>,
)

data class PensionLotteryBackupNumber(
    val generationId: String,
    val parentGenerationId: String,
    val generatedAt: LocalDateTime,
    val number: PensionLotteryGeneratedNumber,
)

private fun PensionLotteryGeneratedNumber.toEntity(
    generationId: String,
    savedAt: LocalDateTime,
    targetRoundNo: Int? = generationSnapshot?.targetRoundNo,
    analysisThroughRound: Int? = generationSnapshot?.analysisThroughRound,
    generationConfigHash: String? = generationSnapshot?.configJson?.sha256(),
    inputDataHash: String? = generationSnapshot?.inputDataHash,
    isEvaluationTarget: Boolean = false,
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
    savedAt = savedAt,
    targetRoundNo = targetRoundNo,
    analysisThroughRound = analysisThroughRound,
    generationVersion = PENSION_GENERATION_VERSION,
    generationConfigHash = generationConfigHash,
    generationConfigJson = generationSnapshot?.configJson,
    inputDataHash = inputDataHash,
    generationSeed = generationSeed,
    isEvaluationTarget = isEvaluationTarget,
)

private fun buildGenerationHistory(
    entities: List<PensionLotteryGeneratedNumberEntity>,
): List<PensionLotteryGenerationHistory> = entities
    .filterNot { entity -> entity.isHidden || entity.generationId.startsWith(BACKUP_GENERATION_PREFIX) }
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

private fun buildBackupNumbers(
    entities: List<PensionLotteryGeneratedNumberEntity>,
): List<PensionLotteryBackupNumber> = entities.mapNotNull { entity ->
    if (entity.isHidden || !entity.generationId.startsWith(BACKUP_GENERATION_PREFIX)) return@mapNotNull null
    val parentAndType = entity.generationId.removePrefix(BACKUP_GENERATION_PREFIX)
    val parentGenerationId = parentAndType.substringBeforeLast(':', missingDelimiterValue = "")
    if (parentGenerationId.isBlank()) return@mapNotNull null
    val number = entity.toGeneratedNumber() ?: return@mapNotNull null
    PensionLotteryBackupNumber(
        generationId = entity.generationId,
        parentGenerationId = parentGenerationId,
        generatedAt = entity.generatedAt,
        number = number,
    )
}.sortedBy { backup -> backup.number.type.ordinal }

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
        generationSeed = generationSeed ?: 0L,
        generatedAt = generatedAt,
        generationSnapshot = if (analysisThroughRound != null && inputDataHash != null && generationConfigJson != null) {
            PensionLotteryGenerationSnapshot(analysisThroughRound, inputDataHash, generationConfigJson)
        } else null,
    )
}

private data class PensionLotteryGeneratorAnalysis(
    val generationSnapshot: PensionLotteryGenerationSnapshot,
    val latestDraws: List<PensionLotteryDrawEntity>,
    val pastWinningNumbers: Set<String>,
    val targetScoreBand: String,
    val targetScoreBandDrawCount: Int,
    val targetDuplicateLabel: String,
    val targetDuplicateDrawCount: Int,
    val targetZeroScoreCount: Int,
    val targetZeroScoreDrawCount: Int,
    val allTimeCounts: List<IntArray>,
    val trendWeightedScores: List<IntArray>,
    val groupSelectionWeights: Map<Int, Int>,
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

private fun matchesCurrentGenerationConditions(
    numbers: List<PensionLotteryGeneratedNumber>,
    analysis: PensionLotteryGeneratorAnalysis,
): Boolean {
    if (numbers.isEmpty()) return false

    val numberByType = numbers.associateBy(PensionLotteryGeneratedNumber::type)
    if (
        numbers.size != PensionLotteryGenerationType.entries.size ||
        numberByType.keys != PensionLotteryGenerationType.entries.toSet()
    ) {
        return false
    }
    for (pairIndex in 1..2) {
        val appearedNumber = numbers.firstOrNull { number ->
            number.type.pairIndex == pairIndex && number.type.pattern == PensionLotteryNumberPattern.APPEARED
        }
        val coldMixNumber = numbers.firstOrNull { number ->
            number.type.pairIndex == pairIndex && number.type.pattern == PensionLotteryNumberPattern.COLD_MIX
        }
        if (
            appearedNumber == null ||
            coldMixNumber == null ||
            differingPositionCount(appearedNumber.winningNumber, coldMixNumber.winningNumber) < 3 ||
            appearedNumber.winningNumber.last() == coldMixNumber.winningNumber.last() ||
            appearedNumber.groupNo == coldMixNumber.groupNo
        ) {
            return false
        }
    }

    return numbers.all { number ->
        if (number.winningNumber in analysis.pastWinningNumbers) return@all false
        if (pensionDuplicateLabel(number.winningNumber) != analysis.targetDuplicateLabel) return@all false
        if (number.duplicateLabel != analysis.targetDuplicateLabel) return@all false

        val currentDigitScores = calculatePensionNumberScores(analysis.latestDraws, number.winningNumber)
        if (pensionScoreBandLabel(currentDigitScores.sum()) != analysis.targetScoreBand) return@all false
        if (number.scoreBand != analysis.targetScoreBand) return@all false

        val digits = number.winningNumber.map { digit -> digit.digitToInt() }
        when (number.type.pattern) {
            PensionLotteryNumberPattern.APPEARED -> {
                number.coldPositions.isEmpty() &&
                    digits.indices.all { position -> digits[position] in analysis.appearedDigits[position] } &&
                    digits.last() in analysis.topAppearedLastDigits
            }

            PensionLotteryNumberPattern.COLD_MIX -> {
                val usesZeroScoreTarget = analysis.targetZeroScoreCount > 0
                val hasExpectedColdPositionCount = if (usesZeroScoreTarget) {
                    number.coldPositions.size == analysis.targetZeroScoreCount
                } else {
                    number.coldPositions.size in 1..2
                }
                val hasPriorityLastDigit = when {
                    usesZeroScoreTarget && analysis.zeroScoreDigits[LAST_DIGIT_POSITION].isNotEmpty() ->
                        LAST_DIGIT_POSITION in number.coldPositions
                    !usesZeroScoreTarget -> LAST_DIGIT_POSITION in number.coldPositions
                    else -> true
                }
                hasExpectedColdPositionCount && hasPriorityLastDigit && digits.indices.all { position ->
                    val allowedDigits = when {
                        position !in number.coldPositions -> analysis.appearedDigits[position]
                        usesZeroScoreTarget -> analysis.zeroScoreDigits[position]
                        else -> analysis.lowestPositiveScoreDigits[position]
                    }
                    digits[position] in allowedDigits
                }
            }
        }
    }
}

private fun buildGeneratorAnalysis(
    draws: List<PensionLotteryDrawEntity>,
    appearedLastDigitCandidateCount: Int = 3,
    approvalHash: String? = null,
): PensionLotteryGeneratorAnalysis? {
    require(appearedLastDigitCandidateCount in 3..4)
    if (draws.size <= GENERATOR_ANALYSIS_WEEKS) return null

    val historicalDigitScores = draws.mapIndexedNotNull { index, draw ->
        val previousDraws = draws.subList(index + 1, minOf(draws.size, index + 1 + GENERATOR_ANALYSIS_WEEKS))
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
    val longTermDraws = draws.take(LONG_TERM_TREND_WEEKS)
    val recentDuplicateCounts = PENSION_DUPLICATE_LABELS.associateWith { label ->
        latestDraws.count { draw -> pensionDuplicateLabel(draw.winningNumber) == label }
    }
    val longTermDuplicateCounts = PENSION_DUPLICATE_LABELS.associateWith { label ->
        longTermDraws.count { draw -> pensionDuplicateLabel(draw.winningNumber) == label }
    }
    val targetDuplicateLabel = PENSION_DUPLICATE_LABELS.maxBy { label ->
        blendedFrequencyScore(
            recentCount = recentDuplicateCounts.getValue(label),
            recentTotal = latestDraws.size,
            longTermCount = longTermDuplicateCounts.getValue(label),
            longTermTotal = longTermDraws.size,
        )
    }
    val recentWeightedScores = buildPositionWeightedScores(latestDraws)
    val longTermWeightedScores = buildPositionWeightedScores(longTermDraws)
    val trendWeightedScores = blendPositionScores(
        recentScores = recentWeightedScores,
        longTermScores = longTermWeightedScores,
    )
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
        generationSnapshot = PensionLotteryGenerationSnapshot(
            analysisThroughRound = draws.first().roundNo,
            inputDataHash = pensionInputDataHash(draws),
            configJson = pensionExperimentConfiguration(appearedLastDigitCandidateCount, approvalHash),
        ),
        latestDraws = latestDraws,
        pastWinningNumbers = draws.map(PensionLotteryDrawEntity::winningNumber).toSet(),
        targetScoreBand = targetScoreBand,
        targetScoreBandDrawCount = scoreBandCounts.getValue(targetScoreBand),
        targetDuplicateLabel = targetDuplicateLabel,
        targetDuplicateDrawCount = recentDuplicateCounts.getValue(targetDuplicateLabel),
        targetZeroScoreCount = targetZeroScoreCount,
        targetZeroScoreDrawCount = zeroScoreCounts.getValue(targetZeroScoreCount),
        allTimeCounts = allTimeCounts,
        trendWeightedScores = trendWeightedScores,
        groupSelectionWeights = buildGroupSelectionWeights(latestDraws, longTermDraws),
        appearedDigits = appearedDigits,
        topAppearedLastDigits = appearedDigits.last()
            .sortedByDescending { digit -> trendWeightedScores.last()[digit] }
            .take(appearedLastDigitCandidateCount),
        zeroScoreDigits = zeroScoreDigits,
        lowestPositiveScoreDigits = lowestPositiveScoreDigits,
    )
}

private fun generateCandidateSet(
    analysis: PensionLotteryGeneratorAnalysis,
    excludedWinningNumbers: Set<String>,
    random: Random = Random.Default,
    trace: PensionGenerationTrace? = null,
): List<PensionLotteryGeneratedNumber>? {
    val pairTypes = listOf(
        PensionLotteryGenerationType.APPEARED to PensionLotteryGenerationType.COLD_MIX,
        PensionLotteryGenerationType.APPEARED_SECOND to PensionLotteryGenerationType.COLD_MIX_SECOND,
    )
    repeat(MAX_SET_GENERATION_ATTEMPTS) {
        val selectedNumbers = mutableListOf<PensionLotteryGeneratedNumber>()
        var failed = false
        pairTypes.forEach { (appearedType, coldMixType) ->
            if (failed) return@forEach
            val selectedWinningNumbers = selectedNumbers.map(PensionLotteryGeneratedNumber::winningNumber).toSet()
            val appearedCandidate = generateCandidate(
                analysis = analysis,
                type = appearedType,
                excludedWinningNumbers = excludedWinningNumbers + selectedWinningNumbers,
                maximumAttempts = MAX_SET_CANDIDATE_ATTEMPTS,
                generationSeed = random.nextLong(),
                trace = trace,
            )
            if (appearedCandidate == null) {
                failed = true
                return@forEach
            }
            val coldMixCandidate = generateCandidate(
                analysis = analysis,
                type = coldMixType,
                comparisonNumber = appearedCandidate.winningNumber,
                comparisonGroupNo = appearedCandidate.groupNo,
                excludedWinningNumbers = excludedWinningNumbers + selectedWinningNumbers + appearedCandidate.winningNumber,
                maximumAttempts = MAX_SET_CANDIDATE_ATTEMPTS,
                generationSeed = random.nextLong(),
                trace = trace,
            )
            if (coldMixCandidate == null) {
                failed = true
                return@forEach
            }
            selectedNumbers += appearedCandidate
            selectedNumbers += coldMixCandidate
        }
        if (!failed && selectedNumbers.size == PensionLotteryGenerationType.entries.size) {
            return selectedNumbers.sortedBy { number -> number.type.ordinal }
        }
    }
    return null
}

private fun generateCandidate(
    analysis: PensionLotteryGeneratorAnalysis,
    type: PensionLotteryGenerationType,
    comparisonNumber: String? = null,
    comparisonGroupNo: Int? = null,
    excludedWinningNumbers: Set<String> = emptySet(),
    maximumAttempts: Int = MAX_GENERATION_ATTEMPTS,
    generationSeed: Long = Random.nextLong(),
    trace: PensionGenerationTrace? = null,
): PensionLotteryGeneratedNumber? {
    val random = Random(generationSeed)
    if (
        type.pattern == PensionLotteryNumberPattern.COLD_MIX &&
        analysis.targetZeroScoreCount > 0 &&
        analysis.zeroScoreDigits.count { digits -> digits.isNotEmpty() } < analysis.targetZeroScoreCount
    ) {
        return null
    }
    repeat(maximumAttempts) {
        trace?.attempt()
        val selection = when (type.pattern) {
            PensionLotteryNumberPattern.APPEARED -> buildAppearedSelection(
                analysis = analysis,
                comparisonLastDigit = comparisonNumber?.lastOrNull()?.digitToInt(),
                random = random,
            )

            PensionLotteryNumberPattern.COLD_MIX -> buildColdMixSelection(
                analysis = analysis,
                comparisonLastDigit = comparisonNumber?.lastOrNull()?.digitToInt(),
                random = random,
            )
        }
        if (!pensionFilter(trace, "selection", selection != null)) return@repeat
        requireNotNull(selection)
        if (!pensionFilter(trace, "pastWinner", selection.winningNumber !in analysis.pastWinningNumbers)) return@repeat
        if (!pensionFilter(trace, "recommendationAndBatch", selection.winningNumber !in excludedWinningNumbers)) return@repeat
        if (!pensionFilter(trace, "pairDifference", comparisonNumber == null || differingPositionCount(selection.winningNumber, comparisonNumber) >= 3)) {
            return@repeat
        }
        if (!pensionFilter(trace, "pairLastDigit", comparisonNumber == null || selection.winningNumber.last() != comparisonNumber.last())) return@repeat
        if (!pensionFilter(trace, "duplicateType", pensionDuplicateLabel(selection.winningNumber) == analysis.targetDuplicateLabel)) return@repeat

        val digitScores = calculatePensionNumberScores(analysis.latestDraws, selection.winningNumber)
        val totalScore = digitScores.sum()
        if (!pensionFilter(trace, "scoreBand", pensionScoreBandLabel(totalScore) == analysis.targetScoreBand)) return@repeat
        val availableGroups = (1..5).filter { groupNo -> groupNo != comparisonGroupNo }
        if (!pensionFilter(trace, "group", availableGroups.isNotEmpty())) return@repeat

        return PensionLotteryGeneratedNumber(
            type = type,
            groupNo = weightedGroup(
                candidates = availableGroups,
                weights = analysis.groupSelectionWeights,
                random = random,
            ),
            winningNumber = selection.winningNumber,
            digitScores = digitScores,
            totalScore = totalScore,
            scoreBand = analysis.targetScoreBand,
            duplicateLabel = analysis.targetDuplicateLabel,
            coldPositions = selection.coldPositions,
            coldPriorityScores = selection.coldPriorityScores,
            generationSeed = generationSeed,
            generatedAt = lotteryEvaluationNow(),
            generationSnapshot = analysis.generationSnapshot,
        )
    }

    return null
}

private fun buildAppearedSelection(
    analysis: PensionLotteryGeneratorAnalysis,
    comparisonLastDigit: Int?,
    random: Random,
): CandidateSelection? {
    val coldMixLastDigits = preferredColdMixLastDigits(analysis)
    val lastDigitCandidates = analysis.topAppearedLastDigits.filter { digit ->
        digit != comparisonLastDigit &&
            (comparisonLastDigit != null || coldMixLastDigits.any { coldDigit -> coldDigit != digit })
    }
    if (lastDigitCandidates.isEmpty()) return null

    val digits = MutableList(LAST_DIGIT_POSITION + 1) { 0 }
    digits[LAST_DIGIT_POSITION] = weightedDigit(
        candidates = lastDigitCandidates,
        random = random,
    ) { digit -> analysis.trendWeightedScores[LAST_DIGIT_POSITION][digit] }
    for (position in 0 until LAST_DIGIT_POSITION) {
        digits[position] = analysis.appearedDigits[position].random(random)
    }
    return CandidateSelection(
        winningNumber = digits.joinToString(""),
        coldPositions = emptySet(),
        coldPriorityScores = emptyMap(),
    )
}

private fun buildColdMixSelection(
    analysis: PensionLotteryGeneratorAnalysis,
    comparisonLastDigit: Int?,
    random: Random,
): CandidateSelection? {
    val zeroScorePositions = analysis.zeroScoreDigits.indices.filter { position ->
        analysis.zeroScoreDigits[position].isNotEmpty()
    }
    val useZeroScoreTarget = analysis.targetZeroScoreCount > 0
    val coldPositions = if (useZeroScoreTarget) {
        if (zeroScorePositions.size < analysis.targetZeroScoreCount) return null
        if (LAST_DIGIT_POSITION in zeroScorePositions) {
            setOf(LAST_DIGIT_POSITION) + zeroScorePositions
                .filter { position -> position != LAST_DIGIT_POSITION }
                .shuffled(random)
                .take(analysis.targetZeroScoreCount - 1)
        } else {
            zeroScorePositions.shuffled(random).take(analysis.targetZeroScoreCount).toSet()
        }
    } else {
        val coldPositionCount = random.nextInt(1, 3)
        setOf(LAST_DIGIT_POSITION) + (0 until LAST_DIGIT_POSITION)
            .shuffled(random)
            .take(coldPositionCount - 1)
    }
    val coldPriorityScores = mutableMapOf<Int, Int>()
    val digits = MutableList(LAST_DIGIT_POSITION + 1) { 0 }

    fun candidatesFor(position: Int): List<Int> = if (position in coldPositions) {
        if (useZeroScoreTarget) {
            analysis.zeroScoreDigits[position]
        } else {
            analysis.lowestPositiveScoreDigits[position]
        }
    } else {
        analysis.appearedDigits[position]
    }

    fun selectDigit(position: Int, candidates: List<Int>): Int {
        return if (position in coldPositions) {
            weightedDigit(
                candidates = candidates,
                random = random,
            ) { digit -> analysis.allTimeCounts[position][digit] }.also { selectedDigit ->
                val maxAllTimeCount = analysis.allTimeCounts[position].maxOrNull()?.coerceAtLeast(1) ?: 1
                val priorityScore = (
                    analysis.allTimeCounts[position][selectedDigit].toDouble() / maxAllTimeCount * 100.0
                    ).roundToInt()
                coldPriorityScores[position] = priorityScore
            }
        } else if (position == LAST_DIGIT_POSITION) {
            weightedDigit(
                candidates = candidates,
                random = random,
            ) { digit -> analysis.trendWeightedScores[position][digit] }
        } else {
            candidates.random(random)
        }
    }

    val lastDigitCandidates = candidatesFor(LAST_DIGIT_POSITION)
        .filter { digit -> digit != comparisonLastDigit }
    if (lastDigitCandidates.isEmpty()) return null
    digits[LAST_DIGIT_POSITION] = selectDigit(LAST_DIGIT_POSITION, lastDigitCandidates)

    for (position in 0 until LAST_DIGIT_POSITION) {
        val candidates = candidatesFor(position)
        if (candidates.isEmpty()) return null
        digits[position] = selectDigit(position, candidates)
    }
    return CandidateSelection(
        winningNumber = digits.joinToString(""),
        coldPositions = coldPositions,
        coldPriorityScores = coldPriorityScores,
    )
}

private fun preferredColdMixLastDigits(analysis: PensionLotteryGeneratorAnalysis): List<Int> = when {
    analysis.targetZeroScoreCount > 0 && analysis.zeroScoreDigits[LAST_DIGIT_POSITION].isNotEmpty() ->
        analysis.zeroScoreDigits[LAST_DIGIT_POSITION]
    analysis.targetZeroScoreCount == 0 -> analysis.lowestPositiveScoreDigits[LAST_DIGIT_POSITION]
    else -> analysis.appearedDigits[LAST_DIGIT_POSITION]
}

private fun buildPensionControlEntities(
    generationId: String,
    savedAt: LocalDateTime,
    targetRoundNo: Int,
    analysisThroughRound: Int,
    generationConfigHash: String,
    inputDataHash: String,
    analysis: PensionLotteryGeneratorAnalysis,
    excludedNumbers: Set<String>,
): List<PensionLotteryGeneratedNumberEntity> {
    val usedNumbers = excludedNumbers.toMutableSet()
    return List(PENSION_CONTROL_COUNT) { index ->
        var seed: Long
        var random: Random
        var number: String
        do {
            seed = Random.nextLong()
            random = Random(seed)
            number = buildString {
                repeat(6) { append(random.nextInt(10)) }
            }
        } while (!usedNumbers.add(number))
        val digitScores = calculatePensionNumberScores(analysis.latestDraws, number)
        PensionLotteryGeneratedNumberEntity(
            generationId = generationId,
            generationType = "CONTROL_${index + 1}",
            groupNo = random.nextInt(1, 6),
            winningNumber = number,
            digitScores = digitScores.joinToString(","),
            totalScore = digitScores.sum(),
            scoreBand = pensionScoreBandLabel(digitScores.sum()),
            duplicateLabel = pensionDuplicateLabel(number),
            coldPositions = "",
            coldPriorityScores = "",
            generatedAt = savedAt,
            savedAt = savedAt,
            targetRoundNo = targetRoundNo,
            analysisThroughRound = analysisThroughRound,
            generationVersion = PENSION_GENERATION_VERSION,
            generationConfigHash = generationConfigHash,
            generationConfigJson = analysis.generationSnapshot.configJson,
            inputDataHash = inputDataHash,
            generationSeed = seed,
            isControl = true,
            isEvaluationTarget = true,
        )
    }
}

private fun pensionInputDataHash(draws: List<PensionLotteryDrawEntity>): String =
    com.habittracker.data.lotto.pensionGenerationInputHash(draws)

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun weightedDigit(
    candidates: List<Int>,
    random: Random,
    weight: (Int) -> Int,
): Int {
    val totalWeight = candidates.sumOf { digit -> weight(digit).coerceAtLeast(1) }
    var remaining = random.nextInt(totalWeight)
    candidates.forEach { digit ->
        remaining -= weight(digit).coerceAtLeast(1)
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

private fun blendPositionScores(
    recentScores: List<IntArray>,
    longTermScores: List<IntArray>,
): List<IntArray> = recentScores.indices.map { position ->
    val recentTotal = recentScores[position].sum().coerceAtLeast(1)
    val longTermTotal = longTermScores[position].sum().coerceAtLeast(1)
    IntArray(10) { digit ->
        (blendedFrequencyScore(
            recentCount = recentScores[position][digit],
            recentTotal = recentTotal,
            longTermCount = longTermScores[position][digit],
            longTermTotal = longTermTotal,
        ) * TREND_SCORE_SCALE).roundToInt()
    }
}

private fun buildGroupSelectionWeights(
    recentDraws: List<PensionLotteryDrawEntity>,
    longTermDraws: List<PensionLotteryDrawEntity>,
): Map<Int, Int> = (1..5).associateWith { groupNo ->
    val recentFrequency = recentDraws.count { draw -> draw.groupNo == groupNo }.toDouble() /
        recentDraws.size.coerceAtLeast(1)
    val longTermFrequency = longTermDraws.count { draw -> draw.groupNo == groupNo }.toDouble() /
        longTermDraws.size.coerceAtLeast(1)
    val adjustedFrequency = UNIFORM_GROUP_WEIGHT * UNIFORM_GROUP_FREQUENCY +
        RECENT_TREND_WEIGHT * recentFrequency +
        LONG_TERM_GROUP_WEIGHT * longTermFrequency
    (adjustedFrequency * TREND_SCORE_SCALE).roundToInt().coerceAtLeast(1)
}

private fun blendedFrequencyScore(
    recentCount: Int,
    recentTotal: Int,
    longTermCount: Int,
    longTermTotal: Int,
): Double = RECENT_TREND_WEIGHT * recentCount.toDouble() / recentTotal.coerceAtLeast(1) +
    LONG_TERM_TREND_WEIGHT * longTermCount.toDouble() / longTermTotal.coerceAtLeast(1)

private fun weightedGroup(
    candidates: List<Int>,
    weights: Map<Int, Int>,
    random: Random,
): Int {
    val totalWeight = candidates.sumOf { groupNo -> weights[groupNo]?.coerceAtLeast(1) ?: 1 }
    var remaining = random.nextInt(totalWeight)
    candidates.forEach { groupNo ->
        remaining -= weights[groupNo]?.coerceAtLeast(1) ?: 1
        if (remaining < 0) return groupNo
    }
    return candidates.last()
}

private fun differingPositionCount(first: String, second: String): Int =
    first.indices.count { index -> first[index] != second[index] }

private const val GENERATOR_ANALYSIS_WEEKS = 16
private const val LONG_TERM_TREND_WEEKS = 156
private const val RECENT_TREND_WEIGHT = 0.25
private const val LONG_TERM_TREND_WEIGHT = 0.75
private const val UNIFORM_GROUP_WEIGHT = 0.25
private const val LONG_TERM_GROUP_WEIGHT = 0.50
private const val UNIFORM_GROUP_FREQUENCY = 0.20
private const val TREND_SCORE_SCALE = 1_000
private const val MAX_GENERATION_ATTEMPTS = 20_000
private const val MAX_SET_GENERATION_ATTEMPTS = 10
private const val MAX_SET_CANDIDATE_ATTEMPTS = 2_000
private const val LAST_DIGIT_POSITION = 5
private const val GENERATION_CONDITION_CHANGED_MESSAGE =
    "최근 당첨번호 반영으로 번호 생성 적용 조건이 변경되었습니다. 네 번호를 다시 생성해 주세요."
private const val GENERATION_INPUT_CHANGED_MESSAGE =
    "생성 당시 분석 입력이 없거나 현재 입력과 다릅니다. 네 번호를 다시 생성해 주세요."
private const val FIXED_GENERATION_PREFIX = "fixed:"
private const val BACKUP_GENERATION_PREFIX = "backup:"
private const val PENSION_CONTROL_COUNT = 4
private const val PENSION_GENERATION_VERSION = "pension-collection-v1"
private val PENSION_GENERATION_CONFIG = """
    {
      "implementationId": "pension-snapshot-v1",
      "recent": $GENERATOR_ANALYSIS_WEEKS, "long": $LONG_TERM_TREND_WEEKS,
      "recentWeight": $RECENT_TREND_WEIGHT, "longWeight": $LONG_TERM_TREND_WEIGHT,
      "types": {"appeared": 2, "coldMix": 2}, "appearedLastDigitCandidateCount": 3,
      "groupWeights": {"uniform": $UNIFORM_GROUP_WEIGHT, "recent": $RECENT_TREND_WEIGHT, "long": $LONG_TERM_GROUP_WEIGHT},
      "trendScoreScale": $TREND_SCORE_SCALE,
      "scoreBandUpperBounds": [50, 70, 90, 110], "scoreBandPolicy": "historical_mode",
      "duplicateTypes": ["tripleOrMore", "twoPairsOrMore", "onePair", "none"], "duplicatePolicy": "blended_mode",
      "coldPositionPolicy": "historical_modal_zero_count_or_1_to_2_lowest_positive_last_position_first",
      "coldDigitWeight": "all_time_position_frequency_min_1", "appearedFrontDigitWeight": "uniform",
      "maxAttempts": $MAX_GENERATION_ATTEMPTS, "maxSetAttempts": $MAX_SET_GENERATION_ATTEMPTS,
      "maxSetCandidateAttempts": $MAX_SET_CANDIDATE_ATTEMPTS,
      "excludePolicy": ["past_winners", "saved_recommendations", "batch_duplicates"],
      "pairMinimumDifferentPositions": 3, "pairDifferentLastDigit": true, "pairDifferentGroup": true,
      "controlCount": $PENSION_CONTROL_COUNT
    }
""".trimIndent()

internal fun pensionExperimentConfiguration(count: Int, approvalHash: String? = null): String {
    require(count in 3..4)
    val config = PENSION_GENERATION_CONFIG.replace("\"appearedLastDigitCandidateCount\": 3", "\"appearedLastDigitCandidateCount\": $count")
    return if (approvalHash == null) config else org.json.JSONObject(config).put("operatingApprovalHash", approvalHash).toString()
}

internal class PensionGenerationTrace(private val checkActive: () -> Unit = {}) {
    var attempts: Long = 0
        private set
    private val reached = linkedMapOf<String, Long>()
    private val passed = linkedMapOf<String, Long>()
    fun attempt() { checkActive(); attempts++ }
    fun filter(name: String, accepted: Boolean): Boolean {
        reached[name] = reached.getOrDefault(name, 0) + 1
        if (accepted) passed[name] = passed.getOrDefault(name, 0) + 1
        return accepted
    }
    fun snapshot(): Map<String, Any?> = mapOf("attempts" to attempts, "filters" to reached.mapValues { (name, total) ->
        mapOf("reached" to total, "passed" to passed.getOrDefault(name, 0), "conditionalPassRate" to passed.getOrDefault(name, 0).toDouble() / total)
    }, "allFiltersPassRate" to if (attempts == 0L) null else passed.getOrDefault("group", 0).toDouble() / attempts)
}

private fun pensionFilter(trace: PensionGenerationTrace?, name: String, accepted: Boolean): Boolean =
    trace?.filter(name, accepted) ?: accepted

internal fun generatePensionExperimentBatch(
    draws: List<PensionLotteryDrawEntity>, count: Int, seed: Long, exclusions: Set<String>, trace: PensionGenerationTrace,
): List<PensionLotteryGeneratedNumber> {
    val analysis = requireNotNull(buildGeneratorAnalysis(draws, count)) { "연금 생성 입력은 직전 회차까지 최소 17회가 필요합니다." }
    return requireNotNull(generateCandidateSet(analysis, exclusions, Random(seed), trace)) {
        "필수 조건을 만족하는 전체 4개 배치를 찾지 못했습니다. 후보 시도=${trace.attempts}"
    }
}

internal suspend fun generatePensionOperatingBatch(
    store: com.habittracker.data.lotto.PensionExperimentStore,
    draws: List<PensionLotteryDrawEntity>, exclusions: Set<String>, random: Random = Random.Default,
): List<PensionLotteryGeneratedNumber> {
    val setting = store.setting()
    return withContext(Dispatchers.Default) {
        val context = kotlinx.coroutines.currentCoroutineContext()
        val analysis = requireNotNull(buildGeneratorAnalysis(draws, setting.count, setting.approvalHash)) { "번호 생성을 위해 당첨번호가 17회 이상 필요합니다." }
        requireNotNull(generateCandidateSet(analysis, exclusions, random, PensionGenerationTrace { context.ensureActive() })) {
            "현재 적용 조건을 모두 만족하는 네 번호를 찾지 못했습니다."
        }
    }
}
