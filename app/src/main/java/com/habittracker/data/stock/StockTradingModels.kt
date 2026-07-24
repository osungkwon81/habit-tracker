package com.habittracker.data.stock

import com.habittracker.data.local.entity.StockOrderEntity
import com.habittracker.data.local.entity.StockSafetyConfigEntity
import java.time.LocalDate
import java.time.LocalDateTime

internal const val STOCK_CRASH_GUARD_BLOCK_PREFIX = "급락 안전장치:"

internal fun StockSafetyConfigEntity.isCrashGuardOrderBlock(): Boolean =
    globalOrderBlocked && blockReason?.let { reason ->
        reason.startsWith(STOCK_CRASH_GUARD_BLOCK_PREFIX) || reason.contains("급락 기준")
    } == true

enum class StockOrderStatus {
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    UNKNOWN,
    REJECTED,
}

enum class StockOrderSource(val label: String) {
    MANUAL("이 앱 직접 주문"),
    MANUAL_ENTRY("수동 입력"),
    EXTERNAL("KIS 외부 주문"),
    STOP_LOSS("이 앱 자동 매도 · 손절"),
    TAKE_PROFIT("이 앱 자동 매도 · 익절"),
    TRAILING_STOP("이 앱 자동 매도 · 고점 추적"),
    TIME_EXIT("이 앱 자동 매도 · 기간 청산"),
    REBALANCE("이 앱 리밸런싱"),
}

enum class StockExitRuleType(val label: String, val unit: String) {
    STOP_LOSS("손절", "%"),
    TAKE_PROFIT("익절", "%"),
    TRAILING_STOP("고점 추적", "%"),
    TIME_EXIT("보유 기간", "일"),
}

enum class StockRuleAction(val label: String) {
    NOTIFY_ONLY("알림만"),
    AUTO_SELL("자동 매도"),
}

data class KisBuyableQuantity(
    val quantity: Long,
    val amount: Long,
)

data class KisMarketIndex(
    val code: String,
    val name: String,
    val currentValue: String,
    val changeRatePercent: Double,
)

data class KisOrderExecution(
    val orderNumber: String,
    val productCode: String,
    val productName: String,
    val side: KisOrderSide,
    val orderDate: LocalDate,
    val orderTime: String,
    val orderedQuantity: Long,
    val orderedUnitPrice: Long,
    val orderDivisionCode: String,
    val exchangeCode: String,
    val filledQuantity: Long,
    val filledAveragePrice: Long?,
    val remainingQuantity: Long,
    val canceledQuantity: Long,
    val rejectedQuantity: Long,
    val isCanceled: Boolean,
)

data class StockBuyLotRow(
    val order: StockOrderEntity,
    val currentPrice: Long?,
    val estimatedReturnPercent: Double?,
)

data class StockBulkSellFailure(
    val productCode: String,
    val productName: String,
    val reason: String,
)

data class StockBulkSellResult(
    val submittedOrders: List<StockOrderEntity>,
    val failures: List<StockBulkSellFailure>,
)

data class StockRebalanceLine(
    val productCode: String,
    val productName: String,
    val targetPercent: Double,
    val currentPercent: Double,
    val currentQuantity: Long,
    val targetQuantity: Long,
    val orderSide: KisOrderSide?,
    val orderQuantity: Long,
    val referencePrice: Long,
)

data class StockJournalAnalysis(
    val filledBuyCount: Int,
    val filledSellCount: Int,
    val realizedTradeCount: Int,
    val profitableTradeCount: Int,
    val estimatedRealizedProfit: Long,
    val winRatePercent: Double?,
    val sourceCounts: Map<StockOrderSource, Int>,
)

data class StockAutomationNotice(
    val title: String,
    val message: String,
    val productCode: String? = null,
)

data class StockAutomationCycleResult(
    val checkedAt: LocalDateTime,
    val notices: List<StockAutomationNotice>,
    val skippedReason: String? = null,
    val monitoredProductCount: Int = 0,
    val activeRuleCount: Int = 0,
)
