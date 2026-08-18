package com.habittracker.ui.lotto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoPurchaseEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.LottoWinningEntity
import com.habittracker.data.local.entity.LottoWinningStatEntity
import com.habittracker.data.local.model.LottoPeriodStatRow
import com.habittracker.data.lotto.LottoGeneratedTicket
import com.habittracker.data.lotto.LottoGenerationMode
import com.habittracker.data.lotto.LottoNumberGenerator
import com.habittracker.data.lotto.LottoControlComparison
import com.habittracker.data.lotto.LottoQrParser
import com.habittracker.data.lotto.LottoScorePerformance
import com.habittracker.data.lotto.LotteryProduct
import com.habittracker.data.lotto.LotterySyncStatus
import com.habittracker.data.lotto.toLotterySyncUserMessage
import com.habittracker.data.repository.HabitRepository
import com.habittracker.ui.digitsOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val balancedSource = "균형형"
private const val dispersedSource = "분산형"
private const val sourceChatGpt = "균형형"
private const val sourceGemini = "분산형"
private const val physicalQrSource = "QR 등록"
private const val savedDrawHistoryLimit = 120
private const val lottoHistoryPageSize = 20

/** 탭을 문자열이 아닌 타입으로 제한해 잘못된 화면 상태를 컴파일 단계에서 막는다. */
enum class LottoTab {
    GENERATOR,
    DRAW,
    PURCHASE,
    PHYSICAL_QR,
    WINNING,
    SAVED,
    STATS,
}

enum class LottoStatsRange(val label: String) {
    WEEKLY("주간"),
    MONTHLY("월간"),
    YEARLY("년간"),
}

private data class PendingLottoDelete(
    val roundNo: Int? = null,
    val ticketId: Long? = null,
)

private data class LottoHistoryState(
    val savedDraws: List<LottoDrawEntity>,
    val allDraws: List<LottoDrawEntity>,
    val savedTickets: List<LottoTicketEntity>,
    val allSavedTickets: List<LottoTicketEntity>,
    val physicalQrTickets: List<LottoTicketEntity>,
    val purchases: List<LottoPurchaseEntity>,
)

private data class LottoTicketHistoryState(
    val savedTickets: List<LottoTicketEntity> = emptyList(),
    val physicalQrTickets: List<LottoTicketEntity> = emptyList(),
)

private data class LottoWinningAndAmountState(
    val winnings: List<LottoWinningEntity>,
    val totalPurchaseAmount: Long,
    val totalWinningAmount: Long,
    val pensionPurchaseAmount: Long,
    val pensionWinningAmount: Long,
)

private data class LottoPeriodStatsState(
    val weekly: List<LottoPeriodStatRow>,
    val monthly: List<LottoPeriodStatRow>,
    val yearly: List<LottoPeriodStatRow>,
    val selectedRange: LottoStatsRange,
    val winningTypeStats: List<LottoWinningStatEntity>,
)

private data class LottoStatsState(
    val winningAndAmount: LottoWinningAndAmountState,
    val period: LottoPeriodStatsState,
    val scorePerformances: List<LottoScorePerformance>,
    val controlComparisons: List<LottoControlComparison>,
)

private data class LottoInputState(
    val selectedTab: LottoTab,
    val roundInput: String,
    val queryRoundInput: String,
    val savedRoundQueryInput: String,
    val numberInputs: List<String>,
)

private data class LottoGenerationState(
    val bonusNumberInput: String,
    val generationMode: LottoGenerationMode,
    val isGenerating: Boolean,
    val chatGptResults: List<LottoGeneratedTicket>,
    val geminiResults: List<LottoGeneratedTicket>,
)

