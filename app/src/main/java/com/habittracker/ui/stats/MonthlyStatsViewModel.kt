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
import java.time.Year
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyStatsViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val currentMonth = MutableStateFlow(YearMonth.now())
    private val currentYear = MutableStateFlow(Year.now().value)
    private val periodMode = MutableStateFlow(StatsPeriodMode.MONTH)

    val uiState: StateFlow<MonthlyStatsUiState> = combine(periodMode, currentMonth, currentYear) { mode, month, year ->
        val startDate = if (mode == StatsPeriodMode.MONTH) month.atDay(1) else LocalDate.of(year, 1, 1)
        val endDate = if (mode == StatsPeriodMode.MONTH) month.atEndOfMonth() else LocalDate.of(year, 12, 31)
        StatsPeriodRange(mode = mode, currentMonth = month, currentYear = year, startDate = startDate, endDate = endDate)
    }
        .flatMapLatest { range ->
            combine(
                repository.observeMonthlyStats(range.startDate, range.endDate),
                repository.observeMonthlySummaries(range.startDate, range.endDate),
                repository.observeDailyTaskStats(range.startDate, range.endDate),
            ) { stats, dailySummaries, dailyTaskStats ->
                val summaries = stats.map { stat ->
                    TaskStatSummary(
                        taskItemMasterId = stat.taskItemMasterId,
                        taskName = stat.taskName,
                        valueType = stat.valueType,
                        unit = stat.unit,
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
                            unit = first.unit,
                            colorHex = repository.getTaskColorHex(first.taskItemMasterId, first.taskName),
                            totalNumber = summaries.firstOrNull { it.taskItemMasterId == first.taskItemMasterId }?.totalNumber,
                            totalDuration = summaries.firstOrNull { it.taskItemMasterId == first.taskItemMasterId }?.totalDuration,
                            completedCount = summaries.firstOrNull { it.taskItemMasterId == first.taskItemMasterId }?.completedCount ?: 0,
                            periodMode = range.mode,
                            points = buildChartPoints(points, range.mode, first.valueType),
                        )
                    }
                MonthlyStatsUiState(
                    currentMonth = range.currentMonth,
                    currentYear = range.currentYear,
                    periodMode = range.mode,
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
        if (periodMode.value == StatsPeriodMode.MONTH) {
            currentMonth.value = currentMonth.value.minusMonths(1)
        } else {
            currentYear.value = currentYear.value - 1
        }
    }

    fun goToNextMonth() {
        if (periodMode.value == StatsPeriodMode.MONTH) {
            currentMonth.value = currentMonth.value.plusMonths(1)
        } else {
            currentYear.value = currentYear.value + 1
        }
    }

    fun selectPeriodMode(mode: StatsPeriodMode) {
        periodMode.value = mode
    }
}

enum class StatsPeriodMode {
    MONTH,
    YEAR,
}

private data class StatsPeriodRange(
    val mode: StatsPeriodMode,
    val currentMonth: YearMonth,
    val currentYear: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class MonthlyStatsUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val currentYear: Int = Year.now().value,
    val periodMode: StatsPeriodMode = StatsPeriodMode.MONTH,
    val stats: List<TaskStatSummary> = emptyList(),
    val dailySummaries: List<RecordSummaryRow> = emptyList(),
    val taskCharts: List<TaskSeriesChart> = emptyList(),
)

data class TaskStatSummary(
    val taskItemMasterId: Long,
    val taskName: String,
    val valueType: String,
    val unit: String?,
    val totalNumber: Double?,
    val totalDuration: Int?,
    val completedCount: Int,
    val colorHex: String,
)

data class TaskSeriesChart(
    val taskItemMasterId: Long,
    val taskName: String,
    val valueType: String,
    val unit: String?,
    val colorHex: String,
    val totalNumber: Double?,
    val totalDuration: Int?,
    val completedCount: Int,
    val periodMode: StatsPeriodMode,
    val points: List<TaskSeriesPoint>,
)

data class TaskSeriesPoint(
    val date: LocalDate,
    val value: Double,
    val durationMinutes: Int?,
    val completedCount: Int,
)

private fun buildChartPoints(
    points: List<com.habittracker.data.local.model.DailyTaskStatRow>,
    periodMode: StatsPeriodMode,
    valueType: String,
): List<TaskSeriesPoint> {
    return if (periodMode == StatsPeriodMode.MONTH) {
        points.sortedBy { it.recordDate }.map { point ->
            point.toTaskSeriesPoint(valueType)
        }
    } else {
        points
            .groupBy { YearMonth.from(it.recordDate) }
            .toSortedMap()
            .map { (month, monthPoints) ->
                TaskSeriesPoint(
                    date = month.atDay(1),
                    value = when (valueType) {
                        "EXERCISE" -> monthPoints.sumOf { it.totalNumber ?: 0.0 }
                        else -> monthPoints.sumOf { it.totalNumber ?: it.completedCount.toDouble() }
                    },
                    durationMinutes = monthPoints.sumOf { it.totalDuration ?: 0 },
                    completedCount = monthPoints.sumOf { it.completedCount },
                )
            }
    }
}

private fun com.habittracker.data.local.model.DailyTaskStatRow.toTaskSeriesPoint(valueType: String): TaskSeriesPoint {
    return TaskSeriesPoint(
        date = recordDate,
        value = when (valueType) {
            "EXERCISE" -> totalNumber ?: 0.0
            else -> totalNumber ?: completedCount.toDouble()
        },
        durationMinutes = totalDuration,
        completedCount = completedCount,
    )
}
