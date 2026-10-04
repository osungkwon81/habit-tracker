package com.habittracker.data.reminder

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    fun schedule(context: Context, reminderId: Long, targetAt: LocalDateTime) {
        enqueue(context, reminderId, targetAt, ExistingWorkPolicy.KEEP)
    }

    fun restore(context: Context, reminderId: Long, targetAt: LocalDateTime) {
        enqueue(context, reminderId, targetAt, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(
        context: Context,
        reminderId: Long,
        targetAt: LocalDateTime,
        policy: ExistingWorkPolicy,
    ) {
        val delayMillis = Duration.between(LocalDateTime.now(), targetAt).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf(ReminderWorker.reminderIdKey to reminderId))
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(tag(reminderId))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName(reminderId), policy, request)
    }

    fun scheduleNext(context: Context, reminderId: Long, intervalMinutes: Int) {
        enqueue(
            context,
            reminderId,
            LocalDateTime.now().plusMinutes(intervalMinutes.toLong()),
            ExistingWorkPolicy.REPLACE,
        )
    }

    fun cancel(context: Context, reminderId: Long) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(tag(reminderId))
    }

    private fun tag(reminderId: Long): String = "reminder-$reminderId"
    private fun uniqueName(reminderId: Long): String = "reminder-schedule-$reminderId"
}
