package com.habittracker.ui.exchange

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.exchange.ExchangeCurrency
import com.habittracker.data.exchange.ExchangeQuote
import com.habittracker.data.exchange.ExchangeRateSource
import com.habittracker.data.exchange.ExchangeRecord
import com.habittracker.data.exchange.ExchangeRepository
import com.habittracker.data.exchange.ExchangeCollectionSettings
import com.habittracker.data.exchange.ExchangeExpense
import com.habittracker.data.exchange.ExchangeBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

enum class ExchangeEntryType { EXCHANGE, EXPENSE }

enum class ExchangeChartPeriod(val label: String) {
    ALL("고시별"), TODAY("오늘"), DAILY("일별"), MONTHLY("월별");
}

data class ExchangeChartState(
    val currency: ExchangeCurrency = ExchangeCurrency.USD,
    val period: ExchangeChartPeriod = ExchangeChartPeriod.ALL,
    val quotes: List<ExchangeQuote> = emptyList(),
    val isLoaded: Boolean = false,
    val today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")),
)

private data class ExchangeChartSeries(
    val currency: ExchangeCurrency,
    val all: List<ExchangeQuote>,
    val daily: List<ExchangeQuote>,
    val monthly: List<ExchangeQuote>,
    val isLoaded: Boolean,
)

data class ExchangeUiState(
    val currency: ExchangeCurrency = ExchangeCurrency.USD,
    val quotes: List<ExchangeQuote> = emptyList(),
    val records: List<ExchangeRecord> = emptyList(),
    val expenses: List<ExchangeExpense> = emptyList(),
    val balances: List<ExchangeBalance> = emptyList(),
    val isLoaded: Boolean = false,
    val loadError: String? = null,
    val isRefreshing: Boolean = false,
    val isBackfilling: Boolean = false,
    val backfillError: String? = null,
    val isRecollecting: Boolean = false,
    val recollectionError: String? = null,
    val isSaving: Boolean = false,
    val isUpdatingCollection: Boolean = false,
    val refreshError: String? = null,
    val message: String? = null,
)

data class ExchangeEditorState(
    val wonAmount: String = "",
    val appliedRate: String = "",
    val id: Long? = null,
    val initialAmount: String = "",
    val initialRate: String = "",
) {
    val hasChanges: Boolean get() = wonAmount != initialAmount || appliedRate != initialRate
}

data class ExchangeExpenseEditorState(
    val foreignAmount: String = "",
    val description: String = "",
    val id: Long? = null,
    val initialAmount: String = "",
    val initialDescription: String = "",
    val expenseDate: LocalDate? = null,
    val initialDate: LocalDate? = null,
) {
    val hasChanges: Boolean get() = foreignAmount != initialAmount || description != initialDescription || expenseDate != initialDate
}