private data class LottoFeedbackState(
    val isHistoryLoading: Boolean,
    val statusMessage: String?,
    val latestRoundNo: Int?,
    val pendingDelete: PendingLottoDelete?,
    val lastGeneratedSource: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LottoViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(LottoTab.GENERATOR)
    private val roundInput = MutableStateFlow("")
    private val queryRoundInput = MutableStateFlow("")
    private val appliedQueryRoundInput = MutableStateFlow("")
    private val numberInputs = MutableStateFlow(List(6) { "" })
    private val bonusNumberInput = MutableStateFlow("")
    private val generationMode = MutableStateFlow(LottoGenerationMode.PRECISE)
    private val isGenerating = MutableStateFlow(false)
    private val isHistoryLoading = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val generatedChatGpt = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val generatedGemini = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val latestRoundNo = MutableStateFlow<Int?>(null)
    private val pendingDelete = MutableStateFlow<PendingLottoDelete?>(null)
    private val lastGeneratedSource = MutableStateFlow<String?>(null)
    private val selectedStatsRange = MutableStateFlow(LottoStatsRange.WEEKLY)
    private val savedRoundQueryInput = MutableStateFlow("")
    private val purchaseHistoryLimit = MutableStateFlow(lottoHistoryPageSize)
    private val winningHistoryLimit = MutableStateFlow(lottoHistoryPageSize)
    private val _isOfficialSyncing = MutableStateFlow(false)
    val isOfficialSyncing: StateFlow<Boolean> = _isOfficialSyncing
    val officialSyncStatus: StateFlow<LotterySyncStatus> = repository
        .observeLotterySyncStatus(LotteryProduct.LOTTO_645)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LotterySyncStatus(LotteryProduct.LOTTO_645),
        )

    private val observedDraws = selectedTab.flatMapLatest { tab ->
        if (tab != LottoTab.DRAW) {
            flowOf(emptyList())
        } else {
            appliedQueryRoundInput.flatMapLatest { query ->
                repository.observeLottoDraws(query.toIntOrNull(), limit = 20)
            }
        }
    }.onEach {
        if (selectedTab.value == LottoTab.DRAW) {
            isHistoryLoading.value = false
        }
    }
    private val allDraws = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.SAVED || tab == LottoTab.PHYSICAL_QR) {
            repository.observeLottoDraws(roundNo = null, limit = savedDrawHistoryLimit)
        } else {
            flowOf(emptyList())
        }
    }
    private val nextRoundSavedTickets = combine(selectedTab, latestRoundNo) { tab, latestDrawRoundNo -> tab to latestDrawRoundNo }.flatMapLatest { (tab, latestDrawRoundNo) ->
        if (tab != LottoTab.GENERATOR && tab != LottoTab.SAVED) return@flatMapLatest flowOf(emptyList())
        val nextRoundNo = latestDrawRoundNo?.plus(1)
        if (nextRoundNo == null) {
            flowOf(emptyList())
        } else {
            repository.observeSavedLottoTicketsByRound(nextRoundNo)
                .map { values -> values.filterNot { ticket -> ticket.sourceLabel == physicalQrSource } }
        }
    }
    private val ticketHistory = combine(selectedTab, savedRoundQueryInput) { tab, query -> tab to query }
        .flatMapLatest { (tab, query) ->
            when (tab) {
                LottoTab.SAVED -> {
                    val tickets = query.toIntOrNull()?.let(repository::observeSavedLottoTicketsByRound)
                        ?: repository.observeAllSavedLottoTickets()
                    tickets.map { values ->
                        LottoTicketHistoryState(
                            savedTickets = values.filterNot { ticket -> ticket.sourceLabel == physicalQrSource },
                        )
                    }
                }
                LottoTab.PHYSICAL_QR -> repository.observeLottoTicketsBySource(physicalQrSource)
                    .map { values -> LottoTicketHistoryState(physicalQrTickets = values) }
                else -> flowOf(LottoTicketHistoryState())
            }
        }
    private val winningTypeStats = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) {
            repository.observeLottoWinningStats().map { stats ->
                stats.filter { stat ->
                    stat.generationVersion == LottoNumberGenerator.CURRENT_GENERATION_VERSION
                }
            }
        } else {
            flowOf(emptyList())
        }
    }
    private val scorePerformances = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) {
            repository.observeLottoScorePerformances().map { performances ->
                performances.filter { performance ->
                    performance.generationVersion == LottoNumberGenerator.CURRENT_GENERATION_VERSION
                }
            }
        } else {
            flowOf(emptyList())
        }
    }
    private val controlComparisons = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) {
            repository.observeLottoControlComparisons().map { comparisons ->
                comparisons.filter { comparison ->
                    comparison.generationVersion == LottoNumberGenerator.CURRENT_GENERATION_VERSION
                }
            }
        } else {
            flowOf(emptyList())
        }
    }
    private val purchases = combine(selectedTab, purchaseHistoryLimit) { tab, limit -> tab to limit }
        .flatMapLatest { (tab, limit) ->
            if (tab == LottoTab.PURCHASE) repository.observeLottoPurchases(limit) else flowOf(emptyList())
        }
    private val winnings = combine(selectedTab, winningHistoryLimit) { tab, limit -> tab to limit }
        .flatMapLatest { (tab, limit) ->
            if (tab == LottoTab.WINNING) repository.observeLottoWinnings(limit) else flowOf(emptyList())
        }
    private val totalPurchaseAmount = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) repository.observeTotalLottoPurchaseAmount("로또") else flowOf(0L)
    }
    private val totalWinningAmount = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) repository.observeTotalLottoWinningAmount("로또") else flowOf(0L)
    }
    private val pensionPurchaseAmount = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) repository.observeTotalLottoPurchaseAmount("연금") else flowOf(0L)
    }
    private val pensionWinningAmount = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) repository.observeTotalLottoWinningAmount("연금") else flowOf(0L)
    }
    private val weeklyStats = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) repository.observeLottoWeeklyStats(limit = 12) else flowOf(emptyList())
    }
    private val monthlyStats = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) repository.observeLottoMonthlyStats(limit = 12) else flowOf(emptyList())
    }
    private val yearlyStats = selectedTab.flatMapLatest { tab ->
        if (tab == LottoTab.STATS) repository.observeLottoYearlyStats(limit = 12) else flowOf(emptyList())
    }

    private val historyState = combine(
        observedDraws,
        allDraws,
        nextRoundSavedTickets,
        ticketHistory,
        purchases,
    ) { draws, allDraws, savedTickets, historyTickets, purchases ->
        LottoHistoryState(
            savedDraws = draws,
            allDraws = allDraws,
            savedTickets = savedTickets,
            allSavedTickets = historyTickets.savedTickets,
            physicalQrTickets = historyTickets.physicalQrTickets,
            purchases = purchases,
        )
    }

    private val winningAndAmountState = combine(
        winnings,
        totalPurchaseAmount,
        totalWinningAmount,
        pensionPurchaseAmount,
        pensionWinningAmount,
    ) { winnings, totalPurchase, totalWinning, pensionPurchase, pensionWinning ->
        LottoWinningAndAmountState(winnings, totalPurchase, totalWinning, pensionPurchase, pensionWinning)
    }

    private val periodStatsState = combine(
        weeklyStats,
        monthlyStats,
        yearlyStats,
        selectedStatsRange,
        winningTypeStats,
    ) { weekly, monthly, yearly, range, winningStats ->
        LottoPeriodStatsState(weekly, monthly, yearly, range, winningStats)
    }

    private val statsState = combine(
        winningAndAmountState,
        periodStatsState,
        scorePerformances,
        controlComparisons,
    ) { winningAndAmount, period, performances, comparisons ->
        LottoStatsState(winningAndAmount, period, performances, comparisons)
    }

    private val inputState = combine(
        selectedTab,
        roundInput,
        queryRoundInput,
        savedRoundQueryInput,
        numberInputs,
    ) { tab, round, query, savedRoundQuery, numbers ->
        LottoInputState(tab, round, query, savedRoundQuery, numbers)
    }

    private val generationState = combine(
        bonusNumberInput,
        generationMode,
        isGenerating,
        generatedChatGpt,
        generatedGemini,
    ) { bonusNumber, mode, generating, chatGpt, gemini ->
        LottoGenerationState(bonusNumber, mode, generating, chatGpt, gemini)
    }

    private val feedbackState = combine(
        isHistoryLoading,
        statusMessage,
        latestRoundNo,
        pendingDelete,
        lastGeneratedSource,
    ) { historyLoading, message, latestRound, pendingDelete, recentSource ->
        LottoFeedbackState(historyLoading, message, latestRound, pendingDelete, recentSource)
    }

    /*
     * UI 상태를 주제별 data class로 묶어 결합한다.
     * Flow 순서를 바꿔도 Array 인덱스가 어긋나지 않고 컴파일 단계에서 타입 오류를 찾을 수 있다.
     */
    val uiState: StateFlow<LottoUiState> = combine(
        historyState,
        statsState,
        inputState,
        generationState,
        feedbackState,
    ) { history, stats, input, generation, feedback ->
        val activeStats = when (stats.period.selectedRange) {
            LottoStatsRange.WEEKLY -> stats.period.weekly
            LottoStatsRange.MONTHLY -> stats.period.monthly
            LottoStatsRange.YEARLY -> stats.period.yearly
        }

        LottoUiState(
            selectedTab = input.selectedTab,
            roundInput = input.roundInput,
            queryRoundInput = input.queryRoundInput,
            savedRoundQueryInput = input.savedRoundQueryInput,
            numberInputs = input.numberInputs,
            bonusNumberInput = generation.bonusNumberInput,
            generationMode = generation.generationMode,
            isGenerating = generation.isGenerating,
            isHistoryLoading = feedback.isHistoryLoading,
            statusMessage = feedback.statusMessage,
            chatGptResults = generation.chatGptResults,
            geminiResults = generation.geminiResults,
            savedDraws = history.savedDraws,
            allDraws = history.allDraws,
            savedTickets = history.savedTickets,
            allSavedTickets = history.allSavedTickets,
            physicalQrTickets = history.physicalQrTickets,
            purchases = history.purchases,
            winnings = stats.winningAndAmount.winnings,
            canLoadMorePurchases = history.purchases.size >= purchaseHistoryLimit.value,
            canLoadMoreWinnings = stats.winningAndAmount.winnings.size >= winningHistoryLimit.value,
            totalPurchaseAmount = stats.winningAndAmount.totalPurchaseAmount,
            totalWinningAmount = stats.winningAndAmount.totalWinningAmount,
            pensionPurchaseAmount = stats.winningAndAmount.pensionPurchaseAmount,
            pensionWinningAmount = stats.winningAndAmount.pensionWinningAmount,
            selectedStatsRange = stats.period.selectedRange,
            stats = activeStats,
            latestSavedRoundNo = feedback.latestRoundNo,
            nextRoundNo = feedback.latestRoundNo?.plus(1),
            pendingDeleteRoundNo = feedback.pendingDelete?.roundNo,
            pendingDeleteTicketId = feedback.pendingDelete?.ticketId,
            lastGeneratedSource = feedback.lastGeneratedSource,
            winningTypeStats = stats.period.winningTypeStats.map(::toWinningTypeStat),
            scorePerformances = stats.scorePerformances,
            controlComparisons = stats.controlComparisons,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LottoUiState(),
    )

    init {
        viewModelScope.launch {
            runCatching {
                val bundledDrawsChanged = repository.syncBundledLottoDraws()
                repository.ensureLottoWinningStatsInitialized(force = bundledDrawsChanged)
                refreshLatestRound()
            }.onFailure { error ->
                statusMessage.value = error.message ?: "로또 데이터를 준비하지 못했습니다."
            }
        }
        isHistoryLoading.value = true
    }

    fun selectGeneratorTab() {
        selectedTab.value = LottoTab.GENERATOR
    }

    fun selectDrawTab() {
        selectedTab.value = LottoTab.DRAW
        isHistoryLoading.value = true
    }

    fun selectPurchaseTab() {
        selectedTab.value = LottoTab.PURCHASE
    }

    fun selectPhysicalQrTab() {
        selectedTab.value = LottoTab.PHYSICAL_QR
    }

    fun selectWinningTab() {
        selectedTab.value = LottoTab.WINNING
    }

    fun selectSavedTab() {
        selectedTab.value = LottoTab.SAVED
    }

    fun selectStatsTab() {
        selectedTab.value = LottoTab.STATS
    }

    fun selectStatsRange(range: LottoStatsRange) {
        selectedStatsRange.value = range
    }

    fun loadMorePurchases() {
        if (!uiState.value.canLoadMorePurchases) return
        purchaseHistoryLimit.value += lottoHistoryPageSize
    }

    fun loadMoreWinnings() {
        if (!uiState.value.canLoadMoreWinnings) return
        winningHistoryLimit.value += lottoHistoryPageSize
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    fun updateRoundInput(value: String) {
        roundInput.value = value.digitsOnly()
    }

    fun updateQueryRoundInput(value: String) {
        queryRoundInput.value = value.digitsOnly()
    }

    fun updateSavedRoundQueryInput(value: String) {
        savedRoundQueryInput.value = value.digitsOnly()
    }

    fun submitDrawQuery() {
        isHistoryLoading.value = true
        appliedQueryRoundInput.value = queryRoundInput.value
    }

    fun updateNumberInput(index: Int, value: String) {
        numberInputs.value = numberInputs.value.toMutableList().also { list ->
            list[index] = value.digitsOnly().take(2)
        }
    }

    fun updateGenerationMode(mode: LottoGenerationMode) {
        generationMode.value = mode
    }

    fun updateBonusNumberInput(value: String) {
        bonusNumberInput.value = value.digitsOnly().take(2)
    }

    fun saveGeneratedBatch(sourceLabel: String, tickets: List<LottoGeneratedTicket>) {
        viewModelScope.launch {
            if (tickets.isEmpty()) {
                statusMessage.value = "저장할 생성 번호가 없습니다."
                return@launch
            }
            val targetRoundNo = roundInput.value.toIntOrNull() ?: latestRoundNo.value?.plus(1)
            if (targetRoundNo == null || targetRoundNo <= 0) {
                statusMessage.value = "저장할 회차를 먼저 확인해 주세요."
                return@launch
            }
            if (repository.getSavedLottoBatchCount(roundNo = targetRoundNo, sourceLabel = sourceLabel) >= 3) {
                statusMessage.value = "${targetRoundNo}회차 ${sourceLabel} 번호는 이미 3세트 저장되어 있습니다. 1세트를 삭제한 뒤 다시 저장해 주세요."
                return@launch
            }
            saveGeneratedBatchInternal(roundNo = targetRoundNo, sourceLabel = sourceLabel, tickets = tickets)
        }
    }

    fun requestDeleteSavedRound(roundNo: Int) {
        pendingDelete.value = PendingLottoDelete(roundNo = roundNo)
    }

    fun requestDeleteSavedTicket(ticketId: Long) {
        pendingDelete.value = PendingLottoDelete(ticketId = ticketId)
    }

    fun dismissDeleteRequest() {
        pendingDelete.value = null
    }

    fun confirmDeleteRequest() {
        val target = pendingDelete.value ?: return
        viewModelScope.launch {
            runCatching {
                when {
                    target.ticketId != null -> repository.deleteLottoTicket(target.ticketId)
                    target.roundNo != null -> repository.deleteLottoRound(target.roundNo)
                }
            }.onSuccess {
                statusMessage.value = when {
                    target.ticketId != null -> "선택한 번호를 삭제했습니다."
                    target.roundNo != null -> "${target.roundNo}회차 저장 번호를 삭제했습니다."
                    else -> null
                }
                pendingDelete.value = null
            }.onFailure { error ->
                statusMessage.value = error.message ?: "삭제에 실패했습니다."
            }
        }
    }

    fun generateChatGpt() {
        if (isGenerating.value) return
        isGenerating.value = true
        lastGeneratedSource.value = sourceChatGpt
        viewModelScope.launch {
            val mode = generationMode.value
            try {
                delay(16)
                runCatching {
                    val history = repository.getAllLottoHistory()
                    withContext(Dispatchers.Default) {
                        LottoNumberGenerator.generateBalanced(history, mode = mode)
                    }
                }.onSuccess { tickets ->
                    generatedChatGpt.value = tickets
                    statusMessage.value = "균형형 번호를 ${mode.label} 모드로 생성했습니다."
                }.onFailure { error ->
                    statusMessage.value = error.message ?: "균형형 번호 생성에 실패했습니다."
                }
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun generateGemini() {
        if (isGenerating.value) return
        isGenerating.value = true
        lastGeneratedSource.value = sourceGemini
        viewModelScope.launch {
            val mode = generationMode.value
            try {
                delay(16)
                runCatching {
                    val history = repository.getAllLottoHistory()
                    withContext(Dispatchers.Default) {
                        LottoNumberGenerator.generateDiversified(history, mode = mode)
                    }
                }.onSuccess { tickets ->
                    generatedGemini.value = tickets
                    statusMessage.value = "분산형 번호를 ${mode.label} 모드로 생성했습니다."
                }.onFailure { error ->
                    statusMessage.value = error.message ?: "분산형 번호 생성에 실패했습니다."
                }
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun saveDraw() {
        viewModelScope.launch {
            runCatching {
                repository.saveLottoDraw(
                    roundNo = roundInput.value.toIntOrNull(),
                    numbers = numberInputs.value.mapNotNull(String::toIntOrNull),
                    bonusNumber = bonusNumberInput.value.toIntOrNull(),
                )
            }.onSuccess { savedRoundNo ->
                refreshLatestRound()
                queryRoundInput.value = savedRoundNo.toString()
                numberInputs.value = List(6) { "" }
                bonusNumberInput.value = ""
                roundInput.value = (savedRoundNo + 1).toString()
                selectedTab.value = LottoTab.DRAW
                statusMessage.value = "${savedRoundNo}회차 당첨 번호가 저장되었습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "로또 당첨 번호 저장에 실패했습니다."
            }
        }
    }

    fun syncOfficialDrawsNow() {
        if (_isOfficialSyncing.value) return
        _isOfficialSyncing.value = true
        repository.markLotterySyncRunning(LotteryProduct.LOTTO_645)
        viewModelScope.launch {
            try {
                val result = repository.syncOfficialLotteryDraws(LotteryProduct.LOTTO_645)
                repository.markLotterySyncSuccess(result)
                refreshLatestRound()
                roundInput.value = (result.latestOfficialRound + 1).toString()
                statusMessage.value = if (result.savedCount > 0) {
                    "공식 로또 당첨번호 ${result.savedCount}개 회차를 저장했습니다."
                } else {
                    "${result.latestOfficialRound}회 공식 번호까지 확인했습니다."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.toLotterySyncUserMessage()
                repository.markLotterySyncFailure(
                    LotteryProduct.LOTTO_645,
                    com.habittracker.data.lotto.LotteryDrawSyncScheduler.expectedDrawDate(LotteryProduct.LOTTO_645),
                    message,
                )
                statusMessage.value = message
            } finally {
                _isOfficialSyncing.value = false
            }
        }
    }

    private suspend fun refreshLatestRound() {
        val latest = repository.getLatestLottoRoundNo()
        latestRoundNo.value = latest
        if (roundInput.value.isBlank() && latest != null) {
            roundInput.value = (latest + 1).toString()
        }
    }

    fun deleteSavedSet(sourceLabel: String, note: String) {
        viewModelScope.launch {
            runCatching {
                repository.deleteLottoSet(sourceLabel, note)
            }.onSuccess {
                statusMessage.value = "저장된 세트를 삭제했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "세트 삭제에 실패했습니다."
            }
        }
    }

    fun markSavedSetPurchased(sourceLabel: String, note: String) {
        viewModelScope.launch {
            runCatching {
                repository.markLottoSetPurchased(sourceLabel, note)
            }.onSuccess {
                statusMessage.value = "세트를 구매 처리했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "세트 구매 처리에 실패했습니다."
            }
        }
    }

    private suspend fun saveGeneratedBatchInternal(roundNo: Int, sourceLabel: String, tickets: List<LottoGeneratedTicket>) {
        runCatching {
            repository.saveLottoBatch(
                roundNo = roundNo,
                sourceLabel = sourceLabel,
                tickets = tickets,
            )
        }.onSuccess { savedCount ->
            when (sourceLabel) {
                sourceChatGpt -> generatedChatGpt.value = emptyList()
                sourceGemini -> generatedGemini.value = emptyList()
            }
            if (lastGeneratedSource.value == sourceLabel) {
                lastGeneratedSource.value = null
            }
            statusMessage.value = "${roundNo}회차 ${sourceLabel} 번호 ${savedCount}게임이 저장되었습니다."
        }.onFailure { error ->
            statusMessage.value = error.message ?: "생성 번호 저장에 실패했습니다."
        }
    }

    fun savePurchase(
        purchaseDate: String,
        lottoType: String,
        roundNo: String,
        amount: String,
        memo: String,
        onSuccess: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.saveLottoPurchase(
                    purchaseDate = java.time.LocalDate.parse(purchaseDate),
                    lottoType = lottoType,
                    roundNo = roundNo.toIntOrNull(),
                    amount = amount.digitsOnly().toIntOrNull() ?: 0,
                    memo = memo,
                )
            }.onSuccess {
                statusMessage.value = "구입 이력이 저장되었습니다."
                onSuccess?.invoke()
            }.onFailure { error ->
                statusMessage.value = error.message ?: "구입 이력 저장에 실패했습니다."
            }
        }
    }

    fun importPurchaseQr(rawValue: String) {
        viewModelScope.launch {
            runCatching {
                repository.importLottoQrPurchase(LottoQrParser.parse(rawValue))
            }.onSuccess { gameCount ->
                statusMessage.value = "QR 실물복권 ${gameCount}개 등록했습니다."
            }.onFailure { error ->
                val reason = error.message?.takeIf(String::isNotBlank) ?: "알 수 없는 오류"
                statusMessage.value = "로또 QR 등록에 실패했습니다. $reason"
            }
        }
    }

    fun reportQrScanFailure(message: String) {
        val reason = message.ifBlank { "QR 내용을 읽지 못했습니다." }
        statusMessage.value = "로또 QR 스캔에 실패했습니다. $reason"
    }

    fun deletePurchase(purchaseId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteLottoPurchase(purchaseId) }
                .onSuccess { statusMessage.value = "구입 이력을 삭제했습니다." }
                .onFailure { error -> statusMessage.value = error.message ?: "구입 이력 삭제에 실패했습니다." }
        }
    }

    fun saveWinning(
        lottoType: String,
        roundNo: String,
        amount: String,
        memo: String,
        onSuccess: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.saveLottoWinning(
                    roundNo = roundNo.toIntOrNull() ?: 0,
                    lottoType = lottoType,
                    amount = amount.digitsOnly().toLongOrNull() ?: 0L,
                    memo = memo,
                )
            }.onSuccess {
                statusMessage.value = "당첨 이력이 저장되었습니다."
                onSuccess?.invoke()
            }.onFailure { error ->
                statusMessage.value = error.message ?: "당첨 이력 저장에 실패했습니다."
            }
        }
    }

    fun deleteWinning(winningId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteLottoWinning(winningId) }
                .onSuccess { statusMessage.value = "당첨 이력을 삭제했습니다." }
                .onFailure { error -> statusMessage.value = error.message ?: "당첨 이력 삭제에 실패했습니다." }
        }
    }
}

