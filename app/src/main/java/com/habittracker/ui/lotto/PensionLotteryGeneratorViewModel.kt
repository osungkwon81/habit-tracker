package com.habittracker.ui.lotto

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity
import com.habittracker.data.repository.HabitRepository
import com.habittracker.data.lotto.lotteryEvaluationNow
import com.habittracker.data.lotto.lotteryEvaluationDeadline
import com.habittracker.data.lotto.LotteryProduct
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import com.habittracker.data.lotto.PensionOperatingSetting
import com.habittracker.data.lotto.PensionExperimentSummary
import java.time.LocalDateTime
import java.time.Duration
import java.time.temporal.TemporalAdjusters
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

class PensionLotteryGeneratorViewModel(
    private val repository: HabitRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val autoImprovementState = repository.autoImprovement.state(com.habittracker.data.lotto.LotteryProduct.PENSION_720)

    fun refreshAutoImprovement() = viewModelScope.launch {
        repository.autoImprovement.refresh(com.habittracker.data.lotto.LotteryProduct.PENSION_720)
    }

    fun setAutoImprovementEnabled(enabled: Boolean) = viewModelScope.launch {
        try { repository.autoImprovement.setEnabled(com.habittracker.data.lotto.LotteryProduct.PENSION_720, enabled) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("LotteryAutoImprovement", "product=PENSION_720 phase=setting failed", error)
            statusMessage.value = error.message ?: "자동 개선 설정 실패"
        }
    }

    fun startHistoricalImprovement() {
        try { repository.autoImprovement.startHistorical(com.habittracker.data.lotto.LotteryProduct.PENSION_720) }
        catch (error: Exception) {
            Log.e("LotteryAutoImprovement", "product=PENSION_720 phase=schedule failed", error)
            statusMessage.value = error.message ?: "과거 비교 예약 실패"
        }
    }
    fun rollbackAutoImprovement() = viewModelScope.launch { repository.autoImprovement.rollback(LotteryProduct.PENSION_720) }

    private val draws = repository.observeAllPensionLotteryDraws()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val storedGeneratedNumbers = repository.observePensionLotteryGeneratedNumbers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val pendingGeneratedNumbers = savedStateHandle.getStateFlow(
        "pension.pendingNumbers", arrayListOf<PensionLotteryGeneratedNumber>(),
    )
    private val statusMessage = MutableStateFlow<String?>(null)
    private val isGenerating = MutableStateFlow(false)
    private val isSaving = MutableStateFlow(false)
    private val regeneratingNumber = MutableStateFlow<String?>(null)
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
        val adaptive = repository.adaptiveSettings.setting(LotteryProduct.PENSION_720)
        return buildGeneratorAnalysis(savedDraws, setting.count, setting.approvalHash, softLastDigit = true,
            lastDigitStrength = adaptive?.get("after")?.let(com.habittracker.data.lotto.LottoDesignRecordJson::decimal) ?: 1.0)
            ?.forIndividualGeneration()?.withCandidatePipeline()?.withAdaptiveHash(adaptive?.get("recordHash") as? String)
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
    private val regenerationClock = flow {
        val firstDeadline = lotteryEvaluationDeadline(LotteryProduct.PENSION_720, 1)
        while (true) {
            val now = lotteryEvaluationNow()
            emit(now)
            val boundary = now.toLocalDate().with(TemporalAdjusters.nextOrSame(firstDeadline.dayOfWeek)).atTime(firstDeadline.toLocalTime())
            val nextBoundary = if (boundary.isAfter(now)) boundary else boundary.plusWeeks(1)
            delay(Duration.between(now, nextBoundary).toMillis().coerceAtLeast(1))
        }
    }
    private val operationState = combine(
        statusMessage,
        isGenerating,
        isSaving,
        regeneratingNumber,
        generatingBackupType,
    ) { message, generating, saving, regenerating, generatingBackup ->
        PensionLotteryGeneratorOperationState(message, generating, saving, regenerating, generatingBackup)
    }.combine(regenerationClock) { operation, now -> operation.copy(now = now) }

    private val analysisState = combine(draws, operatingSetting, repository.adaptiveSettings.changes) { saved, setting, _ ->
        val adaptive = repository.adaptiveSettings.setting(LotteryProduct.PENSION_720)
        saved to setting?.let { buildGeneratorAnalysis(saved, it.count, it.approvalHash, softLastDigit = true,
            lastDigitStrength = adaptive?.get("after")?.let(com.habittracker.data.lotto.LottoDesignRecordJson::decimal) ?: 1.0)
            ?.forIndividualGeneration()?.withCandidatePipeline()?.withAdaptiveHash(adaptive?.get("recordHash") as? String) }
    }.flowOn(Dispatchers.Default)
    private val historyState = combine(draws, storedGeneratedNumbers) { saved, entities ->
        val history = buildGenerationHistory(entities)
        PensionLotteryStoredGenerationState(history, buildBackupNumbers(entities),
            restoreGenerationAnalysis(history.firstOrNull()?.numbers.orEmpty(), saved),
            history.groupBy { it.targetRoundNo }.map { (round, generations) -> PensionLotteryRoundHistory(round, generations) })
    }.flowOn(Dispatchers.Default)

    val performanceUiState: StateFlow<PensionLotteryPerformanceSummary?> = combine<
        List<PensionLotteryDrawEntity>, List<PensionLotteryGeneratedNumberEntity>, PensionLotteryPerformanceSummary?
    >(draws, storedGeneratedNumbers) { saved, entities -> buildPensionLotteryPerformance(saved, entities) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<PensionLotteryGeneratorUiState> = combine(
        analysisState,
        historyState,
        pendingGeneratedNumbers,
        operationState,
        experimentState,
    ) { (savedDraws, analysis), stored, pendingNumbers, operation, experiment ->
        val generationHistory = stored.history
        val backupNumbers = stored.backups
        val generatedNumbers = pendingNumbers.toList()
        val outdatedNumbers = if (analysis == null) emptySet() else generatedNumbers.filter { number ->
            number.generationSnapshot != analysis.generationSnapshot
        }.map { it.winningNumber }.toSet()
        val hasLatestConditionsChanged = outdatedNumbers.isNotEmpty()
        val regeneratableRounds = (stored.rounds.mapNotNull { it.targetRoundNo } +
            pendingNumbers.mapNotNull { it.generationSnapshot?.targetRoundNo }).distinct().filter {
            canRegeneratePensionRound(it, savedDraws.firstOrNull()?.roundNo, operation.now)
        }.toSet()
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
            roundHistory = stored.rounds,
            currentRoundHistory = analysis?.generationSnapshot?.targetRoundNo?.let { target ->
                stored.rounds.firstOrNull { it.targetRoundNo == target }
            } ?: stored.rounds.firstOrNull(),
            backupNumbers = backupNumbers,
            statusMessage = operation.statusMessage,
            isGenerating = operation.isGenerating,
            isSaving = operation.isSaving,
            regeneratingNumber = operation.regeneratingNumber,
            regeneratableRounds = regeneratableRounds,
            outdatedNumbers = outdatedNumbers,
            generatingBackupType = operation.generatingBackupType,
            hasUnsavedGeneration = pendingNumbers.isNotEmpty(),
            hasGenerationConditionChanged = pendingNumbers.isNotEmpty() && hasLatestConditionsChanged,
            hasLatestConditionsChanged = hasLatestConditionsChanged,
            savedGenerationConditions = stored.analysis?.let { savedAnalysis ->
                PensionLotterySavedGenerationConditions(savedAnalysis.generationSnapshot.analysisThroughRound,
                    savedAnalysis.targetScoreBand, savedAnalysis.targetDuplicateLabel, savedAnalysis.targetZeroScoreCount,
                    savedAnalysis.topAppearedLastDigits, preferredColdMixLastDigits(savedAnalysis))
            },
            backupUnavailableReason = when {
                experiment.busy -> "실험 작업 완료 후 생성 가능"
                operatingSetting.value == null -> "승인 설정 확인 필요"
                stored.analysis == null -> "생성 당시 입력·설정 확인 불가"
                else -> null
            },
            canGenerate = analysis != null && !experiment.busy,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PensionLotteryGeneratorUiState(),
    )

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    fun generate(pattern: PensionLotteryNumberPattern) {
        generateIndividual(pattern, replacingNumber = null)
    }

    fun regenerate(winningNumber: String) {
        val number = pendingGeneratedNumbers.value.firstOrNull { it.winningNumber == winningNumber } ?: return
        if (!checkRegenerationRound(number.generationSnapshot?.targetRoundNo)) return
        generateIndividual(number.type.pattern, replacingNumber = winningNumber, replacingSavedId = number.replacesSavedNumberId)
    }

    fun regenerateSavedNumber(generationId: String, winningNumber: String) {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        val saved = storedGeneratedNumbers.value.firstOrNull {
            it.generationId == generationId && it.winningNumber == winningNumber && !it.isControl && !it.isHidden
        }
        val number = saved?.toGeneratedNumber()
        if (saved == null || number == null) {
            Log.w("PensionGenerator", "Saved regeneration rejected: generationId=$generationId reason=source_unavailable")
            statusMessage.value = "저장 번호의 상태가 변경되었거나 생성 정보를 확인할 수 없습니다. 저장 이력을 확인해 주세요."
            return
        }
        if (!checkRegenerationRound(saved.targetRoundNo)) return
        val pending = pendingGeneratedNumbers.value.firstOrNull { it.replacesSavedNumberId == saved.id }
        generateIndividual(number.type.pattern, replacingNumber = pending?.winningNumber, replacingSavedId = saved.id)
    }

    private fun checkRegenerationRound(roundNo: Int?): Boolean {
        if (canRegeneratePensionRound(roundNo, draws.value.firstOrNull()?.roundNo, lotteryEvaluationNow())) return true
        Log.i("PensionGenerator", "Regeneration rejected: targetRound=$roundNo reason=round_closed_or_unknown")
        statusMessage.value = "추첨이 지난 회차이거나 대상 회차를 확인할 수 없어 재생성할 수 없습니다."
        return false
    }

    fun discardGeneratedNumber(winningNumber: String) {
        if (isGenerating.value || isSaving.value) return
        savedStateHandle["pension.pendingNumbers"] = ArrayList(pendingGeneratedNumbers.value.filterNot { it.winningNumber == winningNumber })
    }

    private fun generateIndividual(pattern: PensionLotteryNumberPattern, replacingNumber: String?, replacingSavedId: Long? = null) {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        val savedDraws = draws.value
        val currentNumbers = pendingGeneratedNumbers.value
        val storedNumbers = storedGeneratedNumbers.value.filterNot(PensionLotteryGeneratedNumberEntity::isControl)
        val type = when (pattern) {
            PensionLotteryNumberPattern.APPEARED -> PensionLotteryGenerationType.APPEARED
            PensionLotteryNumberPattern.COLD_MIX -> PensionLotteryGenerationType.COLD_MIX
        }
        val excludedWinningNumbers = (
            storedNumbers.map(PensionLotteryGeneratedNumberEntity::winningNumber) +
                currentNumbers.map(PensionLotteryGeneratedNumber::winningNumber)
            ).toSet()
        isGenerating.value = true
        regeneratingNumber.value = replacingNumber
        viewModelScope.launch {
            statusMessage.value = null
            try {
                val generated = withContext(Dispatchers.Default) {
                    val analysis = requireNotNull(currentAnalysis(savedDraws)) {
                        "번호 생성을 위해 당첨번호가 17회 이상 필요합니다."
                    }
                    val otherNumbers = (currentNumbers.filterNot { it.winningNumber == replacingNumber } +
                        storedNumbers.filter { !it.isHidden && it.id != replacingSavedId && it.targetRoundNo == analysis.generationSnapshot.targetRoundNo }
                            .mapNotNull { it.toGeneratedNumber() })
                        .filter { it.generationSnapshot?.targetRoundNo == analysis.generationSnapshot.targetRoundNo }
                        .distinctBy { it.winningNumber }
                    val comparisonTicket = otherNumbers.filter { it.type.pattern != pattern }.maxByOrNull { it.generatedAt }
                    val context = kotlinx.coroutines.currentCoroutineContext()
                    requireNotNull(
                        generateCandidate(
                            analysis = analysis,
                            type = type,
                            comparisonNumber = comparisonTicket?.winningNumber,
                            excludedWinningNumbers = excludedWinningNumbers,
                            otherNumbers = otherNumbers,
                            trace = PensionGenerationTrace { context.ensureActive() },
                        ),
                    ) { "현재 조건을 만족하는 ${pattern.label} 번호를 찾지 못했습니다. 기존 번호는 유지됩니다." }
                        .copy(replacesSavedNumberId = replacingSavedId)
                }
                val results = if (replacingNumber == null) currentNumbers + generated else currentNumbers.map {
                    if (it.winningNumber == replacingNumber) generated else it
                }
                savedStateHandle["pension.pendingNumbers"] = ArrayList(results)
                statusMessage.value = if (replacingSavedId != null) "새 후보를 생성했습니다. 저장하면 원래 번호가 교체됩니다."
                    else "${pattern.label} 번호 1개를 ${if (replacingNumber == null) "생성" else "재생성"}했습니다. 확인 후 개별 저장해 주세요."
            } catch (error: Exception) {
                propagateCancellation(error)
                Log.e("PensionGenerator", "Individual generation failed: pattern=$pattern throughRound=${savedDraws.firstOrNull()?.roundNo}", error)
                statusMessage.value = error.message ?: "연금번호 생성에 실패했습니다."
            } finally {
                regeneratingNumber.value = null
                isGenerating.value = false
            }
        }
    }

    fun saveGeneratedNumber(winningNumber: String) {
        if (isSaving.value || isGenerating.value || experimentState.value.busy) return
        val number = pendingGeneratedNumbers.value.firstOrNull { it.winningNumber == winningNumber }
        if (number == null) {
            statusMessage.value = "저장할 연금 생성번호가 없습니다."
            return
        }
        val numbers = listOf(number)
        isSaving.value = true
        viewModelScope.launch {
            statusMessage.value = null
            runCatching {
                val savedDraws = draws.value
                val analysis = requireNotNull(withContext(Dispatchers.Default) { currentAnalysis(savedDraws) }) {
                    "번호 생성을 위해 당첨번호가 17회 이상 필요합니다."
                }
                require(matchesNumberConditions(number, analysis)) {
                    GENERATION_CONDITION_CHANGED_MESSAGE
                }
                val snapshot = requireNotNull(numbers.first().generationSnapshot) { GENERATION_INPUT_CHANGED_MESSAGE }
                require(numbers.all { it.generationSnapshot == snapshot } && snapshot == analysis.generationSnapshot) {
                    GENERATION_INPUT_CHANGED_MESSAGE
                }
                val generationId = "single:${UUID.randomUUID()}"
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
                    count = 1,
                )
                repository.savePensionLotteryGeneratedNumbers(
                    recommendationEntities + controlEntities,
                    replacesRecommendationId = number.replacesSavedNumberId,
                )
            }.onSuccess { isEvaluationTarget ->
                savedStateHandle["pension.pendingNumbers"] = ArrayList(pendingGeneratedNumbers.value.filterNot { it == number })
                statusMessage.value = if (isEvaluationTarget) {
                    if (number.replacesSavedNumberId != null) "선택한 번호를 교체했습니다."
                    else "${number.type.pattern.label} 번호 1개를 저장했습니다. 저장 이력에서 확인할 수 있습니다."
                } else {
                    "번호 ${numbers.size}개를 사후 등록했습니다. 사전 추천 성과에서는 제외됩니다."
                }
            }.onFailure { error ->
                propagateCancellation(error)
                Log.e("PensionGenerator", "Individual save failed: pattern=${number.type.pattern} targetRound=${number.generationSnapshot?.targetRoundNo}", error)
                statusMessage.value = error.message ?: "연금 생성번호 저장에 실패했습니다."
            }
            isSaving.value = false
        }
    }

    fun deleteGeneration(generationId: String) {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        isSaving.value = true
        viewModelScope.launch {
            try {
                buildBackupNumbers(storedGeneratedNumbers.value)
                    .filter { backup -> backup.parentGenerationId == generationId }
                    .forEach { backup -> repository.deletePensionLotteryGeneration(backup.generationId) }
                repository.deletePensionLotteryGeneration(generationId)
                statusMessage.value = "저장 번호를 목록에서 숨겼습니다."
            } catch (error: Exception) {
                propagateCancellation(error)
                statusMessage.value = error.message ?: "연금번호 생성 히스토리 삭제에 실패했습니다."
            } finally {
                isSaving.value = false
            }
        }
    }

    fun generateBackup(type: PensionLotteryGenerationType) {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        if (pendingGeneratedNumbers.value.isNotEmpty()) return
        val currentHistory = buildGenerationHistory(storedGeneratedNumbers.value).firstOrNull() ?: return
        if (!checkRegenerationRound(currentHistory.targetRoundNo)) return
        if (currentHistory.generationId.startsWith("single:")) {
            statusMessage.value = "개별 저장 번호의 추가 추천은 유형별 생성 버튼을 이용해 주세요."
            return
        }
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
                requireNotNull(operatingSetting.value) { "승인 설정을 확인하지 못했습니다. 기록·설정을 새로고침해 주세요." }
                val analysis = requireNotNull(restoreGenerationAnalysis(currentHistory.numbers, draws.value)) {
                    "${currentHistory.generationId} 생성 당시 입력·설정을 복원할 수 없어 예비 번호를 생성하지 않았습니다. 저장된 번호는 유지됩니다."
                }
                val backup = requireNotNull(
                    generateCandidate(
                        analysis = analysis,
                        type = type,
                        comparisonNumber = comparisonTicket?.winningNumber,
                        comparisonGroupNo = comparisonTicket?.groupNo,
                        excludedWinningNumbers = excludedWinningNumbers,
                        otherNumbers = currentHistory.numbers.filterNot { it.type == type },
                    ),
                ) { "현재 조건을 만족하는 ${type.label} 예비 번호를 찾지 못했습니다." }
                // 삭제된 예비 번호도 보존되므로 재생성 시 배치 ID를 재사용하지 않는다.
                val generationId = "$BACKUP_GENERATION_PREFIX${currentHistory.generationId}:${type}_${UUID.randomUUID()}"
                repository.savePensionLotteryGeneratedNumbers(
                    listOf(backup.toEntity(generationId, lotteryEvaluationNow())),
                )
            }.onSuccess {
                statusMessage.value = "${type.label} 예비 번호를 고정 번호와 같은 회차·조건으로 저장했습니다."
            }.onFailure { error ->
                propagateCancellation(error)
                Log.e("PensionGenerator", "Backup generation failed: parent=${currentHistory.generationId} type=$type", error)
                statusMessage.value = error.message ?: "예비 번호 생성에 실패했습니다."
            }
            generatingBackupType.value = null
            isGenerating.value = false
        }
    }

    fun deleteBackup(generationId: String) {
        if (isGenerating.value || isSaving.value || experimentState.value.busy) return
        isSaving.value = true
        viewModelScope.launch {
            try {
                repository.deletePensionLotteryGeneration(generationId)
                statusMessage.value = "예비 번호를 삭제했습니다."
            } catch (error: Exception) {
                propagateCancellation(error)
                Log.e("PensionGenerator", "Backup deletion failed: generation=$generationId", error)
                statusMessage.value = error.message ?: "예비 번호 삭제에 실패했습니다."
            } finally {
                isSaving.value = false
            }
        }
    }
    private fun propagateCancellation(error: Throwable) {
        if (error is CancellationException) {
            isGenerating.value = false; isSaving.value = false
            regeneratingNumber.value = null; generatingBackupType.value = null
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

enum class PensionLotteryNumberPattern(val label: String) {
    APPEARED("출현형"),
    COLD_MIX("미출현 혼합형"),
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
    val replacesSavedNumberId: Long? = null,
) : java.io.Serializable

data class PensionLotteryGenerationSnapshot(
    val analysisThroughRound: Int,
    val inputDataHash: String,
    val configJson: String,
) : java.io.Serializable {
    val targetRoundNo: Int get() = analysisThroughRound + 1
    val usesAllGroups: Boolean get() = configJson.contains("\"implementationId\":\"pension-individual-all-groups-v2\"") || configJson.contains("\"implementationId\":\"pension-individual-soft-last-digit-v3\"")
    val isIndividual: Boolean get() = usesAllGroups || configJson.contains("\"implementationId\":\"pension-individual-v1\"")
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
    val roundHistory: List<PensionLotteryRoundHistory> = emptyList(),
    val currentRoundHistory: PensionLotteryRoundHistory? = null,
    val backupNumbers: List<PensionLotteryBackupNumber> = emptyList(),
    val statusMessage: String? = null,
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val regeneratingNumber: String? = null,
    val regeneratableRounds: Set<Int> = emptySet(),
    val outdatedNumbers: Set<String> = emptySet(),
    val generatingBackupType: PensionLotteryGenerationType? = null,
    val hasUnsavedGeneration: Boolean = false,
    val hasGenerationConditionChanged: Boolean = false,
    val hasLatestConditionsChanged: Boolean = false,
    val savedGenerationConditions: PensionLotterySavedGenerationConditions? = null,
    val backupUnavailableReason: String? = null,
    val canGenerate: Boolean = false,
)

data class PensionLotterySavedGenerationConditions(
    val analysisThroughRound: Int,
    val scoreBand: String,
    val duplicateLabel: String,
    val zeroScoreCount: Int,
    val appearedLastDigits: List<Int>,
    val coldMixLastDigits: List<Int>,
)

private data class PensionLotteryStoredGenerationState(
    val history: List<PensionLotteryGenerationHistory>,
    val backups: List<PensionLotteryBackupNumber>,
    val analysis: PensionLotteryGeneratorAnalysis?,
    val rounds: List<PensionLotteryRoundHistory>,
)

private data class PensionLotteryGeneratorOperationState(
    val statusMessage: String?,
    val isGenerating: Boolean,
    val isSaving: Boolean,
    val regeneratingNumber: String?,
    val generatingBackupType: PensionLotteryGenerationType?,
    val now: LocalDateTime = lotteryEvaluationNow(),
)

private fun canRegeneratePensionRound(roundNo: Int?, latestResultRound: Int?, now: LocalDateTime): Boolean =
    roundNo != null && roundNo > 0 && (latestResultRound == null || roundNo > latestResultRound) &&
        now.isBefore(lotteryEvaluationDeadline(LotteryProduct.PENSION_720, roundNo))

data class PensionLotteryGenerationHistory(
    val generationId: String,
    val generatedAt: LocalDateTime,
    val numbers: List<PensionLotteryGeneratedNumber>,
    val targetRoundNo: Int? = null,
)

data class PensionLotteryRoundHistory(
    val targetRoundNo: Int?,
    val generations: List<PensionLotteryGenerationHistory>,
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
                targetRoundNo = batchEntities.filterNot { it.isControl }.map { it.targetRoundNo }.distinct().singleOrNull(),
            )
        }
    }
    .sortedByDescending(PensionLotteryGenerationHistory::generatedAt)

