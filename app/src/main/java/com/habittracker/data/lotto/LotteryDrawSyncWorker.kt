package com.habittracker.data.lotto

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.habittracker.HabitTrackerApplication
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

class LotteryDrawSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val product = inputData.getString(productKey)
            ?.let { value -> runCatching { LotteryProduct.valueOf(value) }.getOrNull() }
            ?: return Result.failure(workDataOf(errorKey to "복권 종류를 확인할 수 없습니다."))
        val expectedDrawDate = inputData.getString(expectedDrawDateKey)
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?: LotteryDrawSyncScheduler.expectedDrawDate(product)
        val repository = (applicationContext as HabitTrackerApplication).appContainer.habitRepository

        return try {
            repository.markLotterySyncRunning(product)
            val result = repository.syncOfficialLotteryDraws(product, expectedDrawDate)
            repository.markLotterySyncSuccess(result)
            if (product == LotteryProduct.LOTTO_645) {
                try {
                    repository.getPurchasedLottoTicketResult(result.latestOfficialRound)?.let { ticketResult ->
                        LottoTicketResultNotifier.showIfNeeded(applicationContext, ticketResult)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(logTag, "로또 구매번호 결과 알림 생성 실패: round=${result.latestOfficialRound}", error)
                }
            }
            LotteryDrawSyncScheduler.scheduleNext(applicationContext, product)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.toLotterySyncUserMessage()
            if (runAttemptCount < maxRetryCount) {
                repository.markLotterySyncRetrying(
                    product = product,
                    message = message,
                    nextDelayMinutes = (runAttemptCount + 1) * 30,
                )
                Result.retry()
            } else {
                repository.markLotterySyncFailure(product, expectedDrawDate, message)
                LotteryDrawSyncNotifier.showFinalFailure(applicationContext, product, message)
                LotteryDrawSyncScheduler.scheduleNext(applicationContext, product)
                Result.failure(workDataOf(errorKey to message))
            }
        }
    }

    companion object {
        const val productKey = "lottery-product"
        const val expectedDrawDateKey = "expected-draw-date"
        const val errorKey = "error-message"
        private const val maxRetryCount = 2
        private const val logTag = "LotteryDrawSync"
    }
}
