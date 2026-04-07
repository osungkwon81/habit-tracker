package com.habittracker.data.repository

import androidx.room.withTransaction
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.ValueType
import com.habittracker.data.local.entity.DailyDiaryEntity
import com.habittracker.data.local.entity.DailyRecordEntity
import com.habittracker.data.local.entity.DailyRecordItemEntity
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.model.DiarySearchRow
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.MonthlyStatRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.data.lotto.LottoSeedData
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

class HabitRepository(
    private val database: HabitTrackerDatabase,
    private val habitDao: HabitDao,
) {
    fun observeLottoDraws(roundNo: Int?, limit: Int): Flow<List<LottoDrawEntity>> =
        habitDao.observeLottoDraws(roundNo, limit)

    fun observeSavedLottoTickets(limit: Int): Flow<List<LottoTicketEntity>> =
        habitDao.observeSavedLottoTickets(limit)

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

    suspend fun searchDiaries(query: String, limit: Int = 20): List<DiarySearchRow> =
        habitDao.searchDiaries(query.trim(), limit)

    suspend fun getRecordDetails(recordDate: LocalDate): List<RecordDetailRow> =
        habitDao.getRecordDetails(recordDate)

    suspend fun getLatestLottoRoundNo(): Int? =
        habitDao.getLatestLottoRoundNo()

    suspend fun getAllLottoHistory(): List<List<Int>> =
        habitDao.getAllLottoDrawsDesc().map(LottoDrawEntity::numbers)

    suspend fun seedLottoDrawsIfEmpty() {
        if (habitDao.getLottoDrawCount() > 0) return
        habitDao.insertLottoDraws(
            LottoSeedData.draws.map { seed ->
                LottoDrawEntity.from(roundNo = seed.roundNo, numbers = seed.numbers.sorted())
            },
        )
    }

    suspend fun saveLottoDraw(roundNo: Int?, numbers: List<Int>): Int {
        require(roundNo != null && roundNo > 0) { "회차 번호를 입력해 주세요." }
        require(numbers.size == 6) { "번호 6개를 모두 입력해 주세요." }

        val sanitizedNumbers = numbers.map { number ->
            require(number in 1..45) { "번호는 1부터 45 사이여야 합니다." }
            number
        }.sorted()

        require(sanitizedNumbers.distinct().size == 6) { "번호는 중복 없이 입력해 주세요." }

        habitDao.upsertLottoDraw(LottoDrawEntity.from(roundNo = roundNo, numbers = sanitizedNumbers))
        return roundNo
    }

    suspend fun saveLottoTicket(numbers: List<Int>, sourceLabel: String, note: String? = null) {
        require(numbers.size == 6) { "저장할 번호는 6개여야 합니다." }
        val sanitizedNumbers = numbers.map { number ->
            require(number in 1..45) { "번호는 1부터 45 사이여야 합니다." }
            number
        }.sorted()
        require(sanitizedNumbers.distinct().size == 6) { "번호는 중복 없이 저장해 주세요." }
        habitDao.insertLottoTicket(
            LottoTicketEntity.from(
                sourceLabel = sourceLabel,
                numbers = sanitizedNumbers,
                note = note?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    suspend fun seedDefaultTaskItemsIfEmpty() {
        val defaultTaskItems = listOf(
            TaskItemMasterEntity(code = "PUSH_UP", name = "푸시업", category = "운동", valueType = ValueType.NUMBER, unit = "회", description = "푸시업 횟수를 기록합니다.", sortOrder = 10),
            TaskItemMasterEntity(code = "WATER_PLANT", name = "화분 물주기", category = "루틴", valueType = ValueType.BOOLEAN, unit = null, description = "화분에 물을 줬는지 체크합니다.", sortOrder = 20),
            TaskItemMasterEntity(code = "DAILY_NOTE", name = "하루 메모", category = "기록", valueType = ValueType.TEXT, unit = null, description = "간단한 하루 메모를 남깁니다.", sortOrder = 30),
        )
        habitDao.insertTaskItems(defaultTaskItems)
    }

    suspend fun addTaskItem(name: String, category: String, valueType: ValueType, unit: String?, description: String?) {
        val sanitizedName = name.trim()
        val sanitizedCategory = category.trim().ifEmpty { "기타" }
        require(sanitizedName.isNotEmpty()) { "항목 이름을 입력해 주세요." }
        val codeBase = sanitizedName.uppercase().replace(" ", "_").replace(Regex("[^A-Z0-9_가-힣]"), "").take(24).ifEmpty { "TASK" }
        val code = "${codeBase}_${System.currentTimeMillis() % 100000}"
        val nextSortOrder = habitDao.getMaxSortOrder() + 10
        habitDao.insertTaskItem(
            TaskItemMasterEntity(
                code = code,
                name = sanitizedName,
                category = sanitizedCategory,
                valueType = valueType,
                unit = unit?.trim()?.takeIf(String::isNotEmpty),
                description = description?.trim()?.takeIf(String::isNotEmpty),
                sortOrder = nextSortOrder,
            ),
        )
    }

    suspend fun updateTaskItem(taskItemId: Long, name: String, category: String, valueType: ValueType, unit: String?, description: String?) {
        require(name.trim().isNotEmpty()) { "항목 이름을 입력해 주세요." }
        val safeCurrentItem = habitDao.getTaskItemById(taskItemId) ?: throw IllegalArgumentException("수정할 항목을 찾지 못했습니다.")
        habitDao.updateTaskItem(
            safeCurrentItem.copy(
                name = name.trim(),
                category = category.trim().ifEmpty { "기타" },
                valueType = valueType,
                unit = unit?.trim()?.takeIf(String::isNotEmpty),
                description = description?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    suspend fun deleteTaskItem(taskItemId: Long) {
        habitDao.deactivateTaskItem(taskItemId)
    }

    suspend fun saveDiary(diaryDate: LocalDate, title: String, body: String, weather: String, imageUris: List<String>) {
        require(title.isNotBlank() || body.isNotBlank()) { "일기 제목 또는 내용을 입력해 주세요." }
        val currentDiary = habitDao.getDiaryByDate(diaryDate)
        habitDao.upsertDiary(
            DailyDiaryEntity(
                id = currentDiary?.id ?: 0L,
                diaryDate = diaryDate,
                title = title.trim().ifEmpty { "무제" },
                body = body,
                weather = weather,
                imageUris = imageUris.joinToString("\n"),
                updatedAt = LocalDateTime.now(),
            ),
        )
    }

    suspend fun saveDailyRecord(recordDate: LocalDate, memo: String?, isHoliday: Boolean, itemInputs: List<DailyRecordItemInput>) {
        database.withTransaction {
            val existingRecord = habitDao.getDailyRecordByDate(recordDate)
            val now = LocalDateTime.now()
            val recordId = if (existingRecord == null) {
                habitDao.insertDailyRecord(
                    DailyRecordEntity(
                        recordDate = recordDate,
                        memo = memo,
                        isHoliday = isHoliday,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                habitDao.updateDailyRecord(existingRecord.copy(memo = memo, isHoliday = isHoliday, updatedAt = now))
                existingRecord.id
            }
            val safeRecordId = if (recordId > 0L) recordId else habitDao.getDailyRecordByDate(recordDate)?.id ?: throw IllegalStateException("Daily record was not persisted for $recordDate")
            val sanitizedItems = itemInputs.filter { input -> input.hasMeaningfulValue() }.map { input ->
                DailyRecordItemEntity(
                    dailyRecordId = safeRecordId,
                    taskItemMasterId = input.taskItemMasterId,
                    numberValue = input.numberValue,
                    booleanValue = input.booleanValue,
                    textValue = input.textValue?.trim()?.takeIf(String::isNotEmpty),
                    durationMinutes = input.durationMinutes,
                    checked = input.checked,
                    note = input.note?.trim()?.takeIf(String::isNotEmpty),
                )
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