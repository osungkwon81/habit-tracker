package com.habittracker.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    val uiState: StateFlow<DiaryUiState> = selectedDate
        .flatMapLatest { date ->
            combine(flow { emit(repository.getDiary(date)) }, message) { diary, statusMessage ->
                DiaryUiState(
                    diaryDate = date,
                    title = diary?.title.orEmpty(),
                    body = diary?.body.orEmpty(),
                    weather = diary?.weather ?: "\uB9D1\uC74C",
                    imageUris = diary?.imageUris?.split("\n")?.filter(String::isNotBlank) ?: emptyList(),
                    statusMessage = statusMessage,
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
            .onSuccess { selectedDate.value = it }
            .onFailure { message.value = "\uB0A0\uC9DC \uD615\uC2DD\uC740 YYYY-MM-DD\uB85C \uC785\uB825\uD574 \uC8FC\uC138\uC694." }
    }

    fun saveDiary(rawDate: String, title: String, body: String, weather: String, imageUris: List<String>) {
        viewModelScope.launch {
            runCatching {
                val diaryDate = LocalDate.parse(rawDate)
                repository.saveDiary(diaryDate = diaryDate, title = title, body = body, weather = weather, imageUris = imageUris)
                selectedDate.value = diaryDate
            }.onSuccess {
                message.value = "\uC77C\uAE30\uB97C \uC800\uC7A5\uD588\uC2B5\uB2C8\uB2E4."
            }.onFailure { error ->
                message.value = error.message ?: "\uC77C\uAE30 \uC800\uC7A5\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4."
            }
        }
    }
}

data class DiaryUiState(
    val diaryDate: LocalDate = LocalDate.now(),
    val title: String = "",
    val body: String = "",
    val weather: String = "\uB9D1\uC74C",
    val imageUris: List<String> = emptyList(),
    val statusMessage: String? = null,
)