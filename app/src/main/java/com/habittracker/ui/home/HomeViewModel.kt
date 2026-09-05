package com.habittracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val currentMonth = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    private val monthlyState = currentMonth.flatMapLatest { month ->
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
    private val selectedDetails = selectedDate.flatMapLatest<LocalDate?, Pair<LocalDate?, List<RecordDetailRow>>> { date ->
        if (date == null) flow { emit(null to emptyList<RecordDetailRow>()) }
        else repository.observeRecordDetails(date).map { date to it }
    }
    val uiState: StateFlow<HomeUiState> = combine(monthlyState, selectedDetails) { month, (date, details) ->
        val visibleDate = date?.takeIf { YearMonth.from(it) == month.currentMonth }
        month.copy(selectedDate = visibleDate, selectedRecordDetails = if (visibleDate != null) details else emptyList())
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    init {
        viewModelScope.launch {
            repository.syncManagedTaskItems()
        }
    }

    fun goToPreviousMonth() {
        currentMonth.value = currentMonth.value.minusMonths(1)
    }

    fun goToNextMonth() {
        currentMonth.value = currentMonth.value.plusMonths(1)
    }

    fun selectDate(date: LocalDate?) {
        selectedDate.value = date
    }
}

data class HomeUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate? = null,
    val summaries: Map<LocalDate, RecordSummaryRow> = emptyMap(),
    val diarySummaries: Map<LocalDate, DiarySummaryRow> = emptyMap(),
    val selectedRecordDetails: List<RecordDetailRow> = emptyList(),
)
