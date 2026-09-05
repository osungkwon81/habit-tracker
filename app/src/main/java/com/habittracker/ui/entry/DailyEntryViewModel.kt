package com.habittracker.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DailyEntryViewModel(
    private val repository: HabitRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val selectedDate = MutableStateFlow(savedState.get<String>("recordDate")?.let(LocalDate::parse) ?: LocalDate.now())
    private val statusMessage = MutableStateFlow<String?>(null)
    private val isSaving = MutableStateFlow(false)

    // 선택 날짜가 바뀔 때 기존 수집을 취소하고 해당 날짜의 기록과 활성 항목을 다시 조합한다.
    private val recordState = selectedDate
        .flatMapLatest { date ->
            combine(repository.observeActiveTaskItems(), repository.observeRecordDetails(date)) { taskItems, details ->
                val existingRecord = repository.getDailyRecord(date)
                DailyEntryUiState(
                    selectedDate = date,
                    hasExistingRecord = existingRecord != null,
                    memo = existingRecord?.memo.orEmpty(),
                    isHoliday = existingRecord?.isHoliday == true,
                    taskItems = mergeTaskItems(taskItems, details),
                    isLoaded = true,
                )
            }
        }
    val uiState: StateFlow<DailyEntryUiState> = combine(recordState, statusMessage, isSaving) { record, message, saving ->
        record.copy(statusMessage = message, isSaving = saving)
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

    fun initializeRecord(rawDate: String) {
        if (!savedState.contains("recordDate")) loadRecord(rawDate)
    }

    fun loadRecord(rawDate: String) {
        runCatching { LocalDate.parse(rawDate) }
            .onSuccess { parsedDate ->
                selectedDate.value = parsedDate
                savedState["recordDate"] = parsedDate.toString()
                statusMessage.value = null
            }
            .onFailure {
                statusMessage.value = "날짜 형식은 YYYY-MM-DD로 입력해 주세요."
            }
    }

    fun saveDailyRecord(recordDate: LocalDate, memo: String, isHoliday: Boolean, items: List<TaskItemInputState>) {
        if (isSaving.value) return
        isSaving.value = true
        viewModelScope.launch {
            runCatching {
                items.forEach { item ->
                    require(item.numberValue.isBlank() || item.numberValue.toDoubleOrNull() != null) { "${item.name}: 숫자를 확인해 주세요." }
                    require(item.durationMinutes.isBlank() || item.durationMinutes.toIntOrNull() != null) { "${item.name}: 시간은 분 단위 정수로 입력해 주세요." }
                }
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
                    "$recordDate 기록과 휴일 표시가 저장되었습니다."
                } else {
                    "$recordDate 기록이 저장되었습니다."
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                statusMessage.value = error.message ?: "저장에 실패했습니다."
            }
            isSaving.value = false
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    private fun mergeTaskItems(taskItems: List<TaskItemMasterEntity>, details: List<RecordDetailRow>): List<TaskItemInputState> {
        // associateBy를 사용하면 각 항목마다 details 전체를 반복 검색하지 않고 ID로 바로 찾을 수 있다.
        val detailMap = details.associateBy(RecordDetailRow::taskItemMasterId)
        return taskItems.map { taskItem ->
            val detail = detailMap[taskItem.id]
            TaskItemInputState(
                taskItemMasterId = taskItem.id,
                name = taskItem.name,
                category = taskItem.category,
                valueType = taskItem.valueType,
                unit = taskItem.unit,
                numberValue = detail?.numberValue?.let { value ->
                    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
                }.orEmpty(),
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
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
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
