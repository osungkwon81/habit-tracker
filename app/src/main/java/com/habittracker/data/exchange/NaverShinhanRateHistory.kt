package com.habittracker.data.exchange

import android.util.Log
import com.habittracker.data.local.entity.ExchangeRateEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

internal class NaverShinhanRateHistory(private val client: OkHttpClient) {
    suspend fun fetchMissing(currency: ExchangeCurrency, existing: List<ExchangeRateEntity>): List<ExchangeRateEntity> = withContext(Dispatchers.IO) {
        val start = existing.first().quotedAt
        val end = existing.last().quotedAt
        val known = existing.associateBy { it.quotedAt.toLocalDate() to it.quoteRound }
        fetchRange(currency, start.toLocalDate(), end.toLocalDate()).filter { row ->
            if (row.quotedAt.isBefore(start) || row.quotedAt.isAfter(end)) return@filter false
            val previous = known[row.quotedAt.toLocalDate() to row.quoteRound]
            if (previous != null) {
                check(previous.quotedAt == row.quotedAt && previous.rate.toBigDecimal().compareTo(row.rate.toBigDecimal()) == 0) {
                    "기존 신한은행 고시와 일치하지 않습니다: currency=${currency.name} date=${row.quotedAt.toLocalDate()} round=${row.quoteRound}"
                }
                return@filter false
            }
            val isAfterStart = row.quotedAt.isAfter(start) || (row.quotedAt == start && row.quoteRound > existing.first().quoteRound)
            val isBeforeEnd = row.quotedAt.isBefore(end) || (row.quotedAt == end && row.quoteRound < existing.last().quoteRound)
            isAfterStart && isBeforeEnd
        }
    }

    suspend fun fetchRange(currency: ExchangeCurrency, start: LocalDate, end: LocalDate): List<ExchangeRateEntity> = withContext(Dispatchers.IO) {
        require(!end.isBefore(start)) { "환율 수집 구간이 유효하지 않습니다: currency=${currency.name} start=$start end=$end" }
        val dates = availableDates(currency, start, end)
        val collected = mutableListOf<ExchangeRateEntity>()
        for (date in dates) {
            coroutineContext.ensureActive()
            try {
                val url = "$baseUrl/${currency.name}/charts/round".toHttpUrl().newBuilder()
                    .addQueryParameter("bankType", "shinhan").addQueryParameter("date", date.toString()).build()
                val json = read(url.toString(), "currency=${currency.name} date=$date stage=고시 조회")
                check(json.getString("tradeBaseAt") == date.format(DateTimeFormatter.BASIC_ISO_DATE)) { "요청 날짜와 응답 날짜가 다릅니다." }
                val comparisonDate = LocalDate.parse(json.getString("lastTradeBaseAt"), DateTimeFormatter.BASIC_ISO_DATE)
                check(comparisonDate.isBefore(date)) { "전일 비교 기준 날짜가 유효하지 않습니다: comparisonDate=$comparisonDate" }
                val points = json.getJSONArray("priceInfos")
                check(points.length() > 0) { "일별 고시는 있지만 회차별 자료가 없습니다." }
                var previousTime: LocalDateTime? = null
                var previousRound = 0
                var skippedComparisonCount = 0
                var requestedDateCount = 0
                for (index in 0 until points.length()) {
                    val point = points.getJSONObject(index)
                    val quotedAt = LocalDateTime.parse(point.getString("announcedAt"), quoteFormat)
                    // 네이버 차트에는 lastTradeBaseAt 날짜의 비교용 고시도 함께 포함된다.
                    if (quotedAt.toLocalDate() == comparisonDate) {
                        skippedComparisonCount++
                        continue
                    }
                    check(quotedAt.toLocalDate() == date) { "요청 날짜 이외의 고시가 포함되어 있습니다: quotedAt=$quotedAt" }
                    requestedDateCount++
                    val round = point.getString("round").toInt()
                    val rate = point.getString("currentPrice").toBigDecimal()
                    check(round > previousRound && (previousTime == null || !quotedAt.isBefore(previousTime))) {
                        "고시 회차·시각 순서가 유효하지 않습니다: round=$round quotedAt=$quotedAt previousRound=$previousRound previousTime=$previousTime"
                    }
                    check(rate.signum() > 0 && rate.toDouble().isFinite()) { "매매기준율이 유효하지 않습니다: round=$round" }
                    previousTime = quotedAt
                    previousRound = round
                    collected.add(ExchangeRateEntity(currency.name, quotedAt, round, rate.toPlainString(), ExchangeRateSource.NAVER_SHINHAN.name))
                }
                if (skippedComparisonCount > 0) {
                    Log.i("Exchange", "네이버 전일 비교용 고시 제외: currency=${currency.name} date=$date comparisonDate=$comparisonDate count=$skippedComparisonCount")
                }
                check(requestedDateCount > 0) { "조회 날짜의 회차별 고시가 없습니다: date=$date" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException("네이버 신한은행 고시 조회 실패: currency=${currency.name} date=$date reason=${error.message}", error)
            }
        }
        collected
    }

    private suspend fun availableDates(currency: ExchangeCurrency, start: LocalDate, end: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var previousDate: LocalDate? = null
        while (true) {
            coroutineContext.ensureActive()
            val builder = "$baseUrl/${currency.name}/daily".toHttpUrl().newBuilder()
                .addQueryParameter("bankType", "shinhan").addQueryParameter("size", "20")
            cursor?.let { builder.addQueryParameter("cursor", it) }
            try {
                val json = read(builder.build().toString(), "currency=${currency.name} start=$start end=$end stage=날짜 조회")
                val items = json.getJSONArray("items")
                val hasNext = json.getBoolean("hasNext")
                check(items.length() > 0 || !hasNext) { "빈 과거 조회 응답에 다음 페이지가 표시되었습니다." }
                var reachedStart = false
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    check(item.getString("currencyCode") == currency.name &&
                        item.getString("marketIndexCode") == "FX_${currency.name}KRW_SHB" &&
                        item.getString("providerKoreanName") == "신한은행" &&
                        item.getString("currencyBasis").toInt() == currency.unit) { "통화·은행·기준 단위가 요청과 다릅니다." }
                    val date = LocalDate.parse(item.getString("date"))
                    check(previousDate == null || date.isBefore(previousDate)) { "과거 조회 날짜 순서가 유효하지 않습니다." }
                    previousDate = date
                    if (date.isBefore(start)) { reachedStart = true; break }
                    if (!date.isAfter(end)) dates.add(date)
                    if (date == start) { reachedStart = true; break }
                }
                if (reachedStart || !hasNext) break
                cursor = json.getString("cursor")
                check(!cursor.isNullOrBlank() && seenCursors.add(checkNotNull(cursor))) { "과거 조회 페이지가 진행되지 않습니다." }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException("네이버 신한은행 과거 날짜 조회 실패: currency=${currency.name} start=$start end=$end reason=${error.message}", error)
            }
        }
        return dates
    }

    private fun read(url: String, stage: String): JSONObject {
        val request = Request.Builder().url(url).header("Referer", "https://stock.naver.com/").build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "네이버 환율 조회 실패: $stage HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "네이버 환율 응답이 없습니다: $stage" }
            JSONObject(body.string()).also { json ->
                check(!json.has("success") || json.getBoolean("success")) { "네이버 환율 조회 거부: $stage" }
            }
        }
    }

    companion object {
        private const val baseUrl = "https://stock.naver.com/api/stockSecurity/exchange-rates/v2"
        private val quoteFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
