package com.habittracker.data.notification

import androidx.room.withTransaction
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.local.entity.NotificationAssistantItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

class NotificationAssistantRepository(
    private val database: HabitTrackerDatabase,
    private val protector: HabitTrackerDatabaseProtector,
    private val dao: HabitDao,
) {
    fun observeItems(): Flow<List<NotificationAssistantItemEntity>> = dao.observeNotificationAssistantItems()

    suspend fun saveCandidate(item: NotificationAssistantItemEntity) {
        val changed = database.withTransaction {
            if (dao.insertNotificationAssistantItem(item) != -1L) {
                true
            } else if (item.category in setOf(
                    NotificationAssistantCategory.PAYMENT,
                    NotificationAssistantCategory.ORDER,
                    NotificationAssistantCategory.DELIVERY,
                )) {
                val existing = dao.getNotificationAssistantItemBySourceKey(item.sourceKey)
                    ?: return@withTransaction false
                if (existing.title == item.title && existing.deliveryState == item.deliveryState &&
                    existing.amount == item.amount && existing.productName == item.productName &&
                    existing.merchant == item.merchant && existing.referenceId == item.referenceId
                ) return@withTransaction false
                dao.updateNotificationAssistantItem(item.copy(id = existing.id, status = existing.status))
                true
            } else false
        }
        if (changed) protector.requestBackup(database)
    }

    suspend fun transition(id: Long, expectedStatus: String, nextStatus: String) {
        require(dao.transitionNotificationAssistantItem(id, expectedStatus, nextStatus) == 1) {
            "알림 항목 $id 상태를 $expectedStatus 에서 $nextStatus 로 변경할 수 없습니다."
        }
        protector.requestBackup(database)
    }

    suspend fun delete(id: Long) {
        require(dao.deleteNotificationAssistantItem(id) == 1) { "삭제할 알림 항목 $id 를 찾을 수 없습니다." }
        protector.requestBackup(database)
    }

    suspend fun updateDate(id: Long, eventAt: LocalDateTime) {
        require(dao.updateNotificationAssistantDate(id, eventAt) == 1) { "알림 항목 $id 날짜를 변경할 수 없습니다." }
        protector.requestBackup(database)
    }
}
