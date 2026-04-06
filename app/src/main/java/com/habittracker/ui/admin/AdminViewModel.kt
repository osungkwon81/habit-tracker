package com.habittracker.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.ValueType
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AdminViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AdminUiState> = combine(repository.observeActiveTaskItems(), message) { taskItems, statusMessage ->
        AdminUiState(taskItems = taskItems, statusMessage = statusMessage)
    }.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = AdminUiState())

    fun addTaskItem(name: String, category: String, valueType: ValueType, unit: String, description: String) {
        viewModelScope.launch {
            runCatching { repository.addTaskItem(name = name, category = category, valueType = valueType, unit = unit, description = description) }
                .onSuccess { message.value = "\uC0C8 \uD56D\uBAA9\uC744 \uCD94\uAC00\uD588\uC2B5\uB2C8\uB2E4." }
                .onFailure { error -> message.value = error.message ?: "\uD56D\uBAA9 \uCD94\uAC00\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4." }
        }
    }

    fun updateTaskItem(taskItemId: Long, name: String, category: String, valueType: ValueType, unit: String, description: String) {
        viewModelScope.launch {
            runCatching { repository.updateTaskItem(taskItemId = taskItemId, name = name, category = category, valueType = valueType, unit = unit, description = description) }
                .onSuccess { message.value = "\uD56D\uBAA9\uC744 \uC218\uC815\uD588\uC2B5\uB2C8\uB2E4." }
                .onFailure { error -> message.value = error.message ?: "\uD56D\uBAA9 \uC218\uC815\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4." }
        }
    }

    fun deleteTaskItem(taskItemId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteTaskItem(taskItemId) }
                .onSuccess { message.value = "\uD56D\uBAA9\uC744 \uC0AD\uC81C\uD588\uC2B5\uB2C8\uB2E4." }
                .onFailure { error -> message.value = error.message ?: "\uD56D\uBAA9 \uC0AD\uC81C\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4." }
        }
    }

    fun getValueTypeLabel(valueType: ValueType): String {
        return when (valueType) {
            ValueType.NUMBER -> "\uC22B\uC790"
            ValueType.BOOLEAN -> "\uCCB4\uD06C"
            ValueType.TEXT -> "\uD14D\uC2A4\uD2B8"
            ValueType.DURATION -> "\uC2DC\uAC04"
        }
    }
}

data class AdminUiState(
    val taskItems: List<TaskItemMasterEntity> = emptyList(),
    val statusMessage: String? = null,
)