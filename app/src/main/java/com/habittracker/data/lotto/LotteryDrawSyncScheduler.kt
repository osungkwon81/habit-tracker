package com.habittracker.data.lotto

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

object LotteryDrawSyncScheduler {
    private val koreaZone = ZoneId.of("Asia/Seoul")

    fun scheduleAll(context: Context) {
        val now = ZonedDateTime.now(koreaZone)
        LotteryProduct.entries.forEach { product ->
            scheduleNext(context, product, now)
            enqueueCatchUpIfDue(context, product, now)
        }
    }

    fun scheduleNext(
        context: Context,
        product: LotteryProduct,
        now: ZonedDateTime = ZonedDateTime.now(koreaZone),
    ) {
        val target = nextScheduledAt(product, now)
        enqueue(
            context = context,
            product = product,
            expectedDrawDate = target.toLocalDate(),
            initialDelayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0L),
            uniqueName = "lottery-scheduled-${product.name}-${target.toLocalDate()}",
        )
    }

    fun expectedDrawDate(
        product: LotteryProduct,
        now: ZonedDateTime = ZonedDateTime.now(koreaZone),
    ): LocalDate {
        var candidateDate = now.toLocalDate().with(TemporalAdjusters.previousOrSame(product.drawDay))
        val candidateTime = candidateDate.atTime(product.scheduledSyncTime).atZone(koreaZone)
        if (candidateTime.isAfter(now)) candidateDate = candidateDate.minusWeeks(1)
        return candidateDate
    }

    private fun enqueueCatchUpIfDue(
        context: Context,
        product: LotteryProduct,
        now: ZonedDateTime,
    ) {
        val expectedDate = expectedDrawDate(product, now)
        val lastSuccessDate = LotteryDrawSyncStore.readLastSuccessDrawDate(context, product)
        val lastFailedDate = LotteryDrawSyncStore.readLastFailedDrawDate(context, product)
        if (lastSuccessDate != null && !lastSuccessDate.isBefore(expectedDate)) return
        if (lastFailedDate == expectedDate) return
        enqueue(
            context = context,
            product = product,
            expectedDrawDate = expectedDate,
            initialDelayMillis = 0L,
            uniqueName = "lottery-catch-up-${product.name}-$expectedDate",
        )
    }

    private fun enqueue(
        context: Context,
        product: LotteryProduct,
        expectedDrawDate: LocalDate,
        initialDelayMillis: Long,
        uniqueName: String,
    ) {
        val request = OneTimeWorkRequestBuilder<LotteryDrawSyncWorker>()
            .setInputData(
                workDataOf(
                    LotteryDrawSyncWorker.productKey to product.name,
                    LotteryDrawSyncWorker.expectedDrawDateKey to expectedDrawDate.toString(),
                ),
            )
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    private fun nextScheduledAt(product: LotteryProduct, now: ZonedDateTime): ZonedDateTime {
        var targetDate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(product.drawDay))
        var target = targetDate.atTime(product.scheduledSyncTime).atZone(koreaZone)
        if (!target.isAfter(now)) {
            targetDate = targetDate.plusWeeks(1)
            target = targetDate.atTime(product.scheduledSyncTime).atZone(koreaZone)
        }
        return target
    }
}
