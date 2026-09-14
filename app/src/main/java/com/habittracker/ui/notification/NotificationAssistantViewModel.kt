package com.habittracker.ui.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.NotificationAssistantItemEntity
import com.habittracker.data.notification.NotificationAssistantRepository
import com.habittracker.data.notification.NotificationAssistantStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class NotificationAssistantUiState(
    val items: List<NotificationAssistantItemEntity> = emptyList(),
    val busyIds: Set<Long> = emptySet(),
    val message: String? = null,
)

class NotificationAssistantViewModel(
    private val repository: NotificationAssistantRepository,
) : ViewModel() {
    private val busyIds = MutableStateFlow<Set<Long>>(emptySet())
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<NotificationAssistantUiState> = combine(
        repository.observeItems(), busyIds, message,
    ) { items, busy, statusMessage ->
        NotificationAssistantUiState(items, busy, statusMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationAssistantUiState())

    fun accept(id: Long) = transition(id, NotificationAssistantStatus.PENDING, NotificationAssistantStatus.ACCEPTED)
    fun ignore(id: Long) = transition(id, NotificationAssistantStatus.PENDING, NotificationAssistantStatus.IGNORED)
    fun complete(id: Long) = transition(id, NotificationAssistantStatus.ACCEPTED, NotificationAssistantStatus.DONE)

    fun delete(id: Long) {
        if (id in busyIds.value) return
        busyIds.value = busyIds.value + id
        viewModelScope.launch {
            try {
                repository.delete(id)
                message.value = "알림 항목을 삭제했습니다."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "알림 항목 $id 삭제에 실패했습니다."
            } finally {
                busyIds.value = busyIds.value - id
            }
        }
    }

    fun updateDate(item: NotificationAssistantItemEntity, date: LocalDate) {
        if (item.id in busyIds.value) return
        busyIds.value = busyIds.value + item.id
        viewModelScope.launch {
            try {
                repository.updateDate(item.id, date.atTime(item.eventAt?.toLocalTime() ?: java.time.LocalTime.MIDNIGHT))
                message.value = "예정일을 변경했습니다."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "알림 항목 ${item.id} 날짜 변경에 실패했습니다."
            } finally {
                busyIds.value = busyIds.value - item.id
            }
        }
    }

    fun clearMessage() { message.value = null }

    private fun transition(id: Long, expected: String, next: String) {
        if (id in busyIds.value) return
        busyIds.value = busyIds.value + id
        viewModelScope.launch {
            try {
                repository.transition(id, expected, next)
                message.value = when (next) {
                    NotificationAssistantStatus.ACCEPTED -> "생활비서에 추가했습니다."
                    NotificationAssistantStatus.DONE -> "완료로 처리했습니다."
                    else -> "제안을 무시했습니다."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message ?: "알림 항목 $id 처리에 실패했습니다."
            } finally {
                busyIds.value = busyIds.value - id
            }
        }
    }
}
