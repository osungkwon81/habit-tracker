package com.habittracker.data.repository

import androidx.room.withTransaction
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.entity.DailyRecordEntity
import com.habittracker.data.local.entity.DailyRecordItemEntity
import java.time.LocalDate
import java.time.LocalDateTime

internal class DailyRecordStore(
    private val database: HabitTrackerDatabase,
    private val dao: HabitDao,
) {
    fun observeDetails(date: LocalDate) = dao.observeRecordDetails(date)
    fun observeSummaries(start: LocalDate, end: LocalDate) = dao.observeMonthlySummaries(start, end)
    fun observeMonthlyStats(start: LocalDate, end: LocalDate) = dao.observeMonthlyStats(start, end)
    fun observeDailyTaskStats(start: LocalDate, end: LocalDate) = dao.observeDailyTaskStats(start, end)
    suspend fun getRecord(date: LocalDate) = dao.getDailyRecordByDate(date)
    suspend fun getDetails(date: LocalDate) = dao.getRecordDetails(date)

    suspend fun save(date: LocalDate, memo: String?, isHoliday: Boolean, inputs: List<DailyRecordItemInput>) {
        database.withTransaction {
            val existing = dao.getDailyRecordByDate(date)
            val now = LocalDateTime.now()
            val recordId = if (existing == null) {
                dao.insertDailyRecord(DailyRecordEntity(recordDate = date, memo = memo, isHoliday = isHoliday, createdAt = now, updatedAt = now))
            } else {
                dao.updateDailyRecord(existing.copy(memo = memo, isHoliday = isHoliday, updatedAt = now))
                existing.id
            }
            val persistedId = if (recordId > 0L) recordId else dao.getDailyRecordByDate(date)?.id
                ?: throw IllegalStateException("Daily record was not persisted for $date")
            val items = inputs.filter { it.hasMeaningfulValue() }.map { input ->
                DailyRecordItemEntity(
                    dailyRecordId = persistedId,
                    taskItemMasterId = input.taskItemMasterId,
                    numberValue = input.numberValue,
                    booleanValue = input.booleanValue,
                    textValue = input.textValue?.trim()?.takeIf(String::isNotEmpty),
                    durationMinutes = input.durationMinutes,
                    checked = input.checked,
                    note = input.note?.trim()?.takeIf(String::isNotEmpty),
                )
            }
            dao.deleteItemsByRecordId(persistedId)
            if (items.isNotEmpty()) dao.upsertDailyRecordItems(items)
        }
    }
}
