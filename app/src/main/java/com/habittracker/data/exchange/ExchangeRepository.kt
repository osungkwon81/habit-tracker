package com.habittracker.data.exchange

import androidx.room.withTransaction
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.local.entity.ExchangeRateEntity
import com.habittracker.data.local.entity.ExchangeRecordEntity
import com.habittracker.data.local.entity.ExchangeExpenseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class ExchangeCurrency(val label: String, val unit: Int) {
    USD("달러", 1), JPY("엔화", 100);
    val rateLabel: String get() = if (this == USD) "1달러당 원화" else "100엔당 원화"
    val amountUnitLabel: String get() = if (this == USD) "달러" else "엔"
}

data class ExchangeQuote(val quotedAt: LocalDateTime, val round: Int, val rate: BigDecimal)
data class ExchangeRecord(val id: Long, val wonAmount: BigDecimal, val appliedRate: BigDecimal)
data class ExchangeExpense(val id: Long, val foreignAmount: BigDecimal, val description: String)
data class ExchangeBalance(val currency: ExchangeCurrency, val exchangedAmount: BigDecimal, val spentAmount: BigDecimal) {
    val remainingAmount: BigDecimal get() = exchangedAmount.subtract(spentAmount)
}

class ExchangeRepository(
    private val database: HabitTrackerDatabase,
    private val protector: HabitTrackerDatabaseProtector,
    private val dao: HabitDao,
) {
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun observeRates(currency: ExchangeCurrency) = dao.observeExchangeRates(currency.name)
    fun observeRecords(currency: ExchangeCurrency) = dao.observeExchangeRecords(currency.name)
    fun observeAllRecords() = dao.observeAllExchangeRecords()
    fun observeAllExpenses() = dao.observeAllExchangeExpenses()

    suspend fun refresh() = refreshMutex.withLock {
        // 공개 조회 화면의 요청 형식. 제휴 Open API와는 별도 경로다.
        val requestJson = JSONObject()
            .put("dataHeader", JSONObject()
                .put("trxCd", "RSHRC0213A01").put("subChannel", "51")
                .put("channelGbn", "DX").put("language", "ko"))
            .put("dataBody", JSONObject()
                .put("조회구분", "1").put("조회일자", "").put("고시회차", "")
                .put("ricInptRootInfo", JSONObject()
                    .put("serviceType", "GU").put("serviceCode", "F3730")
                    .put("language", "ko").put("webUri", "/rib/mnew/index.jsp")))
        val rates = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://m.shinhan.com/serviceEndpoint/httpDigital")
                .header("Referer", "https://m.shinhan.com/rib/mnew/index.jsp")
                .post(requestJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "신한은행 환율 조회 실패: HTTP ${response.code}" }
                val json = JSONObject(checkNotNull(response.body) { "신한은행 환율 응답이 없습니다." }.string())
                val header = json.getJSONObject("dataHeader")
                check(header.getString("result") == "SUCCESS") {
                    "신한은행 환율 조회 거부: ${header.optString("resultCode")}"
                }
                val body = json.getJSONObject("dataBody")
                val quotedAt = LocalDateTime.of(
                    LocalDate.parse(body.getString("고시일자"), DateTimeFormatter.BASIC_ISO_DATE),
                    LocalTime.parse(body.getString("고시시간"), DateTimeFormatter.ofPattern("HHmmss")),
                )
                val round = body.getInt("고시회차")
                check(round > 0) { "신한은행 고시회차가 유효하지 않습니다: $round" }
                val rows = body.getJSONArray("R_RIBF3730_1")
                ExchangeCurrency.entries.map { currency ->
                    val matches = (0 until rows.length()).map { rows.getJSONObject(it) }
                        .filter { it.getString("통화CODE") == currency.name }
                    check(matches.size == 1) { "신한은행 ${currency.name} 환율 응답이 누락되거나 중복되었습니다." }
                    val rate = matches.single().getString("매매기준환율").toBigDecimal()
                    check(rate.signum() > 0 && rate.toDouble().isFinite()) {
                        "신한은행 ${currency.name} 매매기준율이 유효하지 않습니다: $rate"
                    }
                    ExchangeRateEntity(currency.name, quotedAt, round, rate.toPlainString())
                }
            }
        }
        coroutineContext.ensureActive()
        val changed = database.withTransaction {
            val insertedIds = dao.insertExchangeRates(rates)
            rates.forEachIndexed { index, rate ->
                if (insertedIds[index] == -1L) {
                    val previous = dao.getExchangeRate(rate.currency, rate.quotedAt, rate.quoteRound)
                    check(previous != null && previous.rate.toBigDecimal().compareTo(rate.rate.toBigDecimal()) == 0) {
                        "신한은행 고시 데이터 불일치: ${rate.currency} ${rate.quotedAt} ${rate.quoteRound}회차"
                    }
                }
            }
            insertedIds.any { it != -1L }
        }
        if (changed) protector.requestBackup(database)
    }

    suspend fun save(id: Long?, currency: ExchangeCurrency, wonAmount: BigDecimal, appliedRate: BigDecimal) {
        require(wonAmount.signum() > 0 && appliedRate.signum() > 0) { "금액과 당시 환율은 0보다 커야 합니다." }
        val record = ExchangeRecordEntity(id ?: 0, currency.name, wonAmount.toPlainString(), appliedRate.toPlainString())
        if (id == null) dao.insertExchangeRecord(record)
        else require(dao.updateExchangeRecord(record) == 1) { "환전 기록 $id 수정 실패: 기록을 찾을 수 없습니다." }
        protector.requestBackup(database)
    }

    suspend fun delete(id: Long) {
        require(dao.deleteExchangeRecord(id) == 1) { "환전 기록 $id 삭제 실패: 기록을 찾을 수 없습니다." }
        protector.requestBackup(database)
    }

    suspend fun saveExpense(id: Long?, currency: ExchangeCurrency, foreignAmount: BigDecimal, description: String) {
        require(foreignAmount.signum() > 0) { "지출 금액은 0보다 커야 합니다." }
        val content = description.trim()
        require(content.isNotEmpty()) { "지출 내용을 입력해 주세요." }
        val expense = ExchangeExpenseEntity(id ?: 0, currency.name, foreignAmount.toPlainString(), content)
        if (id == null) dao.insertExchangeExpense(expense)
        else require(dao.updateExchangeExpense(expense) == 1) { "지출 기록 $id 수정 실패: 기록을 찾을 수 없습니다." }
        protector.requestBackup(database)
    }

    suspend fun deleteExpense(id: Long) {
        require(dao.deleteExchangeExpense(id) == 1) { "지출 기록 $id 삭제 실패: 기록을 찾을 수 없습니다." }
        protector.requestBackup(database)
    }
}
