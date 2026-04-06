package com.habittracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.model.MonthlyStatRow
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyStatsViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val currentMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<MonthlyStatsUiState> = currentMonth
        .flatMapLatest { month ->
            repository.observeMonthlyStats(month.atDay(1), month.atEndOfMonth()).map { stats ->
                MonthlyStatsUiState(
                    currentMonth = month,
                    stats = stats,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MonthlyStatsUiState(),
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

data class MonthlyStatsUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val stats: List<MonthlyStatRow> = emptyList(),
)
