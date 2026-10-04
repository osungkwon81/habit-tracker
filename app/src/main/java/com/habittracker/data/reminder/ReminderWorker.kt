package com.habittracker.data.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.habittracker.HabitTrackerApplication
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime

class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(reminderIdKey, 0L)
        if (reminderId <= 0L) return Result.failure()

        return try {
            val repository = (applicationContext as HabitTrackerApplication)
                .appContainer.awaitReminderRepository()
            val reminder = repository.get(reminderId) ?: return Result.success()
            if (reminder.completedAt != null) return Result.success()
            if (reminder.remindAt.isAfter(LocalDateTime.now())) {
                ReminderScheduler.schedule(applicationContext, reminder.id, reminder.remindAt)
                return Result.success()
            }
            if (!ReminderNotifier.isVisible(applicationContext, reminder.id)) {
                ReminderNotifier.show(applicationContext, reminder)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ReminderWorker", "리마인더 $reminderId 재알림 처리에 실패했습니다.", error)
            Result.retry()
        }
    }

    companion object {
        const val reminderIdKey = "reminder-id"
    }
}
