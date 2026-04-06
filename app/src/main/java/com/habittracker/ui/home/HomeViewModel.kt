package com.habittracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val currentMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<HomeUiState> = currentMonth
        .flatMapLatest { month ->
            combine(
                repository.observeMonthlySummaries(month.atDay(1), month.atEndOfMonth()),
                repository.observeMonthlyDiarySummaries(month.atDay(1), month.atEndOfMonth()),
            ) { summaries, diaries ->
                HomeUiState(
                    currentMonth = month,
                    summaries = summaries.associateBy(RecordSummaryRow::recordDate),
                    diarySummaries = diaries.associateBy(DiarySummaryRow::diaryDate),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    init {
        viewModelScope.launch {
            repository.seedDefaultTaskItemsIfEmpty()
        }
    }

    fun goToPreviousMonth() {
        currentMonth.value = currentMonth.value.minusMonths(1)
    }

    fun goToNextMonth() {
        currentMonth.value = currentMonth.value.plusMonths(1)
    }
}

data class HomeUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val summaries: Map<LocalDate, RecordSummaryRow> = emptyMap(),
    val diarySummaries: Map<LocalDate, DiarySummaryRow> = emptyMap(),
)