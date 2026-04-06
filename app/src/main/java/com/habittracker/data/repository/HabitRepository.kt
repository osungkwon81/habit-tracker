package com.habittracker.data.repository

import androidx.room.withTransaction
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.ValueType
import com.habittracker.data.local.entity.DailyDiaryEntity
import com.habittracker.data.local.entity.DailyRecordEntity
import com.habittracker.data.local.entity.DailyRecordItemEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.MonthlyStatRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

class HabitRepository(
    private val database: HabitTrackerDatabase,
    private val habitDao: HabitDao,
) {
    fun observeActiveTaskItems(): Flow<List<TaskItemMasterEntity>> = habitDao.observeActiveTaskItems()

    fun observeMonthlySummaries(startDate: LocalDate, endDate: LocalDate): Flow<List<RecordSummaryRow>> =
        habitDao.observeMonthlySummaries(startDate, endDate)

    fun observeMonthlyDiarySummaries(startDate: LocalDate, endDate: LocalDate): Flow<List<DiarySummaryRow>> =
        habitDao.observeMonthlyDiarySummaries(startDate, endDate)

    fun observeMonthlyStats(startDate: LocalDate, endDate: LocalDate): Flow<List<MonthlyStatRow>> =
        habitDao.observeMonthlyStats(startDate, endDate)

    suspend fun getDailyRecord(recordDate: LocalDate): DailyRecordEntity? =
        habitDao.getDailyRecordByDate(recordDate)

    suspend fun getDiary(recordDate: LocalDate): DailyDiaryEntity? =
        habitDao.getDiaryByDate(recordDate)

    suspend fun getRecordDetails(recordDate: LocalDate): List<RecordDetailRow> =
        habitDao.getRecordDetails(recordDate)

    suspend fun seedDefaultTaskItemsIfEmpty() {
        val defaultTaskItems = listOf(
            TaskItemMasterEntity(code = "PUSH_UP", name = "\uD478\uC2DC\uC5C5", category = "\uC6B4\uB3D9", valueType = ValueType.NUMBER, unit = "\uD68C", description = "\uD478\uC2DC\uC5C5 \uD69F\uC218\uB97C \uAE30\uB85D\uD569\uB2C8\uB2E4.", sortOrder = 10),
            TaskItemMasterEntity(code = "WATER_PLANT", name = "\uD654\uBD84 \uBB3C\uC8FC\uAE30", category = "\uB8E8\uD2F4", valueType = ValueType.BOOLEAN, unit = null, description = "\uD654\uBD84\uC5D0 \uBB3C\uC744 \uC92C\uB294\uC9C0 \uCCB4\uD06C\uD569\uB2C8\uB2E4.", sortOrder = 20),
            TaskItemMasterEntity(code = "DAILY_NOTE", name = "\uD558\uB8E8 \uBA54\uBAA8", category = "\uAE30\uB85D", valueType = ValueType.TEXT, unit = null, description = "\uAC04\uB2E8\uD55C \uD558\uB8E8 \uBA54\uBAA8\uB97C \uB0A8\uAE41\uB2C8\uB2E4.", sortOrder = 30),
        )
        habitDao.insertTaskItems(defaultTaskItems)
    }

    suspend fun addTaskItem(name: String, category: String, valueType: ValueType, unit: String?, description: String?) {
        val sanitizedName = name.trim()
        val sanitizedCategory = category.trim().ifEmpty { "\uAE30\uD0C0" }
        require(sanitizedName.isNotEmpty()) { "\uD56D\uBAA9 \uC774\uB984\uC744 \uC785\uB825\uD574 \uC8FC\uC138\uC694." }
        val codeBase = sanitizedName.uppercase().replace(" ", "_").replace(Regex("[^A-Z0-9_\\uAC00-\\uD7A3]"), "").take(24).ifEmpty { "TASK" }
        val code = "${codeBase}_${System.currentTimeMillis() % 100000}"
        val nextSortOrder = habitDao.getMaxSortOrder() + 10
        habitDao.insertTaskItem(TaskItemMasterEntity(code = code, name = sanitizedName, category = sanitizedCategory, valueType = valueType, unit = unit?.trim()?.takeIf(String::isNotEmpty), description = description?.trim()?.takeIf(String::isNotEmpty), sortOrder = nextSortOrder))
    }

    suspend fun updateTaskItem(taskItemId: Long, name: String, category: String, valueType: ValueType, unit: String?, description: String?) {
        require(name.trim().isNotEmpty()) { "\uD56D\uBAA9 \uC774\uB984\uC744 \uC785\uB825\uD574 \uC8FC\uC138\uC694." }
        val safeCurrentItem = habitDao.getTaskItemById(taskItemId) ?: throw IllegalArgumentException("\uC218\uC815\uD560 \uD56D\uBAA9\uC744 \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.")
        habitDao.updateTaskItem(safeCurrentItem.copy(name = name.trim(), category = category.trim().ifEmpty { "\uAE30\uD0C0" }, valueType = valueType, unit = unit?.trim()?.takeIf(String::isNotEmpty), description = description?.trim()?.takeIf(String::isNotEmpty)))
    }

    suspend fun deleteTaskItem(taskItemId: Long) {
        habitDao.deactivateTaskItem(taskItemId)
    }

    suspend fun saveDiary(diaryDate: LocalDate, title: String, body: String, weather: String, imageUris: List<String>) {
        require(title.isNotBlank() || body.isNotBlank()) { "\uC77C\uAE30 \uC81C\uBAA9 \uB610\uB294 \uB0B4\uC6A9\uC744 \uC785\uB825\uD574 \uC8FC\uC138\uC694." }
        val currentDiary = habitDao.getDiaryByDate(diaryDate)
        habitDao.upsertDiary(
            DailyDiaryEntity(
                id = currentDiary?.id ?: 0L,
                diaryDate = diaryDate,
                title = title.trim().ifEmpty { "\uBB34\uC81C" },
                body = body,
                weather = weather,
                imageUris = imageUris.joinToString("\n"),
                updatedAt = LocalDateTime.now(),
            ),
        )
    }

    suspend fun saveDailyRecord(recordDate: LocalDate, memo: String?, itemInputs: List<DailyRecordItemInput>) {
        database.withTransaction {
            val existingRecord = habitDao.getDailyRecordByDate(recordDate)
            val now = LocalDateTime.now()
            val recordId = if (existingRecord == null) {
                habitDao.insertDailyRecord(DailyRecordEntity(recordDate = recordDate, memo = memo, createdAt = now, updatedAt = now))
            } else {
                habitDao.updateDailyRecord(existingRecord.copy(memo = memo, updatedAt = now))
                existingRecord.id
            }
            val safeRecordId = if (recordId > 0L) recordId else habitDao.getDailyRecordByDate(recordDate)?.id ?: throw IllegalStateException("Daily record was not persisted for $recordDate")
            val sanitizedItems = itemInputs.filter { input -> input.hasMeaningfulValue() }.map { input ->
                DailyRecordItemEntity(dailyRecordId = safeRecordId, taskItemMasterId = input.taskItemMasterId, numberValue = input.numberValue, booleanValue = input.booleanValue, textValue = input.textValue?.trim()?.takeIf(String::isNotEmpty), durationMinutes = input.durationMinutes, checked = input.checked, note = input.note?.trim()?.takeIf(String::isNotEmpty))
            }
            habitDao.deleteItemsByRecordId(safeRecordId)
            if (sanitizedItems.isNotEmpty()) {
                habitDao.upsertDailyRecordItems(sanitizedItems)
            }
        }
    }
}

data class DailyRecordItemInput(
    val taskItemMasterId: Long,
    val numberValue: Double? = null,
    val booleanValue: Boolean? = null,
    val textValue: String? = null,
    val durationMinutes: Int? = null,
    val checked: Boolean = false,
    val note: String? = null,
) {
    fun hasMeaningfulValue(): Boolean {
        return checked || booleanValue == true || (numberValue != null && numberValue > 0) || (durationMinutes != null && durationMinutes > 0) || !textValue.isNullOrBlank() || !note.isNullOrBlank()
    }
}