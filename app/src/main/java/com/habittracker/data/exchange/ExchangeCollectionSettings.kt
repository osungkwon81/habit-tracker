package com.habittracker.data.exchange

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ExchangeCollectionSettings(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences("exchange_collection", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _intervalMinutes = MutableStateFlow(preferences.getLong("interval_minutes", 0L))
    val intervalMinutes = _intervalMinutes.asStateFlow()

    suspend fun save(intervalMinutes: Long) = mutex.withLock {
        require(isValidInterval(intervalMinutes)) { "자동 수집 주기는 최소 ${minimumIntervalMinutes}분이며 정수로 입력해야 합니다." }
        withContext(Dispatchers.IO) {
            check(preferences.edit().putLong("interval_minutes", intervalMinutes).commit()) {
                "환율 수집 주기 설정을 저장하지 못했습니다."
            }
        }
        _intervalMinutes.value = intervalMinutes
        try {
            schedule(intervalMinutes, ExistingPeriodicWorkPolicy.UPDATE)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("수집 주기는 저장했지만 자동 수집 예약에 실패했습니다. 설정을 다시 적용해 주세요.", error)
        }
    }

    suspend fun restore() = mutex.withLock {
        val interval = intervalMinutes.value
        check(isValidInterval(interval)) { "저장된 환율 수집 주기가 유효하지 않습니다: $interval" }
        schedule(interval, ExistingPeriodicWorkPolicy.UPDATE)
    }

    private suspend fun schedule(interval: Long, policy: ExistingPeriodicWorkPolicy) {
        withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(applicationContext)
            val operation = if (interval == 0L) {
                manager.cancelUniqueWork(workName)
            } else {
                val request = PeriodicWorkRequestBuilder<ExchangeCollectionWorker>(interval, TimeUnit.MINUTES)
                    .setInitialDelay(interval, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(ExchangeCollectionWorker.intervalKey to interval))
                    .build()
                manager.enqueueUniquePeriodicWork(workName, policy, request)
            }
            operation.result.get()
        }
    }

    companion object {
        private const val workName = "exchange-rate-collection"
        val minimumIntervalMinutes = TimeUnit.MILLISECONDS.toMinutes(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)

        fun isValidInterval(minutes: Long): Boolean = minutes == 0L ||
            (minutes >= minimumIntervalMinutes && minutes <= (Long.MAX_VALUE - System.currentTimeMillis()) / 60_000L)
    }
}
