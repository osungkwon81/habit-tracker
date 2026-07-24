package com.habittracker.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.model.DiarySearchRow
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val diaryListMode = "list"
private const val diaryDetailMode = "detail"
private const val diaryEditorMode = "editor"
private const val diaryPageSize = 20
private const val diarySearchDebounceMillis = 300L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DiaryViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val reloadToken = MutableStateFlow(0)
    private val message = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val screenMode = MutableStateFlow(diaryListMode)
    private val visibleLimit = MutableStateFlow(diaryPageSize)

    private val diaryListFlow = combine(searchQuery, visibleLimit) { query, limit -> query to limit }
        .debounce(diarySearchDebounceMillis)
        .distinctUntilChanged()
        .flatMapLatest { (query, limit) ->
            if (query.isBlank()) {
                repository.observeDiaryList(limit)
            } else {
                repository.observeDiaryListByQuery(query, limit)
            }
        }

    val uiState: StateFlow<DiaryUiState> = combine(
        combine(selectedDate, reloadToken) { date, token -> date to token }
            .flatMapLatest { (date, _) -> flow { emit(repository.getDiary(date)) } },
        message,
        searchQuery,
        diaryListFlow,
        screenMode,
    ) { values ->
        val diary = values[0] as com.habittracker.data.local.entity.DailyDiaryEntity?
        val statusMessage = values[1] as String?
        val query = values[2] as String
        val diaryList = values[3] as List<DiarySearchRow>
        val mode = values[4] as String

        DiaryUiState(
            diaryDate = diary?.diaryDate ?: selectedDate.value,
            title = diary?.title.orEmpty(),
            body = diary?.body.orEmpty(),
            weather = diary?.weather ?: "맑음",
            imageUris = diary?.imageUris?.split("\n")?.filter(String::isNotBlank) ?: emptyList(),
            statusMessage = statusMessage,
            searchQuery = query,
            searchResults = diaryList,
            canLoadMore = diaryList.size >= visibleLimit.value,
            screenMode = mode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DiaryUiState(),
    )

    fun loadDiary(rawDate: String) {
        runCatching { LocalDate.parse(rawDate) }
            .onSuccess {
                selectedDate.value = it
                reloadToken.value += 1
                message.value = null
                screenMode.value = diaryDetailMode
            }
            .onFailure {
                message.value = "날짜 형식은 YYYY-MM-DD로 입력해 주세요."
            }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
        visibleLimit.value = diaryPageSize
    }

    fun searchDiaries() {
        message.value = null
    }

    fun loadMoreDiaries() {
        if (!uiState.value.canLoadMore) return
        visibleLimit.value += diaryPageSize
    }

    fun openSearchResult(diaryDate: LocalDate) {
        selectedDate.value = diaryDate
        reloadToken.value += 1
        message.value = null
        screenMode.value = diaryDetailMode
    }

    fun showList() {
        screenMode.value = diaryListMode
        message.value = null
    }

    fun editCurrentDiary() {
        screenMode.value = diaryEditorMode
        message.value = null
    }

    fun startNewDiary() {
        selectedDate.value = LocalDate.now()
        reloadToken.value += 1
        message.value = null
        screenMode.value = diaryEditorMode
    }

    fun saveDiary(rawDate: String, title: String, body: String, weather: String, imageUris: List<String>) {
        viewModelScope.launch {
            runCatching {
                val diaryDate = LocalDate.parse(rawDate)
                repository.saveDiary(diaryDate = diaryDate, title = title, body = body, weather = weather, imageUris = imageUris)
                selectedDate.value = diaryDate
                reloadToken.value += 1
            }.onSuccess {
                screenMode.value = diaryDetailMode
                message.value = "일기가 저장되었습니다."
            }.onFailure { error ->
                message.value = error.message ?: "일기 저장에 실패했습니다."
            }
        }
    }

    fun clearStatusMessage() {
        message.value = null
    }
}

data class DiaryUiState(
    val diaryDate: LocalDate = LocalDate.now(),
    val title: String = "",
    val body: String = "",
    val weather: String = "맑음",
    val imageUris: List<String> = emptyList(),
    val statusMessage: String? = null,
    val searchQuery: String = "",
    val searchResults: List<DiarySearchRow> = emptyList(),
    val canLoadMore: Boolean = false,
    val screenMode: String = diaryListMode,
)
