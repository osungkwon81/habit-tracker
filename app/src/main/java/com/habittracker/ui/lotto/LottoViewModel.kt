package com.habittracker.ui.lotto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoTicketEntity
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val generatorTab = "generator"
private const val historyTab = "history"

@OptIn(ExperimentalCoroutinesApi::class)
class LottoViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(generatorTab)
    private val roundInput = MutableStateFlow("")
    private val queryRoundInput = MutableStateFlow("")
    private val appliedQueryRoundInput = MutableStateFlow("")
    private val numberInputs = MutableStateFlow(List(6) { "" })
    private val generationMode = MutableStateFlow(LottoGenerationMode.BASIC)
    private val isGenerating = MutableStateFlow(false)
    private val isHistoryLoading = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val generatedChatGpt = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val generatedGemini = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val latestRoundNo = MutableStateFlow<Int?>(null)

    private val observedDraws = appliedQueryRoundInput.flatMapLatest { query ->
        repository.observeLottoDraws(query.toIntOrNull(), limit = 20)
    }.onEach {
        isHistoryLoading.value = false
    }

    val uiState: StateFlow<LottoUiState> = combine(
        selectedTab,
        observedDraws,
        repository.observeSavedLottoTickets(limit = 20),
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
    ) { values ->
        val tab = values[0] as String
        val draws = values[1] as List<LottoDrawEntity>
        val tickets = values[2] as List<LottoTicketEntity>
        val round = values[3] as String
        val query = values[4] as String
        val numbers = values[5] as List<String>
        val mode = values[6] as LottoGenerationMode
        val generating = values[7] as Boolean
        val historyLoading = values[8] as Boolean
        val message = values[9] as String?
        val chatGpt = values[10] as List<LottoGeneratedTicket>
        val gemini = values[11] as List<LottoGeneratedTicket>
        val latestRound = values[12] as Int?

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
            savedTickets = tickets,
            latestSavedRoundNo = latestRound,
            nextRoundNo = latestRound?.plus(1),
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

    fun selectHistoryTab() {
        selectedTab.value = historyTab
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

    fun saveGeneratedTicket(numbers: List<Int>, sourceLabel: String) {
        viewModelScope.launch {
            runCatching {
                repository.saveLottoTicket(numbers = numbers, sourceLabel = sourceLabel)
            }.onSuccess {
                statusMessage.value = "생성 번호를 저장했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "생성 번호 저장에 실패했습니다."
            }
        }
    }

    fun saveAllGeneratedTickets(sourceLabel: String, tickets: List<LottoGeneratedTicket>) {
        viewModelScope.launch {
            if (tickets.isEmpty()) {
                statusMessage.value = "저장할 생성 번호가 없습니다."
                return@launch
            }
            runCatching {
                tickets.forEach { ticket ->
                    repository.saveLottoTicket(numbers = ticket.numbers, sourceLabel = sourceLabel)
                }
            }.onSuccess {
                statusMessage.value = "${tickets.size}개 조합을 모두 저장했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "전체 번호 저장에 실패했습니다."
            }
        }
    }

    fun generateChatGpt() {
        viewModelScope.launch {
            val history = repository.getAllLottoHistory()
            val mode = generationMode.value
            isGenerating.value = true
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
                selectedTab.value = historyTab
                statusMessage.value = "${savedRoundNo}회차 당첨 번호를 저장했습니다."
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
    val savedTickets: List<LottoTicketEntity> = emptyList(),
    val latestSavedRoundNo: Int? = null,
    val nextRoundNo: Int? = null,
)
