package com.habittracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.data.repository.HabitRepository
import com.habittracker.data.reminder.ReminderRepository
import com.habittracker.data.local.entity.ReminderEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
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
    reminderRepository: ReminderRepository,
) : ViewModel() {
    private val currentMonth = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow<LocalDate?>(null)
    private val today = MutableStateFlow(LocalDate.now())
    private val reminders = combine(reminderRepository.observeReminders(), today) { reminders, date ->
        HomeReminderState(items = reminders.filter { it.completedAt == null && it.remindAt.toLocalDate() == date }, isLoading = false)
    }.catch { error ->
        if (error is CancellationException) throw error
        android.util.Log.e("HomeViewModel", "오늘 리마인더 조회 실패", error)
        emit(HomeReminderState(isLoading = false, error = "오늘 할 일을 불러오지 못했습니다."))
    }

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
    val uiState: StateFlow<HomeUiState> = combine(monthlyState, selectedDetails, reminders) { month, selected, reminders ->
        val (date, details) = selected
        val visibleDate = date?.takeIf { YearMonth.from(it) == month.currentMonth }
        month.copy(
            selectedDate = visibleDate,
            selectedRecordDetails = if (visibleDate != null) details else emptyList(),
            reminders = reminders.items,
            remindersLoading = reminders.isLoading,
            remindersError = reminders.error,
        )
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

    fun refreshToday() {
        today.value = LocalDate.now()
    }
}

private data class HomeReminderState(
    val items: List<ReminderEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class HomeUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate? = null,
    val summaries: Map<LocalDate, RecordSummaryRow> = emptyMap(),
    val diarySummaries: Map<LocalDate, DiarySummaryRow> = emptyMap(),
    val selectedRecordDetails: List<RecordDetailRow> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val remindersLoading: Boolean = true,
    val remindersError: String? = null,
)
