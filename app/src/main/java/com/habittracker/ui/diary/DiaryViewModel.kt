package com.habittracker.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.model.DiarySearchRow
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val message = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val searchResults = MutableStateFlow<List<DiarySearchRow>>(emptyList())

    val uiState: StateFlow<DiaryUiState> = selectedDate
        .flatMapLatest { date ->
            combine(flow { emit(repository.getDiary(date)) }, message, searchQuery, searchResults) { diary, statusMessage, query, results ->
                DiaryUiState(
                    diaryDate = date,
                    title = diary?.title.orEmpty(),
                    body = diary?.body.orEmpty(),
                    weather = diary?.weather ?: "맑음",
                    imageUris = diary?.imageUris?.split("\n")?.filter(String::isNotBlank) ?: emptyList(),
                    statusMessage = statusMessage,
                    searchQuery = query,
                    searchResults = results,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DiaryUiState(),
        )

    fun loadDiary(rawDate: String) {
        runCatching { LocalDate.parse(rawDate) }
            .onSuccess {
                selectedDate.value = it
                message.value = null
            }
            .onFailure {
                message.value = "날짜 형식은 YYYY-MM-DD로 입력해 주세요."
            }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun searchDiaries() {
        viewModelScope.launch {
            val trimmedQuery = searchQuery.value.trim()
            if (trimmedQuery.isBlank()) {
                searchResults.value = emptyList()
                message.value = null
                return@launch
            }
            runCatching {
                repository.searchDiaries(trimmedQuery)
            }.onSuccess { results ->
                searchResults.value = results
                message.value = if (results.isEmpty()) "검색 결과가 없습니다." else "${results.size}건의 일기를 찾았습니다."
            }.onFailure { error ->
                message.value = error.message ?: "일기 검색에 실패했습니다."
            }
        }
    }

    fun openSearchResult(diaryDate: LocalDate) {
        selectedDate.value = diaryDate
        message.value = null
    }

    fun saveDiary(rawDate: String, title: String, body: String, weather: String, imageUris: List<String>) {
        viewModelScope.launch {
            runCatching {
                val diaryDate = LocalDate.parse(rawDate)
                repository.saveDiary(diaryDate = diaryDate, title = title, body = body, weather = weather, imageUris = imageUris)
                selectedDate.value = diaryDate
            }.onSuccess {
                message.value = "일기를 저장했습니다."
            }.onFailure { error ->
                message.value = error.message ?: "일기 저장에 실패했습니다."
            }
        }
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
)