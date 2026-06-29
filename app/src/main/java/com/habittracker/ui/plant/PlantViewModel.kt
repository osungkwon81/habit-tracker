package com.habittracker.ui.plant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.PlantEntity
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val plantListMode = "list"
private const val plantEditorMode = "editor"

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
    private val screenMode = MutableStateFlow(plantListMode)

    val uiState: StateFlow<PlantUiState> = combine(
        repository.observePlants(),
        selectedPlantId,
        name,
        imageUri,
        memo,
        wateringMonths,
        wateringDays,
        lastWateredDate,
        statusMessage,
        screenMode,
    ) { values ->
        val plants = values[0] as List<PlantEntity>
        val selectedId = values[1] as Long?
        val nameValue = values[2] as String
        val imageUriValue = values[3] as String?
        val memoValue = values[4] as String
        val monthsValue = values[5] as String
        val daysValue = values[6] as String
        val lastWateredValue = values[7] as LocalDate
        val message = values[8] as String?
        val mode = values[9] as String
        val intervalDays = calculateIntervalDays(monthsValue, daysValue)
        PlantUiState(
            plants = plants,
            duePlants = plants.filter { it.nextWateringDate <= LocalDate.now() },
            selectedPlantId = selectedId,
            name = nameValue,
            imageUri = imageUriValue,
            memo = memoValue,
            wateringMonths = monthsValue,
            wateringDays = daysValue,
            lastWateredDate = lastWateredValue,
            nextWateringDate = lastWateredValue.plusDays(intervalDays.toLong()),
            statusMessage = message,
            screenMode = mode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlantUiState(),
    )

    fun startNewPlant() {
        selectedPlantId.value = null
        name.value = ""
        imageUri.value = null
        memo.value = ""
        wateringMonths.value = ""
        wateringDays.value = ""
        lastWateredDate.value = LocalDate.now()
        statusMessage.value = null
        screenMode.value = plantEditorMode
    }

    fun showList() {
        screenMode.value = plantListMode
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
        screenMode.value = plantEditorMode
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
                selectedPlantId.value = null
                name.value = ""
                imageUri.value = null
                memo.value = ""
                wateringMonths.value = ""
                wateringDays.value = ""
                lastWateredDate.value = LocalDate.now()
                screenMode.value = plantListMode
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

    fun deletePlant(plantId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.deletePlant(plantId)
            }.onSuccess {
                if (selectedPlantId.value == plantId) {
                    selectedPlantId.value = null
                    screenMode.value = plantListMode
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

    private fun calculateIntervalDays(monthsValue: String, daysValue: String): Int {
        val months = monthsValue.toIntOrNull() ?: 0
        val days = daysValue.toIntOrNull() ?: 0
        return ((months * 30) + days).coerceAtLeast(0)
    }

    private fun sanitizeNumericInput(value: String): String {
        val digitsOnly = value.filter(Char::isDigit)
        return digitsOnly.trimStart('0').ifEmpty {
            if (digitsOnly.isEmpty()) "" else "0"
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
    val screenMode: String = plantListMode,
)