private data class ExchangeLedger(
    val records: Map<ExchangeCurrency, List<ExchangeRecord>> = emptyMap(),
    val expenses: Map<ExchangeCurrency, List<ExchangeExpense>> = emptyMap(),
    val balances: List<ExchangeBalance> = emptyList(),
    val isLoaded: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangeViewModel(
    private val repository: ExchangeRepository,
    private val collectionSettings: ExchangeCollectionSettings,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val collectionIntervalMinutes = collectionSettings.intervalMinutes
    val chartPeriod = savedState.getStateFlow("chartPeriod", ExchangeChartPeriod.ALL.name)
    val recollectionStartDate = savedState.getStateFlow<Long?>("recollectionStartDate", null)
        .map { it?.let(LocalDate::ofEpochDay) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val currency = savedState.getStateFlow("currency",
        ExchangeCurrency.entries.firstOrNull { it.name == savedState.get<String>("widgetCurrency") }?.name ?: ExchangeCurrency.USD.name)
    val entryType = savedState.getStateFlow("entryType", ExchangeEntryType.EXCHANGE.name)
    private val actions = MutableStateFlow(ExchangeUiState())
    val editor = combine(
        savedState.getStateFlow("amount", ""), savedState.getStateFlow("rate", ""),
        savedState.getStateFlow<Long?>("recordId", null),
        savedState.getStateFlow("initialAmount", ""), savedState.getStateFlow("initialRate", ""),
    ) { amount, rate, id, initialAmount, initialRate ->
        ExchangeEditorState(amount, rate, id, initialAmount, initialRate)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExchangeEditorState())

    private val expenseFields = combine(
        savedState.getStateFlow("expenseAmount", ""), savedState.getStateFlow("expenseDescription", ""),
        savedState.getStateFlow<Long?>("expenseId", null),
        savedState.getStateFlow("initialExpenseAmount", ""), savedState.getStateFlow("initialExpenseDescription", ""),
    ) { amount, description, id, initialAmount, initialDescription ->
        ExchangeExpenseEditorState(amount, description, id, initialAmount, initialDescription)
    }
    val expenseEditor = combine(
        expenseFields,
        savedState.getStateFlow<Long?>("expenseDate", null),
        savedState.getStateFlow<Long?>("initialExpenseDate", null),
    ) { fields, date, initialDate ->
        fields.copy(expenseDate = date?.let(LocalDate::ofEpochDay), initialDate = initialDate?.let(LocalDate::ofEpochDay))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExchangeExpenseEditorState())

    private val ledger = combine(repository.observeAllRecords(), repository.observeAllExpenses()) { records, expenses ->
        val recordGroups = records.groupBy { ExchangeCurrency.valueOf(it.currency) }.mapValues { (_, rows) ->
            rows.map { ExchangeRecord(it.id, it.wonAmount.toBigDecimal(), it.appliedRate.toBigDecimal()) }
        }
        val expenseGroups = expenses.groupBy { ExchangeCurrency.valueOf(it.currency) }.mapValues { (_, rows) ->
            rows.map { ExchangeExpense(it.id, it.foreignAmount.toBigDecimal(), it.description, it.expenseDate) }
        }
        val balances = ExchangeCurrency.entries.map { currency ->
            val exchanged = recordGroups[currency].orEmpty().fold(BigDecimal.ZERO) { total, record ->
                total.add(record.wonAmount.multiply(currency.unit.toBigDecimal()).divide(record.appliedRate, 8, RoundingMode.HALF_UP))
            }
            val spent = expenseGroups[currency].orEmpty().fold(BigDecimal.ZERO) { total, expense -> total.add(expense.foreignAmount) }
            ExchangeBalance(currency, exchanged, spent)
        }
        ExchangeLedger(recordGroups, expenseGroups, balances, isLoaded = true)
    }.flowOn(Dispatchers.Default).catch { error ->
        if (error is CancellationException) throw error
        Log.e("Exchange", "환전·지출 기록 및 통화별 잔액 조회 실패", error)
        emit(ExchangeLedger(error = "환전·지출 기록 및 잔액을 불러오지 못했습니다."))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExchangeLedger())

    private val data = currency.flatMapLatest { code ->
        val selected = ExchangeCurrency.valueOf(code)
        combine(repository.observeRates(selected), ledger) { rates, ledgerState ->
            ExchangeUiState(
                currency = selected,
                quotes = rates.map { ExchangeQuote(it.quotedAt, it.quoteRound, it.rate.toBigDecimal(), ExchangeRateSource.valueOf(it.source)) },
                records = ledgerState.records[selected].orEmpty(),
                expenses = ledgerState.expenses[selected].orEmpty(),
                balances = ledgerState.balances,
                isLoaded = ledgerState.isLoaded,
                loadError = ledgerState.error,
            )
        }.catch { error ->
            if (error is CancellationException) throw error
            Log.e("Exchange", "환전 기록 및 수집 환율 조회 실패: currency=$selected", error)
            emit(ExchangeUiState(currency = selected, loadError = "환전 기록 및 수집 환율을 불러오지 못했습니다."))
        }
    }
    val uiState = combine(data, actions) { values, action ->
        values.copy(isRefreshing = action.isRefreshing, isSaving = action.isSaving,
            isBackfilling = action.isBackfilling, backfillError = action.backfillError,
            isRecollecting = action.isRecollecting, recollectionError = action.recollectionError,
            isUpdatingCollection = action.isUpdatingCollection,
            refreshError = action.refreshError, message = action.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExchangeUiState())

    private val chartSeries = uiState.map { Triple(it.currency, it.quotes, it.isLoaded) }
        .distinctUntilChanged()
        .map { (currency, quotes, loaded) ->
            val daily = linkedMapOf<LocalDate, ExchangeQuote>()
            val monthly = linkedMapOf<YearMonth, ExchangeQuote>()
            // DAO가 고시 시각·회차 오름차순으로 반환하므로 같은 기간의 마지막 값만 남긴다.
            quotes.forEach { quote ->
                daily[quote.quotedAt.toLocalDate()] = quote
                monthly[YearMonth.from(quote.quotedAt)] = quote
            }
            ExchangeChartSeries(currency, quotes, daily.values.toList(), monthly.values.toList(), loaded)
        }.flowOn(Dispatchers.Default)

    private val chartToday = flow {
        val zone = ZoneId.of("Asia/Seoul")
        while (true) {
            val now = ZonedDateTime.now(zone)
            emit(now.toLocalDate())
            val nextDay = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, nextDay).toMillis().coerceAtLeast(1L))
        }
    }

    val chartState = combine(chartSeries, chartPeriod, chartToday) { series, periodCode, today ->
        val period = ExchangeChartPeriod.valueOf(periodCode)
        val quotes = when (period) {
            ExchangeChartPeriod.ALL -> series.all
            ExchangeChartPeriod.TODAY -> series.all.filter { it.quotedAt.toLocalDate() == today }
            ExchangeChartPeriod.DAILY -> series.daily
            ExchangeChartPeriod.MONTHLY -> series.monthly
        }
        ExchangeChartState(series.currency, period, quotes, series.isLoaded, today)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExchangeChartState())

    fun selectChartPeriod(period: ExchangeChartPeriod) { savedState["chartPeriod"] = period.name }
    fun selectCurrency(code: String) { savedState["currency"] = ExchangeCurrency.valueOf(code).name; clearInputs() }
    fun selectEntryType(type: ExchangeEntryType) { clearInputs(); savedState["entryType"] = type.name }
    fun updateExpenseAmount(value: String) { savedState["expenseAmount"] = value }
    fun updateExpenseDescription(value: String) { savedState["expenseDescription"] = value }
    fun updateExpenseDate(value: LocalDate) { savedState["expenseDate"] = value.toEpochDay() }
    fun updateAmount(value: String) { savedState["amount"] = value }
    fun updateRate(value: String) { savedState["rate"] = value }
    fun clearMessage() { actions.update { it.copy(message = null) } }
    fun saveCollectionInterval(minutes: Long) {
        if (actions.value.isUpdatingCollection) return
        actions.update { it.copy(isUpdatingCollection = true) }
        viewModelScope.launch {
            try {
                collectionSettings.save(minutes)
                actions.update { it.copy(message = if (minutes == 0L) "환율 자동 수집을 중단했습니다." else "환율 자동 수집 주기를 ${minutes}분으로 설정했습니다.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "환율 수집 주기 설정 실패: intervalMinutes=$minutes", error)
                actions.update { it.copy(message = error.message ?: "환율 수집 주기 설정에 실패했습니다.") }
            } finally {
                actions.update { it.copy(isUpdatingCollection = false) }
            }
        }
    }
    fun clearEditor() {
        savedState["amount"] = ""; savedState["rate"] = ""; savedState["recordId"] = null
        savedState["initialAmount"] = ""; savedState["initialRate"] = ""
    }
    fun edit(record: ExchangeRecord) {
        clearExpenseEditor()
        savedState["entryType"] = ExchangeEntryType.EXCHANGE.name
        savedState["amount"] = record.wonAmount.toPlainString()
        savedState["rate"] = record.appliedRate.toPlainString()
        savedState["initialAmount"] = record.wonAmount.toPlainString()
        savedState["initialRate"] = record.appliedRate.toPlainString()
        savedState["recordId"] = record.id
    }

    fun clearExpenseEditor() {
        savedState["expenseAmount"] = ""; savedState["expenseDescription"] = ""; savedState["expenseId"] = null
        savedState["initialExpenseAmount"] = ""; savedState["initialExpenseDescription"] = ""
        savedState["expenseDate"] = null; savedState["initialExpenseDate"] = null
    }
    fun clearInputs() { clearEditor(); clearExpenseEditor() }
    fun editExpense(expense: ExchangeExpense) {
        clearEditor()
        savedState["entryType"] = ExchangeEntryType.EXPENSE.name
        savedState["expenseAmount"] = expense.foreignAmount.toPlainString()
        savedState["expenseDescription"] = expense.description
        savedState["initialExpenseAmount"] = expense.foreignAmount.toPlainString()
        savedState["initialExpenseDescription"] = expense.description
        savedState["expenseDate"] = expense.expenseDate?.toEpochDay()
        savedState["initialExpenseDate"] = expense.expenseDate?.toEpochDay()
        savedState["expenseId"] = expense.id
    }

    fun saveExpense() {
        if (actions.value.isSaving) return
        val amount = positiveDecimal(savedState.get<String>("expenseAmount").orEmpty())
        val description = savedState.get<String>("expenseDescription").orEmpty().trim()
        val expenseDate = savedState.get<Long>("expenseDate")?.let(LocalDate::ofEpochDay)
        if (amount == null || description.isEmpty()) {
            actions.update { it.copy(message = "0보다 큰 외화 금액과 지출 내용을 입력해 주세요.") }
            return
        }
        val id = savedState.get<Long>("expenseId")
        val selected = ExchangeCurrency.valueOf(currency.value)
        actions.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                repository.saveExpense(id, selected, amount, description, expenseDate)
                clearExpenseEditor()
                actions.update { it.copy(message = "지출 기록을 저장했습니다.") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "지출 기록 저장 실패: id=$id currency=$selected", error)
                actions.update { it.copy(message = error.message ?: "지출 기록 저장에 실패했습니다.") }
            } finally { actions.update { it.copy(isSaving = false) } }
        }
    }

    fun deleteExpense(id: Long) {
        if (actions.value.isSaving) return
        actions.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                repository.deleteExpense(id)
                if (savedState.get<Long>("expenseId") == id) clearExpenseEditor()
                actions.update { it.copy(message = "지출 기록을 삭제했습니다.") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "지출 기록 삭제 실패: id=$id", error)
                actions.update { it.copy(message = error.message ?: "지출 기록 삭제에 실패했습니다.") }
            } finally { actions.update { it.copy(isSaving = false) } }
        }
    }

    fun refresh() {
        if (actions.value.isRefreshing || actions.value.isBackfilling || actions.value.isRecollecting) return
        actions.update { it.copy(isRefreshing = true, refreshError = null) }
        viewModelScope.launch {
            try {
                repository.refresh()
                actions.update { it.copy(message = "신한은행 환율을 갱신했습니다.") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "신한은행 USD/JPY 매매기준율 수집 실패", error)
                actions.update { it.copy(refreshError = error.message ?: "신한은행 환율 조회에 실패했습니다.") }
            } finally { actions.update { it.copy(isRefreshing = false) } }
        }
    }

    fun backfillMissingRates() {
        if (actions.value.isBackfilling || actions.value.isRefreshing || actions.value.isRecollecting) return
        actions.update { it.copy(isBackfilling = true, backfillError = null) }
        viewModelScope.launch {
            try {
                val count = repository.backfillMissingRates()
                actions.update { it.copy(message = if (count == 0) "네이버 제공 자료에서 보충할 고시를 찾지 못했습니다." else "네이버의 신한은행 고시 ${count}개를 보충했습니다.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "네이버 신한은행 USD/JPY 누락 구간 보충 실패", error)
                actions.update { it.copy(backfillError = error.message ?: "누락 환율을 보충하지 못했습니다.") }
            } finally {
                actions.update { it.copy(isBackfilling = false) }
            }
        }
    }

    fun updateRecollectionStartDate(value: LocalDate) { savedState["recollectionStartDate"] = value.toEpochDay() }
    fun clearRecollectionStartDate() { savedState["recollectionStartDate"] = null }

    fun recollectRates() {
        if (actions.value.isRecollecting || actions.value.isRefreshing || actions.value.isBackfilling) return
        val startDate = savedState.get<Long>("recollectionStartDate")?.let(LocalDate::ofEpochDay)
        if (startDate == null) {
            actions.update { it.copy(recollectionError = "시작 날짜를 선택해 주세요.") }
            return
        }
        actions.update { it.copy(isRecollecting = true, recollectionError = null) }
        viewModelScope.launch {
            try {
                val count = repository.recollectRates(startDate)
                clearRecollectionStartDate()
                actions.update { it.copy(message = "환율 고시 ${count}개로 이력을 다시 수집했습니다.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "USD/JPY 환율 이력 다시 수집 실패: start=$startDate", error)
                actions.update { it.copy(recollectionError = error.message ?: "환율 이력을 다시 수집하지 못했습니다.") }
            } finally {
                actions.update { it.copy(isRecollecting = false) }
            }
        }
    }

    fun save() {
        if (actions.value.isSaving) return
        val amount = positiveDecimal(savedState.get<String>("amount").orEmpty())
        val rate = positiveDecimal(savedState.get<String>("rate").orEmpty())
        if (amount == null || rate == null) {
            actions.update { it.copy(message = "금액과 당시 환율을 0보다 큰 숫자로 입력해 주세요.") }
            return
        }
        val id = savedState.get<Long>("recordId")
        val selected = ExchangeCurrency.valueOf(currency.value)
        actions.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                repository.save(id, selected, amount, rate)
                clearEditor()
                actions.update { it.copy(message = "환전 기록을 저장했습니다.") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "환전 기록 저장 실패: id=$id currency=$selected", error)
                actions.update { it.copy(message = error.message ?: "환전 기록 저장에 실패했습니다.") }
            } finally { actions.update { it.copy(isSaving = false) } }
        }
    }

    fun delete(id: Long) {
        if (actions.value.isSaving) return
        actions.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                repository.delete(id)
                if (savedState.get<Long>("recordId") == id) clearEditor()
                actions.update { it.copy(message = "환전 기록을 삭제했습니다.") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.e("Exchange", "환전 기록 삭제 실패: id=$id", error)
                actions.update { it.copy(message = error.message ?: "환전 기록 삭제에 실패했습니다.") }
            } finally { actions.update { it.copy(isSaving = false) } }
        }
    }
}

internal fun positiveDecimal(value: String): java.math.BigDecimal? = value.trim()
    .takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) }
    ?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