private fun restoreGenerationAnalysis(
    numbers: List<PensionLotteryGeneratedNumber>,
    draws: List<PensionLotteryDrawEntity>,
): PensionLotteryGeneratorAnalysis? {
    val snapshot = numbers.firstOrNull()?.generationSnapshot ?: return null
    if (numbers.any { it.generationSnapshot != snapshot }) return null
    val config = try {
        org.json.JSONObject(snapshot.configJson)
    } catch (_: org.json.JSONException) {
        return null
    }
    val count = config.optInt("appearedLastDigitCandidateCount", -1)
    if (count !in 3..4) return null
    val approvalHash = if (config.has("operatingApprovalHash")) config.optString("operatingApprovalHash") else null
    val individual = snapshot.isIndividual
    val soft = config.optString("lastDigitPolicy") == "all_digits_positive_weight_v1"
    if (soft) {
        val original = draws.filter { it.roundNo <= snapshot.analysisThroughRound }
        if (original.firstOrNull()?.roundNo != snapshot.analysisThroughRound || pensionInputDataHash(original) != snapshot.inputDataHash) return null
        val strength = config.optDouble("lastDigitStrength", 1.0)
        if (strength !in listOf(1.0, 0.5)) return null
        val adaptiveHash = if (config.has("adaptiveRecordHash")) config.optString("adaptiveRecordHash") else null
        if (adaptiveHash != null && !adaptiveHash.matches(Regex("[0-9a-f]{64}"))) return null
        val base = buildGeneratorAnalysis(original, count, approvalHash, softLastDigit = true, lastDigitStrength = strength)
            ?.forIndividualGeneration() ?: return null
        val restored = (if (config.optBoolean("candidatePipeline", false)) base.withCandidatePipeline() else base)
            .withAdaptiveHash(adaptiveHash, config.optString("adaptivePolicy", com.habittracker.data.lotto.LotteryAdaptiveSettings.POLICY))
        return restored.takeIf { it.generationSnapshot == snapshot && numbers.size == 1 && matchesNumberConditions(numbers.single(), it) }
    }
    val preferDistinctLastDigits = when (snapshot.configJson) {
        pensionIndividualConfiguration(pensionExperimentConfiguration(count, approvalHash)) -> true
        pensionLegacyIndividualConfiguration(pensionExperimentConfiguration(count, approvalHash)) -> true
        pensionExperimentConfiguration(count, approvalHash) -> true
        pensionExperimentConfiguration(count, approvalHash, preferDistinctLastDigits = false) -> false
        else -> return null
    }
    val originalDraws = draws.filter { it.roundNo <= snapshot.analysisThroughRound }
    if (originalDraws.firstOrNull()?.roundNo != snapshot.analysisThroughRound || pensionInputDataHash(originalDraws) != snapshot.inputDataHash) return null
    val base = buildGeneratorAnalysis(originalDraws, count, approvalHash, preferDistinctLastDigits) ?: return null
    val analysis = if (individual) base.copy(generationSnapshot = base.generationSnapshot.copy(
        configJson = if (snapshot.usesAllGroups) pensionIndividualConfiguration(base.generationSnapshot.configJson)
            else pensionLegacyIndividualConfiguration(base.generationSnapshot.configJson),
    )) else base
    return analysis.takeIf { it.generationSnapshot == snapshot && if (individual) {
        numbers.size == 1 && matchesNumberConditions(numbers.single(), it)
    } else matchesCurrentGenerationConditions(numbers, it) }
}

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
    val preferDistinctLastDigits: Boolean,
    val softLastDigit: Boolean,
    val lastDigitStrength: Double,
    val appearedLastDigitWeights: IntArray,
    val coldLastDigitWeights: IntArray,
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

    return numbers.all { matchesNumberConditions(it, analysis) }
}

