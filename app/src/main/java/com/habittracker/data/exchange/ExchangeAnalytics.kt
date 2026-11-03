package com.habittracker.data.exchange

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime

data class ExchangeCardComparison(
    val cardRate: BigDecimal,
    val averageWonAmount: BigDecimal?,
    val rateDifference: BigDecimal?,
    val wonDifference: BigDecimal?,
)

internal fun compareCardExpense(currency: ExchangeCurrency, foreignAmount: BigDecimal?, cardWonAmount: BigDecimal?,
    averageRate: BigDecimal?): ExchangeCardComparison? {
    if (foreignAmount == null || foreignAmount.signum() <= 0 || cardWonAmount == null || cardWonAmount.signum() <= 0) return null
    val unit = currency.unit.toBigDecimal()
    val rate = cardWonAmount.multiply(unit).divide(foreignAmount, 16, RoundingMode.HALF_UP)
    val averageWon = averageRate?.multiply(foreignAmount)?.divide(unit, 16, RoundingMode.HALF_UP)
    return ExchangeCardComparison(rate, averageWon, averageRate?.let(rate::subtract), averageWon?.let(cardWonAmount::subtract))
}

data class ExchangeExpenseAnalysis(
    val quote: ExchangeQuote?,
    val marketWonAmount: BigDecimal?,
    val averageRate: BigDecimal?,
    val foreignCostWon: BigDecimal?,
    val actualWonAmount: BigDecimal?,
    val cardEffectiveRate: BigDecimal?,
    val basisIssue: String?,
)

data class ExchangeAnalytics(
    val cumulativeAverageRate: BigDecimal?,
    val remainingAverageRate: BigDecimal?,
    val remainingCostWon: BigDecimal?,
    val currentValueWon: BigDecimal?,
    val valuationDifferenceWon: BigDecimal?,
    val basisIssue: String?,
    val expenseAnalyses: Map<Long, ExchangeExpenseAnalysis>,
    val knownExpenseWon: BigDecimal,
    val unknownExpenseCostCount: Int,
)

private data class ExchangeLedgerEvent(val record: ExchangeRecord? = null, val expense: ExchangeExpense? = null) {
    val date: LocalDate? get() = record?.exchangeDate ?: expense?.expenseDate
    val time: LocalTime? get() = record?.exchangeTime ?: expense?.expenseTime
    val changesBalance: Boolean get() = record != null || expense?.usesForeignBalance == true
}

internal fun exchangedForeignAmount(record: ExchangeRecord, currency: ExchangeCurrency): BigDecimal =
    record.wonAmount.multiply(currency.unit.toBigDecimal()).divide(record.appliedRate, 8, RoundingMode.HALF_UP)

private fun averageRate(cost: BigDecimal, amount: BigDecimal, unit: BigDecimal): BigDecimal? =
    if (amount.signum() > 0) cost.multiply(unit).divide(amount, 16, RoundingMode.HALF_UP) else null

