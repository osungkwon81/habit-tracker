package com.habittracker.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.DailyDiaryEntity
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

private const val diaryPageSize = 20
private const val diarySearchDebounceMillis = 300L

/** 문자열 대신 enum을 사용하면 오타로 존재하지 않는 화면 상태가 만들어지는 것을 막을 수 있다. */
enum class DiaryScreenMode {
    LIST,
    DETAIL,
    EDITOR,
}

private data class DiaryContentState(
    val diary: DailyDiaryEntity?,
    val statusMessage: String?,
    val searchQuery: String,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DiaryViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val reloadToken = MutableStateFlow(0)
    private val message = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val screenMode = MutableStateFlow(DiaryScreenMode.LIST)
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

    private val selectedDiary = combine(selectedDate, reloadToken) { date, token -> date to token }
        .flatMapLatest { (date, _) -> flow { emit(repository.getDiary(date)) } }

    /*
     * 많은 Flow를 한 번에 합치면 Array<Any?>와 강제 형변환이 생긴다.
     * 관련 상태를 작은 data class로 먼저 묶으면 이후 combine이 타입 안전해진다.
     */
    private val contentState = combine(
        selectedDiary,
        message,
        searchQuery,
    ) { diary, statusMessage, query ->
        DiaryContentState(diary, statusMessage, query)
    }

    val uiState: StateFlow<DiaryUiState> = combine(
        contentState,
        diaryListFlow,
        screenMode,
    ) { content, diaryList, mode ->
        DiaryUiState(
            diaryDate = content.diary?.diaryDate ?: selectedDate.value,
            title = content.diary?.title.orEmpty(),
            body = content.diary?.body.orEmpty(),
            weather = content.diary?.weather ?: "맑음",
            imageUris = content.diary?.imageUris?.split("\n")?.filter(String::isNotBlank) ?: emptyList(),
            statusMessage = content.statusMessage,
            searchQuery = content.searchQuery,
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
                screenMode.value = DiaryScreenMode.DETAIL
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
        screenMode.value = DiaryScreenMode.DETAIL
    }

    fun showList() {
        screenMode.value = DiaryScreenMode.LIST
        message.value = null
    }

    fun editCurrentDiary() {
        screenMode.value = DiaryScreenMode.EDITOR
        message.value = null
    }

    fun startNewDiary() {
        selectedDate.value = LocalDate.now()
        reloadToken.value += 1
        message.value = null
        screenMode.value = DiaryScreenMode.EDITOR
    }

    fun saveDiary(rawDate: String, title: String, body: String, weather: String, imageUris: List<String>) {
        viewModelScope.launch {
            runCatching {
                val diaryDate = LocalDate.parse(rawDate)
                repository.saveDiary(diaryDate = diaryDate, title = title, body = body, weather = weather, imageUris = imageUris)
                selectedDate.value = diaryDate
                reloadToken.value += 1
            }.onSuccess {
                screenMode.value = DiaryScreenMode.DETAIL
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
    val screenMode: DiaryScreenMode = DiaryScreenMode.LIST,
)