private fun matchesNumberConditions(number: PensionLotteryGeneratedNumber, analysis: PensionLotteryGeneratorAnalysis): Boolean {
    if (number.winningNumber.length != 6 || number.winningNumber.any { it !in '0'..'9' } || number.groupNo !in 1..5) return false
    if (number.winningNumber in analysis.pastWinningNumbers) return false
    if (pensionDuplicateLabel(number.winningNumber) != analysis.targetDuplicateLabel) return false
    if (number.duplicateLabel != analysis.targetDuplicateLabel) return false

    val currentDigitScores = calculatePensionNumberScores(analysis.latestDraws, number.winningNumber)
    if (pensionScoreBandLabel(currentDigitScores.sum()) != analysis.targetScoreBand) return false
    if (number.scoreBand != analysis.targetScoreBand) return false

    val digits = number.winningNumber.map { digit -> digit.digitToInt() }
    return when (number.type.pattern) {
        PensionLotteryNumberPattern.APPEARED -> {
            number.coldPositions.isEmpty() &&
                digits.indices.all { position -> (analysis.softLastDigit && position == LAST_DIGIT_POSITION) || digits[position] in analysis.appearedDigits[position] } &&
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
                analysis.softLastDigit -> true
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
                if (analysis.softLastDigit && position == LAST_DIGIT_POSITION) {
                    (position in number.coldPositions) == (digits[position] in if (usesZeroScoreTarget) analysis.zeroScoreDigits[position] else analysis.lowestPositiveScoreDigits[position])
                } else digits[position] in allowedDigits
            }
        }
    }
}