internal fun analyzeExchange(
    currency: ExchangeCurrency,
    records: List<ExchangeRecord>,
    expenses: List<ExchangeExpense>,
    quotes: List<ExchangeQuote>,
): ExchangeAnalytics {
    val unit = currency.unit.toBigDecimal()
    val quantities = records.associate { it.id to exchangedForeignAmount(it, currency) }
    val totalExchanged = quantities.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val totalWon = records.fold(BigDecimal.ZERO) { sum, record -> sum.add(record.wonAmount) }
    val totalSpent = expenses.filter { it.usesForeignBalance }.fold(BigDecimal.ZERO) { sum, expense -> sum.add(expense.foreignAmount) }
    val remaining = totalExchanged.subtract(totalSpent)
    val events = records.map { ExchangeLedgerEvent(record = it) } + expenses.map { ExchangeLedgerEvent(expense = it) }
    var issue: String? = if (events.any { it.changesBalance && it.date == null }) {
        "날짜 없는 환전·외화 지출이 있어 거래 순서에 따른 원가를 계산할 수 없습니다."
    } else null
    var quantity = BigDecimal.ZERO
    var cost = BigDecimal.ZERO
    val costs = mutableMapOf<Long, BigDecimal>()
    val averages = mutableMapOf<Long, BigDecimal>()
    val expenseIssues = mutableMapOf<Long, String>()

    for ((date, dayEvents) in events.filter { it.date != null }.groupBy { requireNotNull(it.date) }.toSortedMap()) {
        val balanceEvents = dayEvents.filter { it.changesBalance }
        val hasExchange = balanceEvents.any { it.record != null }
        val hasExpense = balanceEvents.any { it.expense != null }
        val mixedTimes = balanceEvents.groupBy { it.time }.values.any { group ->
            group.any { it.record != null } && group.any { it.expense != null }
        }
        if (issue == null && hasExchange && hasExpense && (balanceEvents.any { it.time == null } || mixedTimes)) {
            issue = "$date 환전·지출의 순서가 불명확합니다. 서로 다른 거래 시각을 입력해 주세요."
        }
        val daySpent = balanceEvents.mapNotNull { it.expense }.fold(BigDecimal.ZERO) { sum, expense -> sum.add(expense.foreignAmount) }
        if (issue == null && !hasExchange && daySpent > quantity) {
            issue = "$date 외화 지출이 당시 보유량을 초과합니다. 누락된 환전 기록을 확인해 주세요."
        }
        if (issue != null) {
            dayEvents.mapNotNull { it.expense }.forEach { expenseIssues[it.id] = requireNotNull(issue) }
            continue
        }
        // 같은 종류의 거래는 시각이 없어도 평균 원가에 영향이 없다. 환전과 지출의 순서는 위에서 확인한다.
        for (event in dayEvents.sortedBy { it.time ?: LocalTime.MIN }) {
            val record = event.record
            if (record != null) {
                quantity = quantity.add(quantities.getValue(record.id))
                cost = cost.add(record.wonAmount)
                continue
            }
            val expense = requireNotNull(event.expense)
            if (issue != null) {
                expenseIssues[expense.id] = requireNotNull(issue)
                continue
            }
            if (!expense.usesForeignBalance && balanceEvents.any { it.time == null || expense.expenseTime == null || it.time == expense.expenseTime }) {
                expenseIssues[expense.id] = "$date 외화 거래와 카드 결제의 순서를 확인하려면 거래 시각이 필요합니다."
                continue
            }
            val average = averageRate(cost, quantity, unit)
            if (average == null || expense.foreignAmount > quantity) {
                val reason = "지출 당시 보유 외화가 부족해 동일 금액의 원가를 비교할 수 없습니다."
                expenseIssues[expense.id] = reason
                if (expense.usesForeignBalance) issue = "$date 외화 잔액 부족: 누락된 환전 기록을 확인해 주세요."
                continue
            }
            val usedCost = if (expense.foreignAmount.compareTo(quantity) == 0) cost
                else cost.multiply(expense.foreignAmount).divide(quantity, 16, RoundingMode.HALF_UP)
            costs[expense.id] = usedCost
            averages[expense.id] = average
            if (expense.usesForeignBalance) {
                quantity = quantity.subtract(expense.foreignAmount)
                cost = cost.subtract(usedCost)
            }
        }
    }
    val analyses = expenses.associate { expense ->
        val quote = expense.expenseDate?.let { date ->
            val cutoff = date.atTime(expense.expenseTime ?: LocalTime.MAX)
            var low = 0
            var high = quotes.size
            while (low < high) {
                val middle = (low + high) / 2
                if (quotes[middle].quotedAt <= cutoff) low = middle + 1 else high = middle
            }
            quotes.getOrNull(low - 1)
        }
        val foreignCost = costs[expense.id]
        expense.id to ExchangeExpenseAnalysis(
            quote = quote,
            marketWonAmount = quote?.rate?.multiply(expense.foreignAmount)?.divide(unit),
            averageRate = averages[expense.id],
            foreignCostWon = foreignCost,
            actualWonAmount = if (expense.usesForeignBalance) foreignCost else expense.cardWonAmount,
            cardEffectiveRate = if (expense.usesForeignBalance) null else
                expense.cardWonAmount?.multiply(unit)?.divide(expense.foreignAmount, 16, RoundingMode.HALF_UP),
            basisIssue = expenseIssues[expense.id] ?: if (expense.expenseDate == null) "지출 날짜를 입력하면 당시 환율과 원가를 비교할 수 있습니다." else null,
        )
    }
    // 날짜 없이도 미사용 외화의 원가는 확정된다. 사용 이력이 있으면 누적 평균으로 대신 확정하지 않는다.
    val remainingCost = when {
        totalSpent.signum() == 0 -> totalWon
        issue == null -> cost
        else -> null
    }
    val currentValue = quotes.lastOrNull()?.rate?.let { rate ->
        remaining.takeIf { it.signum() >= 0 }?.multiply(rate)?.divide(unit)
    }
    return ExchangeAnalytics(
        cumulativeAverageRate = averageRate(totalWon, totalExchanged, unit),
        remainingAverageRate = remainingCost?.let { averageRate(it, remaining, unit) },
        remainingCostWon = remainingCost,
        currentValueWon = currentValue,
        valuationDifferenceWon = currentValue?.let { value -> remainingCost?.let(value::subtract) },
        basisIssue = issue,
        expenseAnalyses = analyses,
        knownExpenseWon = analyses.values.mapNotNull { it.actualWonAmount }.fold(BigDecimal.ZERO, BigDecimal::add),
        unknownExpenseCostCount = analyses.values.count { it.actualWonAmount == null },
    )
}