private fun toWinningTypeStat(entity: LottoWinningStatEntity): LottoWinningTypeStat =
    LottoWinningTypeStat(
        sourceLabel = entity.sourceLabel,
        generationVersion = entity.generationVersion,
        counts = mapOf(
            "5등" to entity.rank5Count,
            "4등" to entity.rank4Count,
            "3등" to entity.rank3Count,
            "2등" to entity.rank2Count,
            "1등" to entity.rank1Count,
        ),
        evaluatedTicketCount = entity.evaluatedTicketCount,
        stylePassCount = entity.stylePassCount,
        averageStyleScore = if (entity.evaluatedTicketCount == 0) 0 else entity.styleScoreTotal / entity.evaluatedTicketCount,
        averageAnalysisScore = if (entity.scoredTicketCount == 0) null else entity.analysisScoreTotal / entity.scoredTicketCount,
        averageMatchCount = if (entity.evaluatedTicketCount == 0) 0.0 else entity.matchCountTotal.toDouble() / entity.evaluatedTicketCount,
    )

data class LottoUiState(
    val selectedTab: LottoTab = LottoTab.GENERATOR,
    val roundInput: String = "",
    val queryRoundInput: String = "",
    val savedRoundQueryInput: String = "",
    val numberInputs: List<String> = List(6) { "" },
    val bonusNumberInput: String = "",
    val generationMode: LottoGenerationMode = LottoGenerationMode.BASIC,
    val isGenerating: Boolean = false,
    val isHistoryLoading: Boolean = false,
    val statusMessage: String? = null,
    val chatGptResults: List<LottoGeneratedTicket> = emptyList(),
    val geminiResults: List<LottoGeneratedTicket> = emptyList(),
    val savedDraws: List<LottoDrawEntity> = emptyList(),
    val allDraws: List<LottoDrawEntity> = emptyList(),
    val savedTickets: List<LottoTicketEntity> = emptyList(),
    val allSavedTickets: List<LottoTicketEntity> = emptyList(),
    val physicalQrTickets: List<LottoTicketEntity> = emptyList(),
    val purchases: List<LottoPurchaseEntity> = emptyList(),
    val winnings: List<LottoWinningEntity> = emptyList(),
    val canLoadMorePurchases: Boolean = false,
    val canLoadMoreWinnings: Boolean = false,
    val totalPurchaseAmount: Long = 0L,
    val totalWinningAmount: Long = 0L,
    val pensionPurchaseAmount: Long = 0L,
    val pensionWinningAmount: Long = 0L,
    val selectedStatsRange: LottoStatsRange = LottoStatsRange.WEEKLY,
    val stats: List<LottoPeriodStatRow> = emptyList(),
    val latestSavedRoundNo: Int? = null,
    val nextRoundNo: Int? = null,
    val pendingDeleteRoundNo: Int? = null,
    val pendingDeleteTicketId: Long? = null,
    val lastGeneratedSource: String? = null,
    val winningTypeStats: List<LottoWinningTypeStat> = emptyList(),
    val scorePerformances: List<LottoScorePerformance> = emptyList(),
    val controlComparisons: List<LottoControlComparison> = emptyList(),
)

data class LottoWinningTypeStat(
    val sourceLabel: String,
    val generationVersion: String,
    val counts: Map<String, Int>,
    val evaluatedTicketCount: Int,
    val stylePassCount: Int,
    val averageStyleScore: Int,
    val averageAnalysisScore: Double?,
    val averageMatchCount: Double,
)
