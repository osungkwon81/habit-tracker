package com.habittracker.ui.lotto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoPurchaseEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.LottoWinningEntity
import com.habittracker.data.local.model.LottoMonthlyStatRow
import com.habittracker.data.lotto.LottoGeneratedTicket
import com.habittracker.data.lotto.LottoGenerationMode
import com.habittracker.data.lotto.LottoNumberGenerator
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val generatorTab = "generator"
private const val drawTab = "draw"
private const val purchaseTab = "purchase"
private const val winningTab = "winning"
private const val savedTab = "saved"
private const val statsTab = "stats"
private const val sourceChatGpt = "ChatGPT"
private const val sourceGemini = "Gemini"

private data class PendingLottoBatchSave(
    val roundNo: Int,
    val sourceLabel: String,
    val tickets: List<LottoGeneratedTicket>,
)

private data class PendingLottoDelete(
    val roundNo: Int? = null,
    val ticketId: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LottoViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(generatorTab)
    private val roundInput = MutableStateFlow("")
    private val queryRoundInput = MutableStateFlow("")
    private val appliedQueryRoundInput = MutableStateFlow("")
    private val numberInputs = MutableStateFlow(List(6) { "" })
    private val generationMode = MutableStateFlow(LottoGenerationMode.PRECISE)
    private val isGenerating = MutableStateFlow(false)
    private val isHistoryLoading = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val generatedChatGpt = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val generatedGemini = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val latestRoundNo = MutableStateFlow<Int?>(null)
    private val pendingBatchSave = MutableStateFlow<PendingLottoBatchSave?>(null)
    private val pendingDelete = MutableStateFlow<PendingLottoDelete?>(null)
    private val lastGeneratedSource = MutableStateFlow<String?>(null)

    private val observedDraws = appliedQueryRoundInput.flatMapLatest { query ->
        repository.observeLottoDraws(query.toIntOrNull(), limit = 20)
    }.onEach {
        isHistoryLoading.value = false
    }
    private val latestSavedTickets = latestRoundNo.flatMapLatest { roundNo ->
        if (roundNo == null) flowOf(emptyList()) else repository.observeSavedLottoTicketsByRound(roundNo)
    }

    val uiState: StateFlow<LottoUiState> = combine(
        selectedTab,
        observedDraws,
        repository.observeLottoDraws(roundNo = null, limit = 200),
        latestSavedTickets,
        repository.observeSavedLottoTickets(limit = 100),
        repository.observeLottoPurchases(limit = 100),
        repository.observeLottoWinnings(limit = 100),
        repository.observeTotalLottoPurchaseAmount(),
        repository.observeTotalLottoWinningAmount(),
        repository.observeLottoMonthlyStats(limit = 12),
        roundInput,
        queryRoundInput,
        numberInputs,
        generationMode,
        isGenerating,
        isHistoryLoading,
        statusMessage,
        generatedChatGpt,
        generatedGemini,
        latestRoundNo,
        pendingBatchSave,
        pendingDelete,
        lastGeneratedSource,
    ) { values ->
        val tab = values[0] as String
        val draws = values[1] as List<LottoDrawEntity>
        val allDraws = values[2] as List<LottoDrawEntity>
        val latestTickets = values[3] as List<LottoTicketEntity>
        val allTickets = values[4] as List<LottoTicketEntity>
        val purchases = values[5] as List<LottoPurchaseEntity>
        val winnings = values[6] as List<LottoWinningEntity>
        val totalPurchaseAmount = values[7] as Long
        val totalWinningAmount = values[8] as Long
        val monthlyStats = values[9] as List<LottoMonthlyStatRow>
        val round = values[10] as String
        val query = values[11] as String
        val numbers = values[12] as List<String>
        val mode = values[13] as LottoGenerationMode
        val generating = values[14] as Boolean
        val historyLoading = values[15] as Boolean
        val message = values[16] as String?
        val chatGpt = values[17] as List<LottoGeneratedTicket>
        val gemini = values[18] as List<LottoGeneratedTicket>
        val latestRound = values[19] as Int?
        val pendingSave = values[20] as PendingLottoBatchSave?
        val pendingDeleteState = values[21] as PendingLottoDelete?
        val recentSource = values[22] as String?

        LottoUiState(
            selectedTab = tab,
            roundInput = round,
            queryRoundInput = query,
            numberInputs = numbers,
            generationMode = mode,
            isGenerating = generating,
            isHistoryLoading = historyLoading,
            statusMessage = message,
            chatGptResults = chatGpt,
            geminiResults = gemini,
            savedDraws = draws,
            allDraws = allDraws,
            savedTickets = latestTickets,
            allSavedTickets = allTickets,
            purchases = purchases,
            winnings = winnings,
            totalPurchaseAmount = totalPurchaseAmount,
            totalWinningAmount = totalWinningAmount,
            monthlyStats = monthlyStats,
            latestSavedRoundNo = latestRound,
            nextRoundNo = latestRound?.plus(1),
            pendingOverwriteRoundNo = pendingSave?.roundNo,
            pendingOverwriteSourceLabel = pendingSave?.sourceLabel,
            pendingDeleteRoundNo = pendingDeleteState?.roundNo,
            pendingDeleteTicketId = pendingDeleteState?.ticketId,
            lastGeneratedSource = recentSource,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LottoUiState(),
    )

    init {
        viewModelScope.launch {
            repository.seedLottoDrawsIfEmpty()
            refreshLatestRound()
        }
        isHistoryLoading.value = true
    }

    fun selectGeneratorTab() {
        selectedTab.value = generatorTab
    }

    fun selectDrawTab() {
        selectedTab.value = drawTab
    }

    fun selectPurchaseTab() {
        selectedTab.value = purchaseTab
    }

    fun selectWinningTab() {
        selectedTab.value = winningTab
    }

    fun selectSavedTab() {
        selectedTab.value = savedTab
    }

    fun selectStatsTab() {
        selectedTab.value = statsTab
    }

    fun updateRoundInput(value: String) {
        roundInput.value = value.filter(Char::isDigit)
    }

    fun updateQueryRoundInput(value: String) {
        queryRoundInput.value = value.filter(Char::isDigit)
    }

    fun submitDrawQuery() {
        isHistoryLoading.value = true
        appliedQueryRoundInput.value = queryRoundInput.value
    }

    fun updateNumberInput(index: Int, value: String) {
        numberInputs.value = numberInputs.value.toMutableList().also { list ->
            list[index] = value.filter(Char::isDigit).take(2)
        }
    }

    fun updateGenerationMode(mode: LottoGenerationMode) {
        generationMode.value = mode
    }

    fun applyGeneratedNumbers(numbers: List<Int>) {
        numberInputs.value = numbers.map(Int::toString)
        statusMessage.value = "생성한 번호를 당첨 번호 입력 칸으로 복사했습니다."
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
            if (repository.hasSavedLottoBatch(roundNo = targetRoundNo, sourceLabel = sourceLabel)) {
                pendingBatchSave.value = PendingLottoBatchSave(
                    roundNo = targetRoundNo,
                    sourceLabel = sourceLabel,
                    tickets = tickets.take(5),
                )
                statusMessage.value = "${targetRoundNo}회차 ${sourceLabel} 번호가 이미 저장되어 있습니다."
                return@launch
            }
            saveGeneratedBatchInternal(roundNo = targetRoundNo, sourceLabel = sourceLabel, tickets = tickets, overwrite = false)
        }
    }

    fun confirmOverwriteGeneratedBatch() {
        val pendingSave = pendingBatchSave.value ?: return
        viewModelScope.launch {
            saveGeneratedBatchInternal(
                roundNo = pendingSave.roundNo,
                sourceLabel = pendingSave.sourceLabel,
                tickets = pendingSave.tickets,
                overwrite = true,
            )
        }
    }

    fun dismissOverwriteGeneratedBatch() {
        pendingBatchSave.value = null
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
        viewModelScope.launch {
            val history = repository.getAllLottoHistory()
            val mode = generationMode.value
            isGenerating.value = true
            lastGeneratedSource.value = sourceChatGpt
            delay(16)
            runCatching {
                withContext(Dispatchers.Default) {
                    LottoNumberGenerator.generateChatGpt(history, mode = mode)
                }
            }.onSuccess { tickets ->
                generatedChatGpt.value = tickets
                statusMessage.value = "ChatGPT 방식 번호를 ${mode.label} 모드로 생성했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "ChatGPT 번호 생성에 실패했습니다."
            }
            isGenerating.value = false
        }
    }

    fun generateGemini() {
        viewModelScope.launch {
            val history = repository.getAllLottoHistory()
            val mode = generationMode.value
            isGenerating.value = true
            lastGeneratedSource.value = sourceGemini
            delay(16)
            runCatching {
                withContext(Dispatchers.Default) {
                    LottoNumberGenerator.generateGemini(history, mode = mode)
                }
            }.onSuccess { tickets ->
                generatedGemini.value = tickets
                statusMessage.value = "Gemini 방식 번호를 ${mode.label} 모드로 생성했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "Gemini 번호 생성에 실패했습니다."
            }
            isGenerating.value = false
        }
    }

    fun saveDraw() {
        viewModelScope.launch {
            runCatching {
                repository.saveLottoDraw(
                    roundNo = roundInput.value.toIntOrNull(),
                    numbers = numberInputs.value.mapNotNull(String::toIntOrNull),
                )
            }.onSuccess { savedRoundNo ->
                refreshLatestRound()
                queryRoundInput.value = savedRoundNo.toString()
                numberInputs.value = List(6) { "" }
                roundInput.value = (savedRoundNo + 1).toString()
                selectedTab.value = drawTab
                statusMessage.value = "${savedRoundNo}회차 당첨 번호가 저장되었습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "로또 당첨 번호 저장에 실패했습니다."
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

    private suspend fun saveGeneratedBatchInternal(roundNo: Int, sourceLabel: String, tickets: List<LottoGeneratedTicket>, overwrite: Boolean) {
        runCatching {
            repository.saveLottoBatch(
                roundNo = roundNo,
                sourceLabel = sourceLabel,
                tickets = tickets,
                overwrite = overwrite,
            )
        }.onSuccess {
            pendingBatchSave.value = null
            statusMessage.value = "${roundNo}회차 ${sourceLabel} 번호 5게임이 저장되었습니다."
        }.onFailure { error ->
            statusMessage.value = error.message ?: "생성 번호 저장에 실패했습니다."
        }
    }

    fun savePurchase(purchaseDate: String, lottoType: String, amount: String, memo: String) {
        viewModelScope.launch {
            runCatching {
                repository.saveLottoPurchase(
                    purchaseDate = java.time.LocalDate.parse(purchaseDate),
                    lottoType = lottoType,
                    amount = amount.filter(Char::isDigit).toIntOrNull() ?: 0,
                    memo = memo,
                )
            }.onSuccess {
                statusMessage.value = "구입 이력이 저장되었습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "구입 이력 저장에 실패했습니다."
            }
        }
    }

    fun deletePurchase(purchaseId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteLottoPurchase(purchaseId) }
                .onSuccess { statusMessage.value = "구입 이력을 삭제했습니다." }
                .onFailure { error -> statusMessage.value = error.message ?: "구입 이력 삭제에 실패했습니다." }
        }
    }

    fun saveWinning(roundNo: String, amount: String, memo: String) {
        viewModelScope.launch {
            runCatching {
                repository.saveLottoWinning(
                    roundNo = roundNo.toIntOrNull() ?: 0,
                    amount = amount.filter(Char::isDigit).toLongOrNull() ?: 0L,
                    memo = memo,
                )
            }.onSuccess {
                statusMessage.value = "당첨 이력이 저장되었습니다."
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

data class LottoUiState(
    val selectedTab: String = generatorTab,
    val roundInput: String = "",
    val queryRoundInput: String = "",
    val numberInputs: List<String> = List(6) { "" },
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
    val purchases: List<LottoPurchaseEntity> = emptyList(),
    val winnings: List<LottoWinningEntity> = emptyList(),
    val totalPurchaseAmount: Long = 0L,
    val totalWinningAmount: Long = 0L,
    val monthlyStats: List<LottoMonthlyStatRow> = emptyList(),
    val latestSavedRoundNo: Int? = null,
    val nextRoundNo: Int? = null,
    val pendingOverwriteRoundNo: Int? = null,
    val pendingOverwriteSourceLabel: String? = null,
    val pendingDeleteRoundNo: Int? = null,
    val pendingDeleteTicketId: Long? = null,
    val lastGeneratedSource: String? = null,
)
