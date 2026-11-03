package com.habittracker.data.exchange

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExchangeAnalyticsTest {
    private val firstDay = LocalDate.of(2026, 10, 1)
    private fun decimal(value: String) = BigDecimal(value)
    private fun record(id: Long, won: String, rate: String, day: Long = 0) =
        ExchangeRecord(id, decimal(won), decimal(rate), firstDay.plusDays(day))
    private fun expense(id: Long, amount: String, day: Long, method: ExchangePaymentMethod? = ExchangePaymentMethod.CASH, cardWon: String? = null) =
        ExchangeExpense(id, decimal(amount), "테스트 지출", firstDay.plusDays(day), paymentMethod = method, cardWonAmount = cardWon?.let(::decimal))
    private fun money(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, decimal(expected).compareTo(requireNotNull(actual)))
    }

    @Test fun dollarCardDisplayComparesAgainstCumulativeAverageWithoutDateOrTime() {
        val result = requireNotNull(compareCardExpense(ExchangeCurrency.USD, decimal("20"), decimal("28000"), decimal("1350")))
        money("1400", result.cardRate)
        money("27000", result.averageWonAmount)
        money("50", result.rateDifference)
        money("1000", result.wonDifference)
        val missingAverage = requireNotNull(compareCardExpense(ExchangeCurrency.USD, decimal("20"), decimal("28000"), null))
        money("1400", missingAverage.cardRate)
        assertNull(missingAverage.wonDifference)
        assertNull(compareCardExpense(ExchangeCurrency.USD, BigDecimal.ZERO, decimal("28000"), decimal("1350")))
    }

    @Test fun yenComparisonUsesHundredYenAndCardDisplayDoesNotReplaceForeignCost() {
        val result = requireNotNull(compareCardExpense(ExchangeCurrency.JPY, decimal("2000"), decimal("19000"), decimal("900")))
        money("950", result.cardRate)
        money("18000", result.averageWonAmount)
        money("50", result.rateDifference)
        money("1000", result.wonDifference)
        val ledger = analyzeExchange(ExchangeCurrency.USD, listOf(record(1, "100000", "1000")),
            listOf(expense(1, "20", 1, ExchangePaymentMethod.FOREIGN_CARD, "28000")), emptyList())
        money("80000", ledger.remainingCostWon)
        money("20000", ledger.knownExpenseWon)
        money("20000", ledger.expenseAnalyses.getValue(1).actualWonAmount)
    }

    @Test fun yenMovingAveragePreservesPastExpenseCostAfterLaterExchange() {
        val records = listOf(record(2, "100000", "1000", 2), record(1, "90000", "900"))
        val quote = ExchangeQuote(firstDay.plusDays(3).atTime(12, 0), 1, decimal("1100"))
        val result = analyzeExchange(ExchangeCurrency.JPY, records, listOf(expense(1, "2000", 1)), listOf(quote))
        money("18000", result.expenseAnalyses.getValue(1).actualWonAmount)
        money("900", result.expenseAnalyses.getValue(1).averageRate)
        money("950", result.cumulativeAverageRate)
        money("172000", result.remainingCostWon)
        money("198000", result.currentValueWon)
        money("26000", result.valuationDifferenceWon)
        assertTrue(requireNotNull(result.remainingAverageRate) > requireNotNull(result.cumulativeAverageRate))
    }

    @Test fun cashAndForeignCardConsumeBalanceButWonCardDoesNot() {
        val expenses = listOf(expense(1, "10", 1), expense(2, "20", 2, ExchangePaymentMethod.FOREIGN_CARD),
            expense(3, "30", 3, ExchangePaymentMethod.KRW_CARD, "31500"))
        val quote = ExchangeQuote(firstDay.plusDays(4).atTime(12, 0), 1, decimal("1100"))
        val result = analyzeExchange(ExchangeCurrency.USD, listOf(record(1, "100000", "1000")), expenses, listOf(quote))
        money("70000", result.remainingCostWon)
        money("77000", result.currentValueWon)
        money("61500", result.knownExpenseWon)
        money("30000", result.expenseAnalyses.getValue(3).foreignCostWon)
        money("31500", result.expenseAnalyses.getValue(3).actualWonAmount)
        money("1050", result.expenseAnalyses.getValue(3).cardEffectiveRate)
        assertEquals(0, result.unknownExpenseCostCount)
    }

    @Test fun missingDatesKeepCumulativeReferenceButDoNotInventHistoricalCost() {
        val legacyRecord = record(1, "100000", "1000").copy(exchangeDate = null)
        val legacyExpense = expense(1, "10", 1, method = null)
        val pendingCard = expense(2, "10", 2, ExchangePaymentMethod.KRW_CARD)
        val result = analyzeExchange(ExchangeCurrency.USD, listOf(legacyRecord), listOf(legacyExpense, pendingCard), emptyList())
        assertTrue(legacyExpense.usesForeignBalance)
        money("1000", result.cumulativeAverageRate)
        assertNull(result.remainingAverageRate)
        assertNull(result.remainingCostWon)
        assertNull(result.expenseAnalyses.getValue(1).actualWonAmount)
        assertNull(result.expenseAnalyses.getValue(2).actualWonAmount)
        assertEquals(2, result.unknownExpenseCostCount)
        assertNotNull(result.basisIssue)
    }

    @Test fun sameDayOrderRequiresTimesAndRejectsIdenticalExchangeAndExpenseTime() {
        val purchase = record(1, "100000", "1000")
        val spent = expense(1, "10", 0)
        val unknown = analyzeExchange(ExchangeCurrency.USD, listOf(purchase), listOf(spent), emptyList())
        assertNull(unknown.remainingCostWon)
        val ordered = analyzeExchange(ExchangeCurrency.USD, listOf(purchase.copy(exchangeTime = LocalTime.of(9, 0))),
            listOf(spent.copy(expenseTime = LocalTime.of(10, 0))), emptyList())
        money("10000", ordered.expenseAnalyses.getValue(1).actualWonAmount)
        money("90000", ordered.remainingCostWon)
        val tied = analyzeExchange(ExchangeCurrency.USD, listOf(purchase.copy(exchangeTime = LocalTime.of(10, 0))),
            listOf(spent.copy(expenseTime = LocalTime.of(10, 0))), emptyList())
        assertNull(tied.remainingCostWon)
    }

    @Test fun historicalQuoteNeverUsesAQuoteAfterTheSpecifiedTime() {
        val quotes = listOf(
            ExchangeQuote(LocalDateTime.of(2026, 10, 2, 10, 0), 1, decimal("900")),
            ExchangeQuote(LocalDateTime.of(2026, 10, 2, 14, 0), 2, decimal("950")),
        )
        val expenses = listOf(
            expense(1, "2000", 1).copy(expenseTime = LocalTime.of(12, 0)),
            expense(2, "2000", 1), expense(3, "2000", 2), expense(4, "2000", 0),
        )
        val result = analyzeExchange(ExchangeCurrency.JPY, emptyList(), expenses, quotes)
        money("18000", result.expenseAnalyses.getValue(1).marketWonAmount)
        money("19000", result.expenseAnalyses.getValue(2).marketWonAmount)
        assertEquals(quotes.last(), result.expenseAnalyses.getValue(3).quote)
        assertNull(result.expenseAnalyses.getValue(4).quote)
    }

    @Test fun insufficientBalanceDoesNotManufactureCostButKeepsConfirmedCardBill() {
        val expenses = listOf(expense(1, "101", 1), expense(2, "10", 2, ExchangePaymentMethod.KRW_CARD, "12000"))
        val result = analyzeExchange(ExchangeCurrency.USD, listOf(record(1, "100000", "1000")), expenses, emptyList())
        assertNull(result.remainingCostWon)
        assertNull(result.expenseAnalyses.getValue(1).actualWonAmount)
        money("12000", result.knownExpenseWon)
        assertEquals(1, result.unknownExpenseCostCount)
    }

    @Test fun spendingEntireBalanceLeavesNoRoundingCostBehind() {
        val purchase = record(1, "100000", "1333.33")
        val quantity = exchangedForeignAmount(purchase, ExchangeCurrency.USD)
        val spent = expense(1, quantity.toPlainString(), 1)
        val result = analyzeExchange(ExchangeCurrency.USD, listOf(purchase), listOf(spent), emptyList())
        money("100000", result.expenseAnalyses.getValue(1).actualWonAmount)
        money("0", result.remainingCostWon)
        assertNull(result.remainingAverageRate)
    }
}
