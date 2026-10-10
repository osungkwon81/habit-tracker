package com.habittracker.data.exchange

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.habittracker.HabitTrackerApplication
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class ExchangeCollectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HabitTrackerApplication).appContainer
        val interval = inputData.getLong(intervalKey, 0L)
        if (interval == 0L || container.exchangeCollectionSettings.intervalMinutes.value != interval) {
            Log.i("ExchangeCollection", "변경된 수집 설정으로 실행 생략: scheduledMinutes=$interval currentMinutes=${container.exchangeCollectionSettings.intervalMinutes.value}")
            return Result.success()
        }
        return try {
            val repository = container.awaitExchangeRepository()
            if (container.exchangeCollectionSettings.intervalMinutes.value != interval) {
                Log.i("ExchangeCollection", "저장소 준비 중 수집 설정 변경으로 실행 생략: scheduledMinutes=$interval currentMinutes=${container.exchangeCollectionSettings.intervalMinutes.value}")
                return Result.success()
            }
            val collectionDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
            if (collectionDate.dayOfWeek == DayOfWeek.SATURDAY || collectionDate.dayOfWeek == DayOfWeek.SUNDAY) {
                Log.i("ExchangeCollection", "주말 자동 수집 생략: date=$collectionDate intervalMinutes=$interval")
                return Result.success()
            }
            repository.refresh()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ExchangeCollection", "신한은행 USD/JPY 자동 수집 실패: intervalMinutes=$interval", error)
            Result.failure()
        }
    }

    companion object { const val intervalKey = "interval-minutes" }
}
