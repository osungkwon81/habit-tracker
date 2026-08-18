package com.habittracker.data.lotto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class DhlotteryDrawClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun getLottoDrawsAround(roundNo: Int): OfficialLottoDrawBatch = withContext(Dispatchers.IO) {
        require(roundNo > 0) { "조회할 로또 회차가 올바르지 않습니다." }
        val url = "$officialBaseUrl/lt645/selectPstLt645InfoNew.do".toHttpUrl().newBuilder()
            .addQueryParameter("srchDir", "center")
            .addQueryParameter("srchLtEpsd", roundNo.toString())
            .build()
        val raw = getOfficialJson(url.toString(), "/lt645/result")
        val hash = raw.sha256()
        val items = JSONObject(raw).requiredData().getJSONArray("list")
        val draws = buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val numbers = (1..6).map { position -> item.getInt("tm${position}WnNo") }
                require(numbers.all { it in 1..45 } && numbers.distinct().size == 6) {
                    "공식 로또 당첨번호 값이 올바르지 않습니다."
                }
                val bonusNumber = item.getInt("bnsWnNo")
                require(bonusNumber in 1..45 && bonusNumber !in numbers) {
                    "공식 로또 보너스 번호 값이 올바르지 않습니다."
                }
                add(
                    OfficialLottoDraw(
                        roundNo = item.getInt("ltEpsd"),
                        drawDate = item.getString("ltRflYmd").toBasicDate(),
                        numbers = numbers.sorted(),
                        bonusNumber = bonusNumber,
                        sourceReference = url.toString(),
                        sourceContentHash = hash,
                    ),
                )
            }
        }
        require(draws.isNotEmpty()) { "공식 로또 당첨번호 응답이 비어 있습니다." }
        OfficialLottoDrawBatch(draws)
    }

    suspend fun getPensionLotteryDraws(): List<OfficialPensionLotteryDraw> = withContext(Dispatchers.IO) {
        val url = "$officialBaseUrl/pt720/selectPstPt720WnList.do"
        val raw = getOfficialJson(url, "/pt720/result")
        val items = JSONObject(raw).requiredData().getJSONArray("result")
        val draws = buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val winningNumber = item.getString("wnRnkVl")
                val groupNo = item.getString("wnBndNo").toIntOrNull()
                require(groupNo != null && groupNo in 1..5 && winningNumber.matches(Regex("\\d{6}"))) {
                    "공식 연금복권 당첨번호 값이 올바르지 않습니다."
                }
                add(
                    OfficialPensionLotteryDraw(
                        roundNo = item.getInt("psltEpsd"),
                        drawDate = item.getString("psltRflYmd").toBasicDate(),
                        groupNo = groupNo,
                        winningNumber = winningNumber,
                    ),
                )
            }
        }
        require(draws.isNotEmpty()) { "공식 연금복권 당첨번호 응답이 비어 있습니다." }
        draws
    }

    private fun getOfficialJson(url: String, menuPath: String): String {
        val request = Request.Builder()
            .url(url)
            .header("AJAX", "true")
            .header("requestMenuUri", menuPath)
            .header("User-Agent", "HabitTracker Android")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("동행복권 서버 응답 오류가 발생했습니다. (HTTP ${response.code})")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("동행복권 서버 응답이 비어 있습니다.")
            require(body.length <= maxResponseLength) { "동행복권 서버 응답 크기가 허용 범위를 넘었습니다." }
            body
        }
    }

    private fun JSONObject.requiredData(): JSONObject {
        if (!isNull("resultCode")) {
            throw IllegalStateException(optString("resultMessage").ifBlank { "동행복권 조회가 거부되었습니다." })
        }
        return optJSONObject("data")
            ?: throw IllegalStateException("동행복권 응답에서 당첨번호 데이터를 찾지 못했습니다.")
    }

    private fun String.toBasicDate(): LocalDate =
        LocalDate.parse(this, DateTimeFormatter.BASIC_ISO_DATE)

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val officialBaseUrl = "https://www.dhlottery.co.kr"
        const val maxResponseLength = 2_000_000
    }
}
