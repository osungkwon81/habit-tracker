package com.habittracker.data.lotto

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** 동행복권 로또 QR에서 읽은 회차와 구매 게임 목록이다. */
data class LottoQrPurchase(
    val roundNo: Int,
    val tickets: List<List<Int>>,
)

object LottoQrParser {
    private const val maxQrLength = 2_048
    private val payloadPattern = Regex("(?:[?&]|&amp;)v=([^&#]+)", RegexOption.IGNORE_CASE)
    private val ticketDelimiterPattern = Regex("[mMqQ]")

    /**
     * 공식 QR의 `v=회차m12자리번호...` 값을 앱 모델로 변환한다.
     * QR 문자열은 외부 입력이므로 공식 호스트·회차·번호 범위를 모두 다시 검증한다.
     */
    fun parse(rawValue: String): LottoQrPurchase {
        val raw = rawValue.trim()
        require(raw.length in 1..maxQrLength) { "QR 내용을 확인할 수 없습니다." }
        val normalizedRaw = raw.replace("&amp;", "&", ignoreCase = true)
        val uriText = if ("://" in normalizedRaw) normalizedRaw else "https://$normalizedRaw"
        val host = runCatching { URI(uriText).host.orEmpty().lowercase() }.getOrDefault("")
        require(host == "dhlottery.co.kr" || host.endsWith(".dhlottery.co.kr")) {
            "동행복권 로또 QR만 등록할 수 있습니다."
        }

        val encodedPayload = payloadPattern.find(normalizedRaw)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("QR에서 로또 번호 정보를 찾을 수 없습니다.")
        val payload = runCatching {
            URLDecoder.decode(encodedPayload, StandardCharsets.UTF_8.name())
        }.getOrElse { throw IllegalArgumentException("QR 번호 정보를 해석할 수 없습니다.", it) }
        val sections = payload.split(ticketDelimiterPattern)
        require(sections.size >= 2) { "지원하지 않는 로또 QR 형식입니다." }
        val roundNo = sections.firstOrNull()?.takeWhile(Char::isDigit)?.toIntOrNull()
        require(roundNo != null && roundNo > 0) { "QR에서 구입 회차를 확인할 수 없습니다." }

        val tickets = sections.drop(1).map { section ->
            val numberText = section.take(12)
            require(numberText.length == 12 && numberText.all(Char::isDigit)) {
                "QR의 게임 번호 형식이 올바르지 않습니다."
            }
            numberText.chunked(2).map(String::toInt).also { numbers ->
                require(numbers.all { it in 1..45 } && numbers.distinct().size == 6) {
                    "QR에 1~45 범위를 벗어나거나 중복된 번호가 있습니다."
                }
            }.sorted()
        }
        require(tickets.size in 1..5) { "QR에는 로또 번호가 1~5게임 포함되어야 합니다." }

        return LottoQrPurchase(roundNo = roundNo, tickets = tickets)
    }
}
