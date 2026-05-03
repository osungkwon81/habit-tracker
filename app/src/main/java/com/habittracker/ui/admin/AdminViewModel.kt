package com.habittracker.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.TaskColorPalette
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
    val supportedValueTypes = repository.managedTaskValueTypes
    val colorOptions: List<String> = TaskColorPalette.presets
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AdminUiState> = combine(repository.observeActiveTaskItems(), message) { taskItems, statusMessage ->
        AdminUiState(
            taskItems = taskItems
                .filter { it.valueType in supportedValueTypes }
                .map { item ->
                    AdminTaskItemUi(
                        item = item,
                        colorHex = repository.getTaskColorHex(item.id, item.name),
                    )
                },
            statusMessage = statusMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AdminUiState(),
    )

    init {
        viewModelScope.launch {
            repository.syncManagedTaskItems()
        }
    }

    fun addTaskItem(name: String, category: String, valueType: ValueType, unit: String, description: String, colorHex: String) {
        viewModelScope.launch {
            runCatching {
                repository.addTaskItem(
                    name = name,
                    category = category,
                    valueType = valueType,
                    unit = unit,
                    description = description,
                    colorHex = colorHex,
                )
            }.onSuccess {
                message.value = "새 항목이 저장되었습니다."
            }.onFailure { error ->
                message.value = error.message ?: "항목 추가에 실패했습니다."
            }
        }
    }

    fun deleteTaskItem(taskItemId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteTaskItem(taskItemId) }
                .onSuccess { message.value = "항목을 삭제했습니다." }
                .onFailure { error -> message.value = error.message ?: "항목 삭제에 실패했습니다." }
        }
    }

    fun getValueTypeLabel(valueType: ValueType): String {
        return when (valueType) {
            ValueType.NUMBER -> "숫자"
            ValueType.EXERCISE -> "걷기 기록"
            ValueType.BOOLEAN -> "체크"
            ValueType.TEXT -> "텍스트"
            ValueType.DURATION -> "시간"
        }
    }
}

data class AdminTaskItemUi(
    val item: TaskItemMasterEntity,
    val colorHex: String,
)

data class AdminUiState(
    val taskItems: List<AdminTaskItemUi> = emptyList(),
    val statusMessage: String? = null,
)