private fun buildGeneratorAnalysis(
    draws: List<PensionLotteryDrawEntity>,
    appearedLastDigitCandidateCount: Int = 3,
    approvalHash: String? = null,
    preferDistinctLastDigits: Boolean = true,
    softLastDigit: Boolean = false,
    lastDigitStrength: Double = 1.0,
): PensionLotteryGeneratorAnalysis? {
    require(lastDigitStrength in listOf(1.0, 0.5))
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
            configJson = if (softLastDigit) pensionSoftLastDigitConfiguration(approvalHash, lastDigitStrength) else pensionExperimentConfiguration(appearedLastDigitCandidateCount, approvalHash, preferDistinctLastDigits),
        ),
        preferDistinctLastDigits = preferDistinctLastDigits,
        softLastDigit = softLastDigit,
        lastDigitStrength = lastDigitStrength,
        appearedLastDigitWeights = IntArray(10) { lastDigitWeight(trendWeightedScores.last(), it, lastDigitStrength) },
        coldLastDigitWeights = IntArray(10) { lastDigitWeight(allTimeCounts.last(), it, lastDigitStrength) },
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
        topAppearedLastDigits = if (softLastDigit) (0..9).toList() else appearedDigits.last()
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
                otherNumbers = selectedNumbers,
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
                otherNumbers = selectedNumbers + appearedCandidate,
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
    otherNumbers: List<PensionLotteryGeneratedNumber> = emptyList(),
    maximumAttempts: Int = MAX_GENERATION_ATTEMPTS,
    generationSeed: Long = Random.nextLong(),
    trace: PensionGenerationTrace? = null,
    recordPool: ((Map<String, Any?>) -> Unit)? = null,
    poolSelection: Boolean = org.json.JSONObject(analysis.generationSnapshot.configJson).optBoolean("candidatePipeline", false),
): PensionLotteryGeneratedNumber? {
    val random = Random(generationSeed)
    val lastDigitUseCounts = if (analysis.preferDistinctLastDigits) {
        otherNumbers.groupingBy { it.winningNumber.last().digitToInt() }.eachCount()
    } else {
        emptyMap()
    }
    val allowedLastDigits = when (type.pattern) {
        PensionLotteryNumberPattern.APPEARED -> analysis.topAppearedLastDigits
        PensionLotteryNumberPattern.COLD_MIX -> preferredColdMixLastDigits(analysis)
    }.filter { it != comparisonNumber?.lastOrNull()?.digitToInt() }
    val minimumLastDigitUseCount = allowedLastDigits.minOfOrNull { lastDigitUseCounts[it] ?: 0 } ?: 0
    var bestCandidate: PensionLotteryGeneratedNumber? = null
    val pool = linkedMapOf<String, PensionLotteryGeneratedNumber>()
    fun selectPool(): PensionLotteryGeneratedNumber? {
        if (pool.size < 250) return null
        val candidates = pool.values.toList()
        val weights = if (type.pattern == PensionLotteryNumberPattern.APPEARED) analysis.appearedLastDigitWeights else analysis.coldLastDigitWeights
        fun score(index: Int) = if (analysis.lastDigitStrength == 0.5) candidates[index].totalScore else weights[candidates[index].winningNumber.last().digitToInt()]
        val ordered = candidates.indices.sortedByDescending(::score)
        // 상위 압축에서도 후보에 존재하는 끝자리별 최상위 번호를 남겨 분산 선택을 유지한다.
        val representatives = ordered.distinctBy { candidates[it].winningNumber.last() }
        val ranked = (representatives + ordered).distinct().take(50).sortedByDescending(::score)
        val minimumUse = ranked.minOf { lastDigitUseCounts[candidates[it].winningNumber.last().digitToInt()] ?: 0 }
        val digits = ranked.map { candidates[it].winningNumber.last().digitToInt() }.distinct()
            .filter { (lastDigitUseCounts[it] ?: 0) == minimumUse }
        val totalWeight = digits.sumOf { weights[it].toDouble().coerceAtLeast(1.0) }
        var draw = random.nextDouble() * totalWeight
        val digit = digits.firstOrNull { draw -= weights[it].toDouble().coerceAtLeast(1.0); draw < 0 } ?: digits.last()
        val finalists = ranked.filter { candidates[it].winningNumber.last().digitToInt() == digit }
        val selected = finalists[random.nextInt(finalists.size)]
        recordPool?.invoke(mapOf("type" to type.name, "pool" to candidates.map { it.winningNumber },
            "ranked" to ranked, "selected" to listOf(selected), "scores" to ranked.map(::score)))
        return candidates[selected]
    }
    var bestLastDigitUseCount = Int.MAX_VALUE
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
        val allGroups = analysis.generationSnapshot.usesAllGroups
        val availableGroups = if (allGroups) listOf(1) else (1..5).filter { groupNo -> groupNo != comparisonGroupNo }
        if (!pensionFilter(trace, "group", availableGroups.isNotEmpty())) return@repeat

        val lastDigitUseCount = lastDigitUseCounts[selection.winningNumber.last().digitToInt()] ?: 0
        if (!poolSelection && lastDigitUseCount >= bestLastDigitUseCount) return@repeat
        val candidate = PensionLotteryGeneratedNumber(
            type = type,
            groupNo = if (allGroups) 1 else weightedGroup(
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
        if (poolSelection) {
            pool.putIfAbsent(candidate.winningNumber, candidate)
            if (pool.size == 250) return selectPool()
            return@repeat
        }
        if (lastDigitUseCount == minimumLastDigitUseCount) return candidate
        bestCandidate = candidate
        bestLastDigitUseCount = lastDigitUseCount
    }

    if (poolSelection) return selectPool()
    // 끝자리 분산 때문에 기존 필수 조건을 만족한 번호까지 버리지 않는다.
    if (bestCandidate != null) {
        Log.i("PensionGenerator", "Last digit fallback: throughRound=${analysis.generationSnapshot.analysisThroughRound} type=$type attempts=$maximumAttempts reuse=$bestLastDigitUseCount minimum=$minimumLastDigitUseCount")
    }
    return bestCandidate
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
    ) { digit -> analysis.appearedLastDigitWeights[digit] }
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
    if (analysis.softLastDigit) return buildSoftColdMixSelection(analysis, comparisonLastDigit, random)
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

private fun buildSoftColdMixSelection(
    analysis: PensionLotteryGeneratorAnalysis,
    comparisonLastDigit: Int?,
    random: Random,
): CandidateSelection? {
    val digits = MutableList(6) { 0 }
    digits[LAST_DIGIT_POSITION] = weightedDigit((0..9).filter { it != comparisonLastDigit }, random) {
        analysis.coldLastDigitWeights[it]
    }
    val usesZero = analysis.targetZeroScoreCount > 0
    val coldDigits = if (usesZero) analysis.zeroScoreDigits else analysis.lowestPositiveScoreDigits
    val lastIsCold = digits.last() in coldDigits.last()
    val count = if (usesZero) analysis.targetZeroScoreCount else random.nextInt(1, 3)
    val frontCount = count - if (lastIsCold) 1 else 0
    val eligibleFront = (0 until LAST_DIGIT_POSITION).filter { coldDigits[it].isNotEmpty() }
    if (frontCount !in 0..eligibleFront.size) return null
    val positions = eligibleFront.shuffled(random).take(frontCount).toSet() + if (lastIsCold) setOf(LAST_DIGIT_POSITION) else emptySet()
    val scores = mutableMapOf<Int, Int>()
    for (position in digits.indices) {
        if (position != LAST_DIGIT_POSITION) {
            val candidates = if (position in positions) coldDigits[position] else analysis.appearedDigits[position]
            if (candidates.isEmpty()) return null
            digits[position] = if (position in positions) weightedDigit(candidates, random) { analysis.allTimeCounts[position][it] } else candidates.random(random)
        }
        if (position in positions) {
            val maximum = analysis.allTimeCounts[position].maxOrNull()?.coerceAtLeast(1) ?: 1
            scores[position] = (analysis.allTimeCounts[position][digits[position]].toDouble() / maximum * 100).roundToInt()
        }
    }
    return CandidateSelection(digits.joinToString(""), positions, scores)
}

private fun preferredColdMixLastDigits(analysis: PensionLotteryGeneratorAnalysis): List<Int> = when {
    analysis.softLastDigit -> (0..9).toList()
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
    count: Int = PENSION_CONTROL_COUNT,
): List<PensionLotteryGeneratedNumberEntity> {
    val usedNumbers = excludedNumbers.toMutableSet()
    return List(count) { index ->
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
            groupNo = if (analysis.generationSnapshot.usesAllGroups) 1 else random.nextInt(1, 6),
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
    "최근 당첨번호 반영으로 번호 생성 적용 조건이 변경되었습니다. 해당 번호를 다시 생성해 주세요."
private const val GENERATION_INPUT_CHANGED_MESSAGE =
    "생성 당시 분석 입력이 없거나 현재 입력과 다릅니다. 해당 번호를 다시 생성해 주세요."
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

private fun lastDigitWeight(scores: IntArray, digit: Int, strength: Double): Int =
    if (strength == 1.0) scores[digit] else (scores[digit].coerceAtLeast(1) * strength + scores.map { it.coerceAtLeast(1) }.average() * (1.0 - strength)).roundToInt().coerceAtLeast(1)

internal fun pensionSoftLastDigitConfiguration(approvalHash: String? = null, strength: Double = 1.0): String = org.json.JSONObject(pensionExperimentConfiguration(3, approvalHash))
    .put("implementationId", "pension-soft-last-digit-v1")
    .put("lastDigitPolicy", "all_digits_positive_weight_v1")
    .put("lastDigitImplementationId", "pension-soft-last-digit-v1")
    .put("appearedLastDigitWeight", "recent_0.25_long_0.75_min_1")
    .put("coldLastDigitWeight", "all_time_position_frequency_min_1")
    .put("coldPositionPolicy", "historical_count_selected_last_digit_then_front_positions")
    .put("lastDigitCandidates", org.json.JSONArray((0..9).toList()))
    .apply { if (strength != 1.0) {
        put("lastDigitStrength", strength); put("lastDigitImplementationId", "pension-soft-last-digit-strength-v2")
        put("lastDigitStrengthPolicy", "strength_times_positive_weight_plus_remaining_times_mean_positive_weight")
    } }.toString()

internal fun pensionExperimentConfiguration(
    count: Int,
    approvalHash: String? = null,
    preferDistinctLastDigits: Boolean = true,
): String {
    require(count in 3..4)
    val config = PENSION_GENERATION_CONFIG.replace("\"appearedLastDigitCandidateCount\": 3", "\"appearedLastDigitCandidateCount\": $count")
    if (preferDistinctLastDigits) {
        return org.json.JSONObject(config)
            .put("implementationId", "pension-batch-last-digit-diversity-v1")
            .put("batchLastDigitPolicy", "least_reused_valid_candidate_within_existing_attempt_limit")
            .apply { if (approvalHash != null) put("operatingApprovalHash", approvalHash) }
            .toString()
    }
    return if (approvalHash == null) config else org.json.JSONObject(config).put("operatingApprovalHash", approvalHash).toString()
}

private fun pensionLegacyIndividualConfiguration(batchConfig: String): String = org.json.JSONObject(batchConfig)
    .put("implementationId", "pension-individual-v1")
    .put("types", org.json.JSONObject().put("selection", "one_at_a_time")
        .put("allowed", org.json.JSONArray(listOf("APPEARED", "COLD_MIX"))))
    .put("controlCount", 1)
    .put("comparisonPolicy", "latest_opposite_pattern_in_same_round")
    .put("lastDigitContext", "pending_and_visible_saved_recommendations_in_same_round_except_replaced")
    .toString()

private fun pensionIndividualConfiguration(batchConfig: String): String = org.json.JSONObject(pensionLegacyIndividualConfiguration(batchConfig))
    .put("implementationId", if (org.json.JSONObject(batchConfig).has("lastDigitPolicy")) "pension-individual-soft-last-digit-v3" else "pension-individual-all-groups-v2")
    .put("pairDifferentGroup", false)
    .put("purchaseGroups", org.json.JSONArray((1..5).toList()))
    .put("storedGroupNoMeaning", "compatibility_placeholder_1_not_recommendation")
    .apply { remove("groupWeights") }
    .toString()

internal fun pensionIndividualWeightConfiguration(strength: Double): String = pensionIndividualConfiguration(pensionSoftLastDigitConfiguration(strength = strength))

private fun PensionLotteryGeneratorAnalysis.withCandidatePipeline(): PensionLotteryGeneratorAnalysis = copy(
    generationSnapshot = generationSnapshot.copy(configJson = org.json.JSONObject(generationSnapshot.configJson)
        .put("candidatePipeline", true).put("candidatePipelineId", "pension-250-50-1-weighted-v3")
        .put("rankingPolicy", if (lastDigitStrength == 0.5) "total_historical_score" else "last_digit_weight")
        .put("candidatePoolPerType", 250).put("finalistsPerType", 50).toString()))

internal fun generateTrackedPension(draws: List<PensionLotteryDrawEntity>, seed: Long, strength: Double,
    checkActive: () -> Unit = {},
    record: (Map<String, Any?>) -> Unit): List<PensionLotteryGeneratedNumber> {
    val analysis = requireNotNull(buildGeneratorAnalysis(draws, 3, softLastDigit = true, lastDigitStrength = strength))
        .forIndividualGeneration().withCandidatePipeline()
    val selected = mutableListOf<PensionLotteryGeneratedNumber>()
    val random = Random(seed)
    for (type in PensionLotteryGenerationType.entries) {
        selected += requireNotNull(generateCandidate(analysis, type,
            comparisonNumber = selected.lastOrNull { it.type.pattern != type.pattern }?.winningNumber,
            excludedWinningNumbers = selected.map { it.winningNumber }.toSet(), otherNumbers = selected,
            generationSeed = random.nextLong(), trace = PensionGenerationTrace(checkActive), recordPool = record)) { "연금 후보 250개 확보 실패: type=$type seed=$seed" }
    }
    return selected
}

private fun PensionLotteryGeneratorAnalysis.forIndividualGeneration(): PensionLotteryGeneratorAnalysis = copy(
    generationSnapshot = generationSnapshot.copy(configJson = pensionIndividualConfiguration(generationSnapshot.configJson)),
)

private fun PensionLotteryGeneratorAnalysis.withAdaptiveHash(hash: String?, policy: String = com.habittracker.data.lotto.LotteryAdaptiveSettings.POLICY): PensionLotteryGeneratorAnalysis =
    if (hash == null) this else copy(generationSnapshot = generationSnapshot.copy(configJson = org.json.JSONObject(generationSnapshot.configJson)
        .put("adaptivePolicy", policy).put("adaptiveRecordHash", hash).toString()))

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
    softLastDigit: Boolean = false,
    lastDigitStrength: Double = 1.0,
    individualGeneration: Boolean = false,
): List<PensionLotteryGeneratedNumber> {
    val analysis = requireNotNull(buildGeneratorAnalysis(draws, count, softLastDigit = softLastDigit, lastDigitStrength = lastDigitStrength)) { "연금 생성 입력은 직전 회차까지 최소 17회가 필요합니다." }
    if (individualGeneration) {
        val selected = mutableListOf<PensionLotteryGeneratedNumber>()
        val random = Random(seed)
        val individual = analysis.forIndividualGeneration()
        for (type in PensionLotteryGenerationType.entries) {
            val opposite = selected.lastOrNull { it.type.pattern != type.pattern }
            selected += requireNotNull(generateCandidate(individual, type, comparisonNumber = opposite?.winningNumber,
                excludedWinningNumbers = exclusions + selected.map { it.winningNumber }, otherNumbers = selected,
                generationSeed = random.nextLong(), trace = trace)) { "연금 개별 생성 비교 실패: type=$type seed=$seed" }
        }
        return selected
    }
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
