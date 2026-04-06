package com.habittracker.ui.lotto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.lotto.LottoGeneratedTicket
import com.habittracker.data.lotto.LottoNumberGenerator
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LottoViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val roundInput = MutableStateFlow("")
    private val queryRoundInput = MutableStateFlow("")
    private val numberInputs = MutableStateFlow(List(6) { "" })
    private val statusMessage = MutableStateFlow<String?>(null)
    private val generatedChatGpt = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val generatedGemini = MutableStateFlow<List<LottoGeneratedTicket>>(emptyList())
    private val latestRoundNo = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<LottoUiState> = queryRoundInput
        .flatMapLatest { query ->
            repository.observeLottoDraws(query.toIntOrNull(), limit = 10)
        }
        .combine(roundInput) { draws, round -> draws to round }
        .combine(queryRoundInput) { (draws, round), query -> Triple(draws, round, query) }
        .combine(numberInputs) { (draws, round, query), numbers -> LottoUiAccumulator(draws, round, query, numbers) }
        .combine(statusMessage) { acc, message -> acc.copy(message = message) }
        .combine(generatedChatGpt) { acc, chatGpt -> acc.copy(chatGpt = chatGpt) }
        .combine(generatedGemini) { acc, gemini -> acc.copy(gemini = gemini) }
        .combine(latestRoundNo) { acc, latestRound ->
            LottoUiState(
                roundInput = acc.round,
                queryRoundInput = acc.query,
                numberInputs = acc.numbers,
                statusMessage = acc.message,
                chatGptResults = acc.chatGpt,
                geminiResults = acc.gemini,
                savedDraws = acc.draws,
                latestSavedRoundNo = latestRound,
                nextRoundNo = latestRound?.plus(1),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LottoUiState(),
        )

    init {
        viewModelScope.launch {
            repository.seedLottoDrawsIfEmpty()
            refreshLatestRound()
        }
    }

    fun updateRoundInput(value: String) {
        roundInput.value = value.filter(Char::isDigit)
    }

    fun updateQueryRoundInput(value: String) {
        queryRoundInput.value = value.filter(Char::isDigit)
    }

    fun updateNumberInput(index: Int, value: String) {
        numberInputs.value = numberInputs.value.toMutableList().also { list ->
            list[index] = value.filter(Char::isDigit).take(2)
        }
    }

    fun applyGeneratedNumbers(numbers: List<Int>) {
        numberInputs.value = numbers.map(Int::toString)
        statusMessage.value = "생성 결과를 입력 칸에 반영했습니다."
    }

    fun generateChatGpt() {
        viewModelScope.launch {
            val history = repository.getAllLottoHistory()
            generatedChatGpt.value = LottoNumberGenerator.generateChatGpt(history)
            statusMessage.value = "챗GPT 기준 추천 번호를 생성했습니다."
        }
    }

    fun generateGemini() {
        viewModelScope.launch {
            val history = repository.getAllLottoHistory()
            generatedGemini.value = LottoNumberGenerator.generateGemini(history)
            statusMessage.value = "제미니 기준 추천 번호를 생성했습니다."
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
                statusMessage.value = "${savedRoundNo}회차를 저장했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "로또 회차 저장에 실패했습니다."
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
    val roundInput: String = "",
    val queryRoundInput: String = "",
    val numberInputs: List<String> = List(6) { "" },
    val statusMessage: String? = null,
    val chatGptResults: List<LottoGeneratedTicket> = emptyList(),
    val geminiResults: List<LottoGeneratedTicket> = emptyList(),
    val savedDraws: List<LottoDrawEntity> = emptyList(),
    val latestSavedRoundNo: Int? = null,
    val nextRoundNo: Int? = null,
)

private data class LottoUiAccumulator(
    val draws: List<LottoDrawEntity>,
    val round: String,
    val query: String,
    val numbers: List<String>,
    val message: String? = null,
    val chatGpt: List<LottoGeneratedTicket> = emptyList(),
    val gemini: List<LottoGeneratedTicket> = emptyList(),
)
