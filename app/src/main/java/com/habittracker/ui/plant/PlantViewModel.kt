package com.habittracker.ui.plant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.PlantEntity
import com.habittracker.data.repository.HabitRepository
import com.habittracker.ui.digitsOnly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 화면 모드를 enum으로 제한하면 문자열 비교와 오타를 제거할 수 있다. */
enum class PlantScreenMode {
    LIST,
    EDITOR,
}

private data class PlantEditorMainState(
    val selectedPlantId: Long?,
    val name: String,
    val imageUri: String?,
    val memo: String,
    val wateringMonths: String,
)

private data class PlantEditorState(
    val main: PlantEditorMainState,
    val wateringDays: String,
    val lastWateredDate: LocalDate,
)

private data class PlantFeedbackState(
    val statusMessage: String?,
    val screenMode: PlantScreenMode,
)

class PlantViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedPlantId = MutableStateFlow<Long?>(null)
    private val name = MutableStateFlow("")
    private val imageUri = MutableStateFlow<String?>(null)
    private val memo = MutableStateFlow("")
    private val wateringMonths = MutableStateFlow("")
    private val wateringDays = MutableStateFlow("")
    private val lastWateredDate = MutableStateFlow(LocalDate.now())
    private val statusMessage = MutableStateFlow<String?>(null)
    private val screenMode = MutableStateFlow(PlantScreenMode.LIST)

    private val editorMainState = combine(
        selectedPlantId,
        name,
        imageUri,
        memo,
        wateringMonths,
    ) { selectedId, name, imageUri, memo, months ->
        PlantEditorMainState(selectedId, name, imageUri, memo, months)
    }

    private val editorState = combine(
        editorMainState,
        wateringDays,
        lastWateredDate,
    ) { main, days, lastWateredDate ->
        PlantEditorState(main, days, lastWateredDate)
    }

    private val feedbackState = combine(statusMessage, screenMode) { message, mode ->
        PlantFeedbackState(message, mode)
    }

    val uiState: StateFlow<PlantUiState> = combine(
        repository.observePlants(),
        editorState,
        feedbackState,
    ) { plants, editor, feedback ->
        val intervalDays = calculateIntervalDays(editor.main.wateringMonths, editor.wateringDays)
        val lastWateredDate = editor.lastWateredDate

        PlantUiState(
            plants = plants,
            duePlants = plants.filter { it.nextWateringDate <= LocalDate.now() },
            selectedPlantId = editor.main.selectedPlantId,
            name = editor.main.name,
            imageUri = editor.main.imageUri,
            memo = editor.main.memo,
            wateringMonths = editor.main.wateringMonths,
            wateringDays = editor.wateringDays,
            lastWateredDate = lastWateredDate,
            nextWateringDate = lastWateredDate.plusDays(intervalDays.toLong()),
            statusMessage = feedback.statusMessage,
            screenMode = feedback.screenMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlantUiState(),
    )

    fun startNewPlant() {
        resetEditor()
        statusMessage.value = null
        screenMode.value = PlantScreenMode.EDITOR
    }

    fun showList() {
        screenMode.value = PlantScreenMode.LIST
        statusMessage.value = null
    }

    fun updateName(value: String) {
        name.value = value
    }

    fun updateMemo(value: String) {
        memo.value = value
    }

    fun updateWateringMonths(value: String) {
        wateringMonths.value = sanitizeNumericInput(value)
    }

    fun updateWateringDays(value: String) {
        wateringDays.value = sanitizeNumericInput(value)
    }

    fun updateLastWateredDate(date: LocalDate) {
        lastWateredDate.value = date
    }

    fun updateImageUri(value: String?) {
        imageUri.value = value
    }

    fun openPlant(plant: PlantEntity) {
        selectedPlantId.value = plant.id
        name.value = plant.name
        imageUri.value = plant.imageUri
        memo.value = plant.memo.orEmpty()
        wateringMonths.value = (plant.wateringIntervalDays / 30).takeIf { it > 0 }?.toString().orEmpty()
        wateringDays.value = (plant.wateringIntervalDays % 30).takeIf { it > 0 }?.toString().orEmpty()
        lastWateredDate.value = plant.lastWateredDate
        statusMessage.value = null
        screenMode.value = PlantScreenMode.EDITOR
    }

    fun savePlant() {
        viewModelScope.launch {
            runCatching {
                repository.savePlant(
                    plantId = selectedPlantId.value,
                    name = name.value,
                    imageUri = imageUri.value,
                    memo = memo.value,
                    wateringMonths = wateringMonths.value.toIntOrNull() ?: 0,
                    wateringDays = wateringDays.value.toIntOrNull() ?: 0,
                    lastWateredDate = lastWateredDate.value,
                )
            }.onSuccess {
                resetEditor()
                screenMode.value = PlantScreenMode.LIST
                statusMessage.value = "화분이 저장되었습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "화분 저장에 실패했습니다."
            }
        }
    }

    fun completeWatering(plantId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.completePlantWatering(plantId)
            }.onSuccess {
                statusMessage.value = "물주기 완료로 처리했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "물주기 완료 처리에 실패했습니다."
            }
        }
    }

    fun increaseWateringIntervalOneDay(plantId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.increasePlantWateringIntervalOneDay(plantId)
            }.onSuccess {
                statusMessage.value = "물주기 주기와 예정일을 하루 늘렸습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "물주기 주기 변경에 실패했습니다."
            }
        }
    }

    fun deletePlant(plantId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.deletePlant(plantId)
            }.onSuccess {
                if (selectedPlantId.value == plantId) {
                    resetEditor()
                    screenMode.value = PlantScreenMode.LIST
                }
                statusMessage.value = "화분을 삭제했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "화분 삭제에 실패했습니다."
            }
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    private fun resetEditor() {
        selectedPlantId.value = null
        name.value = ""
        imageUri.value = null
        memo.value = ""
        wateringMonths.value = ""
        wateringDays.value = ""
        lastWateredDate.value = LocalDate.now()
    }

    private fun calculateIntervalDays(monthsValue: String, daysValue: String): Int {
        val months = monthsValue.toIntOrNull() ?: 0
        val days = daysValue.toIntOrNull() ?: 0
        return ((months * 30) + days).coerceAtLeast(0)
    }

    private fun sanitizeNumericInput(value: String): String {
        val sanitized = value.digitsOnly()
        return sanitized.trimStart('0').ifEmpty {
            if (sanitized.isEmpty()) "" else "0"
        }
    }
}

data class PlantUiState(
    val plants: List<PlantEntity> = emptyList(),
    val duePlants: List<PlantEntity> = emptyList(),
    val selectedPlantId: Long? = null,
    val name: String = "",
    val imageUri: String? = null,
    val memo: String = "",
    val wateringMonths: String = "",
    val wateringDays: String = "",
    val lastWateredDate: LocalDate = LocalDate.now(),
    val nextWateringDate: LocalDate = LocalDate.now(),
    val statusMessage: String? = null,
    val screenMode: PlantScreenMode = PlantScreenMode.LIST,
)
