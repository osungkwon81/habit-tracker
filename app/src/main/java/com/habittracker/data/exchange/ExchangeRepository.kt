package com.habittracker.data.exchange

import android.util.Base64
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.local.entity.ExchangeRateEntity
import com.habittracker.data.local.entity.ExchangeRecordEntity
import com.habittracker.data.local.entity.ExchangeExpenseEntity
import com.habittracker.widget.ExchangeRateWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

enum class ExchangeCurrency(val label: String, val unit: Int) {
    USD("달러", 1), JPY("엔화", 100);
    val rateLabel: String get() = if (this == USD) "1달러당 원화" else "100엔당 원화"
    val amountUnitLabel: String get() = if (this == USD) "달러" else "엔"
}

enum class ExchangeRateSource(val label: String) {
    SHINHAN("신한은행"), NAVER_SHINHAN("네이버 · 신한은행");
}

data class ExchangeQuote(val quotedAt: LocalDateTime, val round: Int, val rate: BigDecimal, val source: ExchangeRateSource = ExchangeRateSource.SHINHAN)
data class ExchangeRecord(val id: Long, val wonAmount: BigDecimal, val appliedRate: BigDecimal)
data class ExchangeExpense(val id: Long, val foreignAmount: BigDecimal, val description: String, val expenseDate: LocalDate? = null)
data class ExchangeBalance(val currency: ExchangeCurrency, val exchangedAmount: BigDecimal, val spentAmount: BigDecimal) {
    val remainingAmount: BigDecimal get() = exchangedAmount.subtract(spentAmount)
}

