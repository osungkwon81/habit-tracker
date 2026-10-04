package com.habittracker.data.reminder

import android.content.Context
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

class ReminderRepository(
    private val context: Context,
    private val database: HabitTrackerDatabase,
    private val protector: HabitTrackerDatabaseProtector,
    private val dao: HabitDao,
) {
    fun observeReminders(): Flow<List<ReminderEntity>> = dao.observeReminders()

    suspend fun create(title: String, remindAt: LocalDateTime, repeatIntervalMinutes: Int) {
        val safeTitle = title.trim()
        require(safeTitle.isNotEmpty()) { "리마인더 내용을 입력해 주세요." }
        require(remindAt.isAfter(LocalDateTime.now())) { "알림 시간은 현재 이후로 선택해 주세요." }
        require(repeatIntervalMinutes > 0) { "재알림 간격은 1분 이상이어야 합니다." }

        val reminderId = dao.insertReminder(
            ReminderEntity(
                title = safeTitle,
                remindAt = remindAt,
                repeatIntervalMinutes = repeatIntervalMinutes,
                createdAt = LocalDateTime.now(),
            ),
        )
        protector.requestBackup(database)
        ReminderScheduler.schedule(context, reminderId, remindAt)
    }

    suspend fun get(reminderId: Long): ReminderEntity? = dao.getReminderById(reminderId)

    suspend fun restoreSchedules() {
        val now = LocalDateTime.now()
        dao.getActiveReminders().forEach { reminder ->
            if (!ReminderNotifier.isVisible(context, reminder.id)) {
                ReminderScheduler.restore(
                    context,
                    reminder.id,
                    reminder.remindAt.takeIf { it.isAfter(now) } ?: now,
                )
            }
        }
    }

    suspend fun complete(reminderId: Long) {
        if (dao.completeReminder(reminderId, LocalDateTime.now()) == 1) {
            protector.requestBackup(database)
        }
        ReminderScheduler.cancel(context, reminderId)
        ReminderNotifier.cancel(context, reminderId)
    }

    suspend fun delete(reminderId: Long) {
        require(dao.deleteReminder(reminderId) == 1) { "삭제할 리마인더 $reminderId 를 찾을 수 없습니다." }
        protector.requestBackup(database)
        ReminderScheduler.cancel(context, reminderId)
        ReminderNotifier.cancel(context, reminderId)
    }
}
