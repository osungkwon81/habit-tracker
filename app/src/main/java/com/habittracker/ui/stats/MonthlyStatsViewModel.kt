package com.habittracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class MonthlyStatsViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val currentMonth = MutableStateFlow(YearMonth.now())

    val uiState: StateFlow<MonthlyStatsUiState> = currentMonth
        .flatMapLatest { month ->
            combine(
                repository.observeMonthlyStats(month.atDay(1), month.atEndOfMonth()),
                repository.observeMonthlySummaries(month.atDay(1), month.atEndOfMonth()),
                repository.observeDailyTaskStats(month.atDay(1), month.atEndOfMonth()),
            ) { stats, dailySummaries, dailyTaskStats ->
                val summaries = stats.map { stat ->
                    TaskStatSummary(
                        taskItemMasterId = stat.taskItemMasterId,
                        taskName = stat.taskName,
                        valueType = stat.valueType,
                        totalNumber = stat.totalNumber,
                        totalDuration = stat.totalDuration,
                        completedCount = stat.completedCount,
                        colorHex = repository.getTaskColorHex(stat.taskItemMasterId, stat.taskName),
                    )
                }
                val chartGroups = dailyTaskStats
                    .groupBy { it.taskItemMasterId to it.taskName }
                    .mapNotNull { (_, points) ->
                        val first = points.firstOrNull() ?: return@mapNotNull null
                        TaskSeriesChart(
                            taskItemMasterId = first.taskItemMasterId,
                            taskName = first.taskName,
                            valueType = first.valueType,
                            colorHex = repository.getTaskColorHex(first.taskItemMasterId, first.taskName),
                            totalNumber = summaries.firstOrNull { it.taskItemMasterId == first.taskItemMasterId }?.totalNumber,
                            totalDuration = summaries.firstOrNull { it.taskItemMasterId == first.taskItemMasterId }?.totalDuration,
                            completedCount = summaries.firstOrNull { it.taskItemMasterId == first.taskItemMasterId }?.completedCount ?: 0,
                            points = points.sortedBy { it.recordDate }.map { point ->
                                TaskSeriesPoint(
                                    date = point.recordDate,
                                    value = when (first.valueType) {
                                        "EXERCISE" -> point.totalNumber ?: 0.0
                                        else -> point.totalNumber ?: point.completedCount.toDouble()
                                    },
                                    durationMinutes = point.totalDuration,
                                    completedCount = point.completedCount,
                                )
                            },
                        )
                    }
                MonthlyStatsUiState(
                    currentMonth = month,
                    stats = summaries,
                    dailySummaries = dailySummaries,
                    taskCharts = chartGroups,
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
            repository.syncManagedTaskItems()
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
    val stats: List<TaskStatSummary> = emptyList(),
    val dailySummaries: List<RecordSummaryRow> = emptyList(),
    val taskCharts: List<TaskSeriesChart> = emptyList(),
)

data class TaskStatSummary(
    val taskItemMasterId: Long,
    val taskName: String,
    val valueType: String,
    val totalNumber: Double?,
    val totalDuration: Int?,
    val completedCount: Int,
    val colorHex: String,
)

data class TaskSeriesChart(
    val taskItemMasterId: Long,
    val taskName: String,
    val valueType: String,
    val colorHex: String,
    val totalNumber: Double?,
    val totalDuration: Int?,
    val completedCount: Int,
    val points: List<TaskSeriesPoint>,
)

data class TaskSeriesPoint(
    val date: LocalDate,
    val value: Double,
    val durationMinutes: Int?,
    val completedCount: Int,
)