class ExchangeRepository(
    private val database: HabitTrackerDatabase,
    private val protector: HabitTrackerDatabaseProtector,
    private val dao: HabitDao,
    context: Context,
) {
    private val applicationContext = context.applicationContext
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
            val keyRequest = Request.Builder()
                .url("https://m.shinhan.com/comjsp/comInitInfo.jsp")
                .header("Referer", "https://m.shinhan.com/rib/mnew/index.jsp")
                .post(ByteArray(0).toRequestBody())
                .build()
            val (keyBytes, cookies) = client.newCall(keyRequest).execute().use { response ->
                check(response.isSuccessful) { "신한은행 환율 암호화 준비 실패: HTTP ${response.code}" }
                val keyJson = JSONObject(checkNotNull(response.body) { "신한은행 환율 암호화 응답이 없습니다." }.string())
                val keyHex = keyJson.getString("v").trim()
                check(keyHex.matches(Regex("[0-9a-fA-F]{64}"))) { "신한은행 환율 암호화 키 형식이 유효하지 않습니다." }
                val bytes = ByteArray(32) { index -> keyHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                bytes to Cookie.parseAll(response.request.url, response.headers)
            }
            coroutineContext.ensureActive()
            // 공개 웹의 shbSec.js 규칙: 본문만 AES-256-CBC로 암호화하고 IV는 키 앞 16바이트를 사용한다.
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes.copyOfRange(0, 16)))
            val encryptedBody = cipher.doFinal(requestJson.getJSONObject("dataBody").toString().toByteArray(Charsets.UTF_8))
            requestJson.put("dataBody", Base64.encodeToString(encryptedBody, Base64.NO_WRAP))
            requestJson.getJSONObject("dataHeader").put("encG", "1").put("procG", "1")
            val requestUrl = "https://m.shinhan.com/serviceEndpoint/httpDigital".toHttpUrl()
            val cookieHeader = cookies.filter { it.matches(requestUrl) }
                .joinToString("; ") { "${it.name}=${it.value}" }
            val request = Request.Builder()
                .url(requestUrl)
                .header("Referer", "https://m.shinhan.com/rib/mnew/index.jsp")
                .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
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
        if (changed) notifyRatesChanged()
    }

    suspend fun backfillMissingRates(): Int = refreshMutex.withLock {
        val history = NaverShinhanRateHistory(client)
        val candidates = mutableListOf<ExchangeRateEntity>()
        for (currency in ExchangeCurrency.entries) {
            val existing = dao.getExchangeRates(currency.name)
            if (existing.size < 2) {
                Log.i("Exchange", "누락 고시 보충 생략: currency=${currency.name} reason=저장 구간 부족 count=${existing.size}")
                continue
            }
            candidates.addAll(history.fetchMissing(currency, existing))
        }
        coroutineContext.ensureActive()
        val insertedCount = database.withTransaction {
            val missing = mutableListOf<ExchangeRateEntity>()
            for ((currency, rows) in candidates.groupBy { it.currency }) {
                val existing = dao.getExchangeRates(currency).associateBy { it.quotedAt.toLocalDate() to it.quoteRound }
                for (row in rows) {
                    val previous = existing[row.quotedAt.toLocalDate() to row.quoteRound]
                    if (previous == null) missing.add(row)
                    else check(previous.quotedAt == row.quotedAt && previous.rate.toBigDecimal().compareTo(row.rate.toBigDecimal()) == 0) {
                        "네이버 보충 고시 불일치: currency=$currency date=${row.quotedAt.toLocalDate()} round=${row.quoteRound}"
                    }
                }
            }
            val inserted = dao.insertExchangeRates(missing)
            missing.forEachIndexed { index, row ->
                if (inserted[index] == -1L) {
                    val previous = dao.getExchangeRate(row.currency, row.quotedAt, row.quoteRound)
                    check(previous != null && previous.rate.toBigDecimal().compareTo(row.rate.toBigDecimal()) == 0) {
                        "네이버 보충 저장 불일치: currency=${row.currency} quotedAt=${row.quotedAt} round=${row.quoteRound}"
                    }
                }
            }
            inserted.count { it != -1L }
        }
        if (insertedCount > 0) notifyRatesChanged()
        insertedCount
    }

    suspend fun recollectRates(startDate: LocalDate): Int = refreshMutex.withLock {
        val endDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
        require(!startDate.isAfter(endDate)) { "시작 날짜는 오늘 이후로 선택할 수 없습니다: start=$startDate end=$endDate" }
        val history = NaverShinhanRateHistory(client)
        val collected = mutableListOf<ExchangeRateEntity>()
        for (currency in ExchangeCurrency.entries) {
            val rows = history.fetchRange(currency, startDate, endDate)
            check(rows.isNotEmpty()) { "선택 구간에 제공되는 ${currency.label} 고시가 없어 기존 이력을 유지합니다: start=$startDate end=$endDate" }
            collected.addAll(rows)
        }
        coroutineContext.ensureActive()
        // 두 통화의 조회가 끝난 뒤 교체한다. 조회 실패나 저장 오류로 기존 이력을 잃지 않게 한다.
        database.withTransaction {
            dao.deleteAllExchangeRates()
            val inserted = dao.insertExchangeRates(collected)
            check(inserted.size == collected.size && inserted.all { it != -1L }) {
                "환율 이력 교체 중 고시 중복이 발생했습니다: start=$startDate end=$endDate"
            }
        }
        notifyRatesChanged()
        collected.size
    }

    private suspend fun notifyRatesChanged() {
        protector.requestBackup(database)
        try {
            ExchangeRateWidgetProvider.enqueueUpdate(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ExchangeWidget", "환율 저장 후 위젯 갱신 예약 실패", error)
        }
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

    suspend fun saveExpense(id: Long?, currency: ExchangeCurrency, foreignAmount: BigDecimal, description: String, expenseDate: LocalDate? = null) {
        require(foreignAmount.signum() > 0) { "지출 금액은 0보다 커야 합니다." }
        val content = description.trim()
        require(content.isNotEmpty()) { "지출 내용을 입력해 주세요." }
        val expense = ExchangeExpenseEntity(id ?: 0, currency.name, foreignAmount.toPlainString(), content, expenseDate)
        if (id == null) dao.insertExchangeExpense(expense)
        else require(dao.updateExchangeExpense(expense) == 1) { "지출 기록 $id 수정 실패: 기록을 찾을 수 없습니다." }
        protector.requestBackup(database)
    }

    suspend fun deleteExpense(id: Long) {
        require(dao.deleteExchangeExpense(id) == 1) { "지출 기록 $id 삭제 실패: 기록을 찾을 수 없습니다." }
        protector.requestBackup(database)
    }
}
