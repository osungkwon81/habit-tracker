package com.habittracker.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.ValueType
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.repository.DailyRecordItemInput
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

@OptIn(ExperimentalCoroutinesApi::class)
class DailyEntryViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DailyEntryUiState> = selectedDate
        .flatMapLatest { date ->
            combine(repository.observeActiveTaskItems(), statusMessage) { taskItems, message ->
                val existingRecord = repository.getDailyRecord(date)
                val details = repository.getRecordDetails(date)
                DailyEntryUiState(
                    selectedDate = date,
                    hasExistingRecord = existingRecord != null,
                    memo = existingRecord?.memo.orEmpty(),
                    isHoliday = existingRecord?.isHoliday == true,
                    taskItems = mergeTaskItems(taskItems, details),
                    statusMessage = message,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DailyEntryUiState(),
        )

    init {
        viewModelScope.launch {
            repository.syncManagedTaskItems()
        }
    }

    fun loadRecord(rawDate: String) {
        runCatching { LocalDate.parse(rawDate) }
            .onSuccess { parsedDate ->
                selectedDate.value = parsedDate
                statusMessage.value = null
            }
            .onFailure {
                statusMessage.value = "날짜 형식은 YYYY-MM-DD로 입력해 주세요."
            }
    }

    fun saveDailyRecord(recordDate: LocalDate, memo: String, isHoliday: Boolean, items: List<TaskItemInputState>) {
        viewModelScope.launch {
            runCatching {
                repository.saveDailyRecord(
                    recordDate = recordDate,
                    memo = memo.trim().takeIf(String::isNotEmpty),
                    isHoliday = isHoliday,
                    itemInputs = items.map { item ->
                        DailyRecordItemInput(
                            taskItemMasterId = item.taskItemMasterId,
                            numberValue = item.numberValue.toDoubleOrNull(),
                            booleanValue = item.booleanValue,
                            textValue = item.textValue,
                            durationMinutes = item.durationMinutes.toIntOrNull(),
                            checked = item.checked,
                            note = item.note,
                        )
                    },
                )
            }.onSuccess {
                selectedDate.value = recordDate
                statusMessage.value = if (isHoliday) {
                    "$recordDate 기록과 휴일 표시를 저장했습니다."
                } else {
                    "$recordDate 기록을 저장했습니다."
                }
            }.onFailure { error ->
                statusMessage.value = error.message ?: "저장에 실패했습니다."
            }
        }
    }

    private fun mergeTaskItems(taskItems: List<TaskItemMasterEntity>, details: List<RecordDetailRow>): List<TaskItemInputState> {
        val detailMap = details.associateBy(RecordDetailRow::taskItemMasterId)
        return taskItems.map { taskItem ->
            val detail = detailMap[taskItem.id]
            TaskItemInputState(
                taskItemMasterId = taskItem.id,
                name = taskItem.name,
                category = taskItem.category,
                valueType = taskItem.valueType,
                unit = taskItem.unit,
                numberValue = detail?.numberValue?.toInt()?.toString().orEmpty(),
                booleanValue = detail?.booleanValue == true,
                textValue = detail?.textValue.orEmpty(),
                durationMinutes = detail?.durationMinutes?.toString().orEmpty(),
                checked = detail?.checked == true || detail?.booleanValue == true,
                note = detail?.note.orEmpty(),
                description = taskItem.description.orEmpty(),
                hasExistingValue = detail != null,
            )
        }
    }
}

data class DailyEntryUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val hasExistingRecord: Boolean = false,
    val memo: String = "",
    val isHoliday: Boolean = false,
    val taskItems: List<TaskItemInputState> = emptyList(),
    val statusMessage: String? = null,
)

data class TaskItemInputState(
    val taskItemMasterId: Long,
    val name: String,
    val category: String,
    val valueType: ValueType,
    val unit: String?,
    val numberValue: String = "",
    val booleanValue: Boolean = false,
    val textValue: String = "",
    val durationMinutes: String = "",
    val checked: Boolean = false,
    val note: String = "",
    val description: String = "",
    val hasExistingValue: Boolean = false,
)
