package com.habittracker.ui.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.CardHistoryEntity
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.YearMonth

class CardHistoryViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val paymentDay = MutableStateFlow(repository.getCardPaymentDay())
    private val selectedMonth = MutableStateFlow(billingCycleMonth(LocalDate.now(), paymentDay.value))
    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<CardHistoryUiState> = combine(
        repository.observeCardHistories(),
        selectedMonth,
        paymentDay,
        statusMessage,
    ) { histories, month, day, message ->
        CardHistoryUiState(
            selectedMonth = month,
            paymentDay = day,
            histories = histories.sortedWith(compareByDescending<CardHistoryEntity> { it.useDate }.thenByDescending { it.id }),
            recentHistories = histories
                .filter { billingCycleMonth(it.useDate, day) == month }
                .sortedWith(compareByDescending<CardHistoryEntity> { it.useDate }.thenByDescending { it.id }),
            threeMonthSeries = buildRollingSeries(histories, month, day),
            yearComparisonSeries = buildYearComparisonSeries(histories, month, day),
            statusMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CardHistoryUiState(),
    )

    init {
        viewModelScope.launch {
            repository.seedCardHistoriesIfEmpty()
            paymentDay.value = repository.getCardPaymentDay()
        }
    }

    fun goToPreviousMonth() {
        selectedMonth.value = selectedMonth.value.minusMonths(1)
    }

    fun goToNextMonth() {
        selectedMonth.value = selectedMonth.value.plusMonths(1)
    }

    fun updatePaymentDay(value: String) {
        val parsed = value.filter(Char::isDigit).toIntOrNull()
        if (parsed == null || parsed !in 1..28) {
            statusMessage.value = "결제일은 1일부터 28일 사이로 입력해 주세요."
            return
        }
        repository.saveCardPaymentDay(parsed)
        paymentDay.value = parsed
        statusMessage.value = "결제일 기준이 저장되었습니다."
    }

    fun saveHistory(useDate: String, amount: String, memo: String, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            runCatching {
                val parsedUseDate = LocalDate.parse(useDate)
                repository.saveCardHistory(
                    useDate = parsedUseDate,
                    amount = amount.filter(Char::isDigit).toLongOrNull() ?: 0L,
                    memo = memo,
                )
            }.onSuccess {
                selectedMonth.value = billingCycleMonth(LocalDate.parse(useDate), paymentDay.value)
                statusMessage.value = "카드 이력이 저장되었습니다."
                onSuccess?.invoke()
            }.onFailure { error ->
                statusMessage.value = error.message ?: "카드 이력 저장에 실패했습니다."
            }
        }
    }

    fun deleteHistory(historyId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteCardHistory(historyId) }
                .onSuccess { statusMessage.value = "카드 이력을 삭제했습니다." }
                .onFailure { error -> statusMessage.value = error.message ?: "카드 이력 삭제에 실패했습니다." }
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }
}

data class CardHistoryUiState(
    val selectedMonth: YearMonth = billingCycleMonth(LocalDate.now(), 9),
    val paymentDay: Int = 9,
    val histories: List<CardHistoryEntity> = emptyList(),
    val recentHistories: List<CardHistoryEntity> = emptyList(),
    val threeMonthSeries: List<CardMonthSeries> = emptyList(),
    val yearComparisonSeries: List<CardMonthSeries> = emptyList(),
    val statusMessage: String? = null,
)

data class CardTopSummary(
    val currentAmount: Long = 0L,
    val currentDate: LocalDate? = null,
    val averageDate: LocalDate = LocalDate.now(),
    val sameDayYearAverage: Long = 0L,
    val sameDayYearAverageCount: Int = 0,
)

data class CardMonthSeries(
    val label: String,
    val points: List<CardChartPoint>,
    val cycleLength: Int,
)

data class CardChartPoint(
    val dayIndex: Int,
    val amount: Long,
)

private fun buildRollingSeries(histories: List<CardHistoryEntity>, selectedMonth: YearMonth, paymentDay: Int): List<CardMonthSeries> =
    (0..2).map { offset -> selectedMonth.minusMonths(offset.toLong()) }
        .distinct()
        .sorted()
        .map { month -> month.toSeries(histories, paymentDay) }
        .filter { it.points.isNotEmpty() }

private fun buildYearComparisonSeries(histories: List<CardHistoryEntity>, selectedMonth: YearMonth, paymentDay: Int): List<CardMonthSeries> =
    listOf(
        selectedMonth.toSeries(histories, paymentDay),
        selectedMonth.minusYears(1).toSeries(histories, paymentDay),
    ).filter { it.points.isNotEmpty() }

private fun YearMonth.toSeries(histories: List<CardHistoryEntity>, paymentDay: Int): CardMonthSeries {
    val cycleStartDate = cycleStart(paymentDay)
    val cycleEndDate = cycleEnd(paymentDay)
    val monthHistories = histories
        .filter { !it.useDate.isBefore(cycleStartDate) && !it.useDate.isAfter(cycleEndDate) }
        .sortedBy(CardHistoryEntity::useDate)

    val points = monthHistories.map { history ->
        CardChartPoint(
            dayIndex = ChronoUnit.DAYS.between(cycleStartDate, history.useDate).toInt() + 1,
            amount = history.amount,
        )
    }.sortedBy(CardChartPoint::dayIndex)

    return CardMonthSeries(
        label = "${year}년 ${monthValue}월",
        points = points,
        cycleLength = ChronoUnit.DAYS.between(cycleStartDate, cycleEndDate).toInt() + 1,
    )
}

private fun billingCycleMonth(date: LocalDate, paymentDay: Int): YearMonth =
    if (date.dayOfMonth <= paymentDay) YearMonth.from(date) else YearMonth.from(date).plusMonths(1)

private fun YearMonth.cycleStart(paymentDay: Int): LocalDate =
    minusMonths(1).atDay(paymentDay + 1)

private fun YearMonth.cycleEnd(paymentDay: Int): LocalDate =
    atDay(paymentDay.coerceAtMost(lengthOfMonth()))
