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
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.entity.VocabularyWordEntity
import com.habittracker.data.local.model.DiarySearchRow
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.MonthlyStatRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.data.lotto.LottoSeedData
import com.habittracker.data.lotto.LottoGeneratedTicket
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime

class HabitRepository(
    private val database: HabitTrackerDatabase,
    private val habitDao: HabitDao,
) {
    private companion object {
        const val lottoRoundNotePrefix = "ROUND:"
    }

    fun observeLottoDraws(roundNo: Int?, limit: Int): Flow<List<LottoDrawEntity>> =
        habitDao.observeLottoDraws(roundNo, limit)

    fun observeSavedLottoTickets(limit: Int): Flow<List<LottoTicketEntity>> =
        habitDao.observeSavedLottoTickets(limit)

    fun observeMemoNotes(limit: Int): Flow<List<MemoNoteEntity>> =
        habitDao.observeMemoNotes(limit)

    fun observeMemoNotesByQuery(query: String, limit: Int): Flow<List<MemoNoteEntity>> =
        habitDao.observeMemoNotesByQuery(query.trim(), limit)

    fun observeVocabularyWords(limit: Int): Flow<List<VocabularyWordEntity>> =
        habitDao.observeVocabularyWords(limit)

    fun observeVocabularyWordsByQuery(query: String, limit: Int): Flow<List<VocabularyWordEntity>> =
        habitDao.observeVocabularyWordsByQuery(query.trim(), limit)

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

    fun observeDiaryList(limit: Int): Flow<List<DiarySearchRow>> =
        habitDao.observeDiaryList(limit)

    fun observeDiaryListByQuery(query: String, limit: Int): Flow<List<DiarySearchRow>> =
        habitDao.observeDiaryListByQuery(query.trim(), limit)

    suspend fun getMemoNote(memoId: Long): MemoNoteEntity? =
        habitDao.getMemoNoteById(memoId)

    suspend fun getVocabularyWord(wordId: Long): VocabularyWordEntity? =
        habitDao.getVocabularyWordById(wordId)

    suspend fun getAllVocabularyWords(): List<VocabularyWordEntity> =
        habitDao.getAllVocabularyWords()

    suspend fun verifyMemoPassword(memoId: Long, password: String): MemoNoteEntity {
        val memoNote = habitDao.getMemoNoteById(memoId) ?: throw IllegalArgumentException("메모를 찾을 수 없습니다.")
        require(memoNote.isLocked) { "잠금 메모가 아닙니다." }
        require(password.matches(Regex("\\d{4}"))) { "비밀번호는 4자리 숫자로 입력해 주세요." }
        require(memoNote.passwordHash == hashPin(password)) { "비밀번호가 올바르지 않습니다." }
        return memoNote
    }

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

    suspend fun hasSavedLottoBatch(roundNo: Int, sourceLabel: String): Boolean {
        require(roundNo > 0) { "회차 번호가 올바르지 않습니다." }
        return habitDao.getLottoTicketsBySourceAndNote(
            sourceLabel = sourceLabel,
            note = buildLottoRoundNote(roundNo),
        ).isNotEmpty()
    }

    suspend fun deleteLottoTicket(ticketId: Long) {
        habitDao.deleteLottoTicketById(ticketId)
    }

    suspend fun deleteLottoRound(roundNo: Int) {
        require(roundNo > 0) { "삭제할 회차를 확인해 주세요." }
        habitDao.deleteLottoTicketsByNote(buildLottoRoundNote(roundNo))
    }

    suspend fun saveLottoBatch(roundNo: Int, sourceLabel: String, tickets: List<LottoGeneratedTicket>, overwrite: Boolean) {
        require(roundNo > 0) { "저장할 회차를 확인해 주세요." }
        require(tickets.isNotEmpty()) { "저장할 생성 번호가 없습니다." }

        val limitedTickets = tickets.take(5)
        val roundNote = buildLottoRoundNote(roundNo)

        database.withTransaction {
            val existingTickets = habitDao.getLottoTicketsBySourceAndNote(sourceLabel = sourceLabel, note = roundNote)
            require(existingTickets.isEmpty() || overwrite) { "${roundNo}회차 ${sourceLabel} 번호가 이미 저장되어 있습니다." }
            if (existingTickets.isNotEmpty()) {
                habitDao.deleteLottoTicketsBySourceAndNote(sourceLabel = sourceLabel, note = roundNote)
            }
            limitedTickets.forEach { ticket ->
                saveLottoTicket(
                    numbers = ticket.numbers,
                    sourceLabel = sourceLabel,
                    note = roundNote,
                )
            }
        }
    }

    suspend fun saveMemoNote(memoId: Long?, title: String, content: String, isLocked: Boolean, password: String?) {
        val sanitizedTitle = title.trim()
        val sanitizedContent = content.trim()
        require(sanitizedTitle.isNotEmpty() || sanitizedContent.isNotEmpty()) { "제목 또는 내용을 입력해 주세요." }

        val passwordHash = if (isLocked) {
            require(!password.isNullOrBlank()) { "잠금 메모는 4자리 비밀번호를 입력해 주세요." }
            require(password.matches(Regex("\\d{4}"))) { "비밀번호는 4자리 숫자로 입력해 주세요." }
            hashPin(password)
        } else {
            null
        }

        val now = LocalDateTime.now()
        val existingMemo = if (memoId != null) {
            habitDao.getMemoNoteById(memoId)
        } else {
            null
        }
        if (existingMemo == null) {
            habitDao.insertMemoNote(
                MemoNoteEntity(
                    title = sanitizedTitle.ifEmpty { "제목 없음" },
                    content = sanitizedContent,
                    isLocked = isLocked,
                    passwordHash = passwordHash,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            habitDao.updateMemoNote(
                existingMemo.copy(
                    title = sanitizedTitle.ifEmpty { "제목 없음" },
                    content = sanitizedContent,
                    isLocked = isLocked,
                    passwordHash = passwordHash,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun saveVocabularyWord(wordId: Long?, word: String, meaning: String, pronunciation: String?) {
        val sanitizedWord = word.trim()
        val sanitizedMeaning = meaning.trim()
        val sanitizedPronunciation = pronunciation?.trim()?.takeIf(String::isNotEmpty)
        require(sanitizedWord.isNotEmpty()) { "단어를 입력해 주세요." }
        require(sanitizedMeaning.isNotEmpty()) { "뜻을 입력해 주세요." }
        require(habitDao.countDuplicateVocabulary(sanitizedWord, sanitizedMeaning, wordId) == 0) { "이미 같은 단어와 뜻이 등록되어 있습니다." }

        val now = LocalDateTime.now()
        val existingWord = if (wordId != null) habitDao.getVocabularyWordById(wordId) else null
        if (existingWord == null) {
            habitDao.insertVocabularyWord(
                VocabularyWordEntity(
                    word = sanitizedWord,
                    meaning = sanitizedMeaning,
                    pronunciation = sanitizedPronunciation,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            habitDao.updateVocabularyWord(
                existingWord.copy(
                    word = sanitizedWord,
                    meaning = sanitizedMeaning,
                    pronunciation = sanitizedPronunciation,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun deleteVocabularyWord(wordId: Long) {
        val existingWord = habitDao.getVocabularyWordById(wordId) ?: throw IllegalArgumentException("삭제할 단어를 찾을 수 없습니다.")
        habitDao.deleteVocabularyWord(existingWord)
    }

    suspend fun bulkInsertVocabulary(rawInput: String): BulkVocabularyInsertResult {
        val lines = rawInput.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.isNotEmpty()) { "등록할 단어를 입력해 주세요." }

        val now = LocalDateTime.now()
        val existingPairs = habitDao.getAllVocabularyWords()
            .asSequence()
            .map { it.word.trim().lowercase() to it.meaning.trim().lowercase() }
            .toMutableSet()
        val seenPairs = mutableSetOf<Pair<String, String>>()

        var skippedDuplicateCount = 0
        val entries = lines.mapNotNull { line ->
            val parts = line.split('\t', ',', '|').map(String::trim)
            require(parts.size >= 2) { "대량 등록 형식은 단어,뜻,발음 입니다." }
            val word = parts[0]
            val meaning = parts[1]
            val pronunciation = parts.getOrNull(2)?.takeIf(String::isNotBlank)
            require(word.isNotBlank() && meaning.isNotBlank()) { "단어와 뜻은 비워둘 수 없습니다." }
            val pair = word.lowercase() to meaning.lowercase()
            if (pair in existingPairs || !seenPairs.add(pair)) {
                skippedDuplicateCount += 1
                null
            } else {
                VocabularyWordEntity(
                word = word,
                meaning = meaning,
                pronunciation = pronunciation,
                createdAt = now,
                updatedAt = now,
            )
            }
        }

        require(entries.isNotEmpty()) { "이미 등록된 단어만 포함되어 있습니다." }
        val insertResults = habitDao.insertVocabularyWords(entries)
        val insertedCount = insertResults.count { it > 0L }
        val duplicateCount = skippedDuplicateCount + (insertResults.size - insertedCount)
        return BulkVocabularyInsertResult(insertedCount = insertedCount, duplicateCount = duplicateCount)
    }

    suspend fun recordVocabularyExposure(wordId: Long, isCorrect: Boolean) {
        recordVocabularyStudySession(
            records = listOf(VocabularyStudyRecord(wordId = wordId, isCorrect = isCorrect)),
        )
    }

    suspend fun recordVocabularyStudySession(
        records: List<VocabularyStudyRecord>,
        flashcardSeconds: Int = 0,
        testSeconds: Int = 0,
    ) {
        if (records.isEmpty()) return

        val flashcardShares = distributeStudySeconds(records.size, flashcardSeconds)
        val testShares = distributeStudySeconds(records.size, testSeconds)
        val aggregates = linkedMapOf<Long, VocabularyStudyAggregate>()

        records.forEachIndexed { index, record ->
            val aggregate = aggregates.getOrPut(record.wordId) { VocabularyStudyAggregate() }
            when (record.isCorrect) {
                true -> {
                    aggregate.correctCount += 1
                    aggregate.exposureCount += 1
                }
                false -> {
                    aggregate.wrongCount += 1
                    aggregate.exposureCount += 1
                }
                null -> Unit
            }
            aggregate.flashcardStudySeconds += flashcardShares[index]
            aggregate.testStudySeconds += testShares[index]
        }

        val now = LocalDateTime.now()
        database.withTransaction {
            aggregates.forEach { (wordId, aggregate) ->
                val existingWord = habitDao.getVocabularyWordById(wordId) ?: return@forEach
                habitDao.updateVocabularyWord(
                    existingWord.copy(
                        correctCount = existingWord.correctCount + aggregate.correctCount,
                        wrongCount = existingWord.wrongCount + aggregate.wrongCount,
                        exposureCount = existingWord.exposureCount + aggregate.exposureCount,
                        flashcardStudySeconds = existingWord.flashcardStudySeconds + aggregate.flashcardStudySeconds,
                        testStudySeconds = existingWord.testStudySeconds + aggregate.testStudySeconds,
                        updatedAt = now,
                    ),
                )
            }
        }
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
        val normalizedUnit = when (valueType) {
            ValueType.NUMBER -> unit?.trim()?.takeIf(String::isNotEmpty)
            ValueType.EXERCISE -> "km/min"
            else -> null
        }
        habitDao.insertTaskItem(
            TaskItemMasterEntity(
                code = code,
                name = sanitizedName,
                category = sanitizedCategory,
                valueType = valueType,
                unit = normalizedUnit,
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
                unit = when (valueType) {
                    ValueType.NUMBER -> unit?.trim()?.takeIf(String::isNotEmpty)
                    ValueType.EXERCISE -> "km/min"
                    else -> null
                },
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

    private fun distributeStudySeconds(itemCount: Int, totalSeconds: Int): List<Int> {
        if (itemCount <= 0 || totalSeconds <= 0) return List(itemCount.coerceAtLeast(0)) { 0 }
        val baseSeconds = totalSeconds / itemCount
        val remainder = totalSeconds % itemCount
        return List(itemCount) { index ->
            baseSeconds + if (index < remainder) 1 else 0
        }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray()).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun buildLottoRoundNote(roundNo: Int): String = "$lottoRoundNotePrefix$roundNo"
}

data class BulkVocabularyInsertResult(
    val insertedCount: Int,
    val duplicateCount: Int,
)

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

data class VocabularyStudyRecord(
    val wordId: Long,
    val isCorrect: Boolean?,
)

private data class VocabularyStudyAggregate(
    var correctCount: Int = 0,
    var wrongCount: Int = 0,
    var exposureCount: Int = 0,
    var flashcardStudySeconds: Int = 0,
    var testStudySeconds: Int = 0,
)
