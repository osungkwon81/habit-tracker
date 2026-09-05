package com.habittracker.data.lotto

import org.json.JSONException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

enum class LotteryProduct(
    val label: String,
    val drawDay: DayOfWeek,
    val scheduledSyncTime: LocalTime,
) {
    LOTTO_645("로또 6/45", DayOfWeek.SATURDAY, LocalTime.of(21, 5)),
    PENSION_720("연금복권 720+", DayOfWeek.THURSDAY, LocalTime.of(19, 35)),
}

enum class LotterySyncState {
    IDLE,
    RUNNING,
    RETRYING,
    SUCCESS,
    FAILED,
}

data class LotterySyncStatus(
    val product: LotteryProduct,
    val state: LotterySyncState = LotterySyncState.IDLE,
    val message: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAt: LocalDateTime? = null,
    val lastSuccessAt: LocalDateTime? = null,
    val lastSuccessRound: Int? = null,
    val lastSuccessDrawDate: LocalDate? = null,
    val lastFailedDrawDate: LocalDate? = null,
)

data class LotteryOfficialSyncResult(
    val product: LotteryProduct,
    val savedCount: Int,
    val latestOfficialRound: Int,
    val latestOfficialDrawDate: LocalDate,
)

data class OfficialLottoDraw(
    val roundNo: Int,
    val drawDate: LocalDate,
    val numbers: List<Int>,
    val bonusNumber: Int,
    val sourceReference: String,
    val sourceContentHash: String,
)

data class OfficialPensionLotteryDraw(
    val roundNo: Int,
    val drawDate: LocalDate,
    val groupNo: Int,
    val winningNumber: String,
    val bonusNumber: String,
)

data class OfficialLottoDrawBatch(
    val draws: List<OfficialLottoDraw>,
)

data class LottoPurchasedTicketResult(
    val roundNo: Int,
    val totalTicketCount: Int,
    val physicalQrTicketCount: Int,
    val winningRankCounts: Map<Int, Int>,
    val maximumMatchCount: Int,
)

data class PensionLotteryPurchasedNumberResult(
    val roundNo: Int,
    val winningSetCount: Int,
    val winningRanks: Set<PensionLotteryPrizeRank>,
)

enum class PensionLotteryPrizeRank(val label: String) {
    FIRST("1등"),
    SECOND("2등"),
    THIRD("3등"),
    FOURTH("4등"),
    FIFTH("5등"),
    SIXTH("6등"),
    SEVENTH("7등"),
    BONUS("보너스"),
}

data class PensionLotteryPrizeHit(
    val rank: PensionLotteryPrizeRank,
    val ticketCount: Int,
)

fun calculatePensionLotteryPrizeHits(
    purchaseNumber: String?,
    winningNumber: String?,
    bonusNumber: String?,
): List<PensionLotteryPrizeHit> {
    if (purchaseNumber == null || winningNumber == null) return emptyList()

    if (purchaseNumber == winningNumber) {
        return listOf(
            PensionLotteryPrizeHit(PensionLotteryPrizeRank.FIRST, ticketCount = 1),
            PensionLotteryPrizeHit(PensionLotteryPrizeRank.SECOND, ticketCount = 4),
        )
    }
    if (purchaseNumber == bonusNumber) {
        return listOf(PensionLotteryPrizeHit(PensionLotteryPrizeRank.BONUS, ticketCount = 5))
    }

    val rank = when (pensionLotteryMatchingSuffixLength(purchaseNumber, winningNumber)) {
        5 -> PensionLotteryPrizeRank.THIRD
        4 -> PensionLotteryPrizeRank.FOURTH
        3 -> PensionLotteryPrizeRank.FIFTH
        2 -> PensionLotteryPrizeRank.SIXTH
        1 -> PensionLotteryPrizeRank.SEVENTH
        else -> null
    }
    return rank?.let { listOf(PensionLotteryPrizeHit(it, ticketCount = 5)) }.orEmpty()
}

fun pensionLotteryMatchingSuffixLength(purchaseNumber: String?, winningNumber: String?): Int {
    if (purchaseNumber == null || winningNumber == null) return 0
    return purchaseNumber
        .reversed()
        .zip(winningNumber.reversed())
        .takeWhile { (purchaseDigit, winningDigit) -> purchaseDigit == winningDigit }
        .size
}

fun Throwable.toLotterySyncUserMessage(): String = when (this) {
    is SocketTimeoutException -> "동행복권 서버 응답 시간이 초과되었습니다."
    is JSONException, is DateTimeParseException -> "동행복권 공식 응답 형식이 변경되었습니다."
    is IOException -> "네트워크 연결을 확인해 주세요. (${message ?: "통신 오류"})"
    else -> message?.take(300)?.ifBlank { null } ?: "공식 당첨번호를 가져오지 못했습니다."
}
