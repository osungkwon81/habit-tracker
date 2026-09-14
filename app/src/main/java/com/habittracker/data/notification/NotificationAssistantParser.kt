package com.habittracker.data.notification

import com.habittracker.data.local.entity.NotificationAssistantItemEntity
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object NotificationAssistantStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val DONE = "DONE"
    const val IGNORED = "IGNORED"
}

object NotificationAssistantCategory {
    const val RESERVATION = "RESERVATION"
    const val DEADLINE = "DEADLINE"
    const val DELIVERY = "DELIVERY"
    const val PAYMENT = "PAYMENT"
    const val ORDER = "ORDER"
}

internal object NotificationAssistantParser {
    private val sensitivePattern = Regex("인증번호|인증 코드|일회용 비밀번호|OTP|보안코드|비밀번호|주민등록번호", RegexOption.IGNORE_CASE)
    private val explicitDate = Regex("(?<!\\d)(20\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})(?!\\d)")
    private val monthDay = Regex("(?<!\\d)(\\d{1,2})월\\s*(\\d{1,2})일")
    private val clockTime = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val koreanTime = Regex("(오전|오후)?\\s*(\\d{1,2})시(?:\\s*([0-5]?\\d)분)?")
    private val reservationPattern = Regex("예약|진료|검진|방문 예정|예매")
    private val deadlinePattern = Regex("마감|만료|까지|제출 기한")
    private val deliveryPattern = Regex("배송|택배|운송장|송장번호|집하|배달|상품.{0,12}도착|물품.{0,12}도착")
    private val orderPattern = Regex("주문완료|주문 완료|주문하신|구매완료|구매 완료")
    private val paymentPattern = Regex("결제|승인|이용")
    private val canceledPaymentPattern = Regex("결제.?취소|승인.?취소|취소.?승인")
    private val amountPattern = Regex("(?<!\\d)(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*원")
    private val merchantPattern = Regex("(?:가맹점|사용처|결제처)\\s*[:：]\\s*([^\\n,]+)")
    private val productPattern = Regex("(?:상품명|주문상품|구매상품)\\s*[:：]\\s*([^\\n,]+)")
    private val orderIdPattern = Regex("주문번호\\s*[:：]?\\s*([A-Za-z0-9-]{6,})")
    private val trackingIdPattern = Regex("(?:운송장번호|송장번호)\\s*[:：]?\\s*([A-Za-z0-9-]{6,})")

    fun parse(
        sourceKey: String,
        sourcePackage: String,
        title: String,
        content: String,
        postedAtMillis: Long,
    ): NotificationAssistantItemEntity? {
        val text = listOf(title, content).filter(String::isNotBlank).joinToString(" ").trim()
        if (text.isBlank() || sensitivePattern.containsMatchIn(text)) return null
        val category = when {
            Regex("배송.?완료|배달.?완료|도착.?완료").containsMatchIn(text) -> NotificationAssistantCategory.DELIVERY
            orderPattern.containsMatchIn(text) -> NotificationAssistantCategory.ORDER
            deliveryPattern.containsMatchIn(text) -> NotificationAssistantCategory.DELIVERY
            paymentPattern.containsMatchIn(text) && !canceledPaymentPattern.containsMatchIn(text) &&
                amountPattern.containsMatchIn(text) -> NotificationAssistantCategory.PAYMENT
            reservationPattern.containsMatchIn(text) -> NotificationAssistantCategory.RESERVATION
            deadlinePattern.containsMatchIn(text) -> NotificationAssistantCategory.DEADLINE
            "도착 예정" in text -> NotificationAssistantCategory.DELIVERY
            else -> return null
        }
        val receivedAt = Instant.ofEpochMilli(postedAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val isCommerce = category in setOf(
            NotificationAssistantCategory.PAYMENT,
            NotificationAssistantCategory.ORDER,
            NotificationAssistantCategory.DELIVERY,
        )
        val sourceIdentity = if (isCommerce) "$sourcePackage|$sourceKey|$postedAtMillis|$category"
            else "$sourcePackage|$sourceKey|$text"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val amount = amountPattern.find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        val merchant = merchantPattern.find(text)?.groupValues?.get(1)?.trim()?.take(80)
        val productName = productPattern.find(text)?.groupValues?.get(1)?.trim()?.take(100)
        val referenceId = orderIdPattern.find(text)?.groupValues?.get(1)?.let { "order:$it" }
            ?: trackingIdPattern.find(text)?.groupValues?.get(1)?.let { "tracking:$it" }
        val deliveryState = if (category == NotificationAssistantCategory.DELIVERY) when {
            Regex("배송.?완료|배달.?완료|도착.?완료").containsMatchIn(text) -> "배송 완료"
            Regex("배송.?중|배달.?중|출발|집하").containsMatchIn(text) -> "배송 중"
            else -> "배송 예정"
        } else null
        val summarizedTitle = when (category) {
            NotificationAssistantCategory.PAYMENT -> "결제 ${amount?.let { "${it}원" } ?: "금액 확인 필요"}${merchant?.let { " · $it" }.orEmpty()}"
            NotificationAssistantCategory.ORDER -> "주문 ${productName ?: "상품 확인 필요"}${amount?.let { " · ${it}원" }.orEmpty()}"
            NotificationAssistantCategory.DELIVERY -> "${deliveryState ?: "배송"} · ${productName ?: "상품 확인 필요"}"
            else -> title.trim().ifBlank { content.trim().take(80) }
        }
        return NotificationAssistantItemEntity(
            sourceKey = digest,
            sourcePackage = sourcePackage,
            category = category,
            title = summarizedTitle.take(120),
            sourceText = if (isCommerce) "" else content.trim().take(300),
            eventAt = extractEventAt(text, receivedAt.toLocalDate()),
            receivedAt = receivedAt,
            status = if (isCommerce) NotificationAssistantStatus.ACCEPTED else NotificationAssistantStatus.PENDING,
            amount = amount,
            merchant = merchant,
            productName = productName,
            referenceId = referenceId,
            deliveryState = deliveryState,
        )
    }

    private fun extractEventAt(text: String, referenceDate: LocalDate): LocalDateTime? {
        val date = when {
            "내일" in text -> referenceDate.plusDays(1)
            "오늘" in text -> referenceDate
            explicitDate.containsMatchIn(text) -> explicitDate.find(text)?.destructured?.let { (year, month, day) ->
                runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
            }
            monthDay.containsMatchIn(text) -> monthDay.find(text)?.destructured?.let { (month, day) ->
                runCatching { LocalDate.of(referenceDate.year, month.toInt(), day.toInt()) }
                    .getOrNull()?.takeUnless { it.isBefore(referenceDate) }
            }
            else -> null
        } ?: return null
        val time = clockTime.find(text)?.destructured?.let { (hour, minute) ->
            LocalTime.of(hour.toInt(), minute.toInt())
        } ?: koreanTime.find(text)?.destructured?.let { (period, hourText, minuteText) ->
            val hour = hourText.toInt()
            if (hour !in 0..23 || (period.isNotEmpty() && hour !in 1..12)) return@let null
            val adjusted = when (period) {
                "오전" -> hour % 12
                "오후" -> hour % 12 + 12
                else -> hour
            }
            LocalTime.of(adjusted, minuteText.toIntOrNull() ?: 0)
        } ?: LocalTime.MIDNIGHT
        return LocalDateTime.of(date, time)
    }
}
