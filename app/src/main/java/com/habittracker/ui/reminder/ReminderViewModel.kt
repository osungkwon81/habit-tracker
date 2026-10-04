package com.habittracker.ui.reminder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.ReminderEntity
import com.habittracker.data.reminder.ReminderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class ReminderUiState(
    val reminders: List<ReminderEntity> = emptyList(),
    val busyIds: Set<Long> = emptySet(),
    val isSaving: Boolean = false,
    val message: String? = null,
    val screenMode: ReminderScreenMode = ReminderScreenMode.LIST,
)

enum class ReminderScreenMode { LIST, EDITOR }

class ReminderViewModel(
    private val repository: ReminderRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val defaultRemindAt = defaultReminderTime()
    val title = savedStateHandle.getStateFlow(titleKey, "")
    val date = savedStateHandle.getStateFlow(dateKey, defaultRemindAt.toLocalDate().toString())
    val time = savedStateHandle.getStateFlow(timeKey, defaultRemindAt.toLocalTime().toString())
    val intervalMinutes = savedStateHandle.getStateFlow(intervalKey, "")
    private val busyIds = MutableStateFlow<Set<Long>>(emptySet())
    private val isSaving = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val screenMode = MutableStateFlow(ReminderScreenMode.LIST)

    val uiState: StateFlow<ReminderUiState> = combine(
        repository.observeReminders(), busyIds, isSaving, message, screenMode,
    ) { reminders, busy, saving, statusMessage, mode ->
        ReminderUiState(reminders, busy, saving, statusMessage, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderUiState())

    fun startEditor() {
        resetEditor()
        screenMode.value = ReminderScreenMode.EDITOR
    }

    fun closeEditor() {
        resetEditor()
        screenMode.value = ReminderScreenMode.LIST
    }

    fun updateTitle(value: String) { savedStateHandle[titleKey] = value }
    fun updateDate(value: LocalDate) { savedStateHandle[dateKey] = value.toString() }
    fun updateTime(hour: Int, minute: Int) {
        savedStateHandle[timeKey] = LocalTime.of(hour, minute).toString()
    }
    fun updateIntervalMinutes(value: String) {
        if (value.all(Char::isDigit)) savedStateHandle[intervalKey] = value
    }

    fun save() {
        if (isSaving.value) return
        viewModelScope.launch {
            isSaving.value = true
            try {
                val remindAt = LocalDateTime.of(LocalDate.parse(date.value), LocalTime.parse(time.value))
                repository.create(title.value, remindAt, intervalMinutes.value.toIntOrNull() ?: 0)
                val nextDefault = defaultReminderTime()
                savedStateHandle[titleKey] = ""
                savedStateHandle[dateKey] = nextDefault.toLocalDate().toString()
                savedStateHandle[timeKey] = nextDefault.toLocalTime().toString()
                savedStateHandle[intervalKey] = ""
                screenMode.value = ReminderScreenMode.LIST
                message.value = "리마인더를 저장했습니다."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "리마인더 저장에 실패했습니다."
            } finally {
                isSaving.value = false
            }
        }
    }

    fun complete(reminderId: Long) = change(reminderId, "리마인더를 완료했습니다.") {
        repository.complete(reminderId)
    }

    fun delete(reminderId: Long) = change(reminderId, "리마인더를 삭제했습니다.") {
        repository.delete(reminderId)
    }

    fun restoreSchedules() {
        viewModelScope.launch {
            try {
                repository.restoreSchedules()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "리마인더 알림 예약을 복구하지 못했습니다."
            }
        }
    }

    fun clearMessage() { message.value = null }

    private fun resetEditor() {
        val nextDefault = defaultReminderTime()
        savedStateHandle[titleKey] = ""
        savedStateHandle[dateKey] = nextDefault.toLocalDate().toString()
        savedStateHandle[timeKey] = nextDefault.toLocalTime().toString()
        savedStateHandle[intervalKey] = ""
    }

    private fun change(reminderId: Long, successMessage: String, block: suspend () -> Unit) {
        if (reminderId in busyIds.value) return
        busyIds.value = busyIds.value + reminderId
        viewModelScope.launch {
            try {
                block()
                message.value = successMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "리마인더 $reminderId 처리에 실패했습니다."
            } finally {
                busyIds.value = busyIds.value - reminderId
            }
        }
    }

    private companion object {
        const val titleKey = "reminder-title"
        const val dateKey = "reminder-date"
        const val timeKey = "reminder-time"
        const val intervalKey = "reminder-interval-minutes"

        fun defaultReminderTime(): LocalDateTime =
            LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0)
    }
}
