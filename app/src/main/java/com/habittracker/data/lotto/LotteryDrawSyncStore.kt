package com.habittracker.data.lotto

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

class LotteryDrawSyncStore(context: Context) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val statuses = MutableStateFlow(
        LotteryProduct.entries.associateWith(::readStatus),
    )

    fun observe(product: LotteryProduct): Flow<LotterySyncStatus> =
        statuses.map { values -> values.getValue(product) }.distinctUntilChanged()

    fun markRunning(product: LotteryProduct) {
        val previous = current(product)
        update(
            previous.copy(
                state = LotterySyncState.RUNNING,
                message = "공식 당첨번호를 확인하고 있습니다.",
                attemptCount = if (previous.state == LotterySyncState.RETRYING) {
                    previous.attemptCount + 1
                } else {
                    1
                },
                lastAttemptAt = LocalDateTime.now(),
            ),
        )
    }

    fun markRetrying(product: LotteryProduct, message: String, nextDelayMinutes: Int) {
        update(
            current(product).copy(
                state = LotterySyncState.RETRYING,
                message = "$message · ${nextDelayMinutes}분 후 다시 시도합니다.",
            ),
        )
    }

    fun markSuccess(product: LotteryProduct, result: LotteryOfficialSyncResult) {
        val now = LocalDateTime.now()
        update(
            current(product).copy(
                state = LotterySyncState.SUCCESS,
                message = if (result.savedCount > 0) {
                    "공식 당첨번호 ${result.savedCount}개 회차를 저장했습니다."
                } else {
                    "${result.latestOfficialRound}회 공식 당첨번호까지 확인했습니다."
                },
                attemptCount = 0,
                lastAttemptAt = now,
                lastSuccessAt = now,
                lastSuccessRound = result.latestOfficialRound,
                lastSuccessDrawDate = result.latestOfficialDrawDate,
                lastFailedDrawDate = null,
            ),
        )
    }

    fun markFailure(product: LotteryProduct, expectedDrawDate: LocalDate, message: String) {
        update(
            current(product).copy(
                state = LotterySyncState.FAILED,
                message = message,
                lastAttemptAt = LocalDateTime.now(),
                lastFailedDrawDate = expectedDrawDate,
            ),
        )
    }

    private fun current(product: LotteryProduct): LotterySyncStatus =
        statuses.value.getValue(product)

    private fun update(status: LotterySyncStatus) {
        val product = status.product
        preferences.edit()
            .putString(key(product, "state"), status.state.name)
            .putString(key(product, "message"), status.message)
            .putInt(key(product, "attempt-count"), status.attemptCount)
            .putString(key(product, "last-attempt-at"), status.lastAttemptAt?.toString())
            .putString(key(product, "last-success-at"), status.lastSuccessAt?.toString())
            .putInt(key(product, "last-success-round"), status.lastSuccessRound ?: -1)
            .putString(key(product, lastSuccessDrawDateSuffix), status.lastSuccessDrawDate?.toString())
            .putString(key(product, lastFailedDrawDateSuffix), status.lastFailedDrawDate?.toString())
            .apply()
        statuses.value = statuses.value + (product to status)
    }

    private fun readStatus(product: LotteryProduct): LotterySyncStatus = LotterySyncStatus(
        product = product,
        state = preferences.getString(key(product, "state"), null)
            ?.let { value -> runCatching { LotterySyncState.valueOf(value) }.getOrNull() }
            ?: LotterySyncState.IDLE,
        message = preferences.getString(key(product, "message"), null),
        attemptCount = preferences.getInt(key(product, "attempt-count"), 0),
        lastAttemptAt = preferences.getString(key(product, "last-attempt-at"), null)
            ?.let { value -> runCatching { LocalDateTime.parse(value) }.getOrNull() },
        lastSuccessAt = preferences.getString(key(product, "last-success-at"), null)
            ?.let { value -> runCatching { LocalDateTime.parse(value) }.getOrNull() },
        lastSuccessRound = preferences.getInt(key(product, "last-success-round"), -1).takeIf { it > 0 },
        lastSuccessDrawDate = readLastSuccessDrawDate(preferences, product),
        lastFailedDrawDate = readDate(preferences, product, lastFailedDrawDateSuffix),
    )

    companion object {
        private const val preferencesName = "lottery-draw-sync-prefs"
        private const val lastSuccessDrawDateSuffix = "last-success-draw-date"
        private const val lastFailedDrawDateSuffix = "last-failed-draw-date"

        private fun key(product: LotteryProduct, suffix: String): String =
            "${product.name.lowercase()}-$suffix"

        fun readLastSuccessDrawDate(context: Context, product: LotteryProduct): LocalDate? =
            readLastSuccessDrawDate(
                context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
                product,
            )

        fun readLastFailedDrawDate(context: Context, product: LotteryProduct): LocalDate? =
            readDate(
                context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
                product,
                lastFailedDrawDateSuffix,
            )

        private fun readLastSuccessDrawDate(
            preferences: android.content.SharedPreferences,
            product: LotteryProduct,
        ): LocalDate? = readDate(preferences, product, lastSuccessDrawDateSuffix)

        private fun readDate(
            preferences: android.content.SharedPreferences,
            product: LotteryProduct,
            suffix: String,
        ): LocalDate? = preferences.getString(key(product, suffix), null)
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
    }
}
