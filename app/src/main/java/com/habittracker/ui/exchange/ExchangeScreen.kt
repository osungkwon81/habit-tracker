package com.habittracker.ui.exchange

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.exchange.ExchangeCurrency
import com.habittracker.data.exchange.ExchangeCollectionSettings
import com.habittracker.data.exchange.ExchangeQuote
import com.habittracker.data.exchange.ExchangeRateSource
import com.habittracker.data.exchange.ExchangeRecord
import com.habittracker.data.exchange.ExchangeExpense
import com.habittracker.data.exchange.ExchangeAnalytics
import com.habittracker.data.exchange.compareCardExpense
import com.habittracker.ui.components.AppActionNotice
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppButtonRow
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppLoadingCard
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.LocalAppNavigationGuard
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

enum class ExchangePage { RATES, RECORDS, COLLECTION_SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeScreen(
    viewModel: ExchangeViewModel,
    page: ExchangePage = ExchangePage.RATES,
    onOpenRates: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onOpenCollectionSettings: () -> Unit = {},
    initialEntryType: ExchangeEntryType? = null,
) {
    var menuEntryInitialized by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(viewModel, initialEntryType) {
        if (!menuEntryInitialized && initialEntryType != null) {
            viewModel.selectEntryType(initialEntryType)
            menuEntryInitialized = true
        }
    }
    val isRecordsPage = page == ExchangePage.RECORDS
    val isRatesPage = page == ExchangePage.RATES
    val isCollectionSettingsPage = page == ExchangePage.COLLECTION_SETTINGS
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val expenseEditor by viewModel.expenseEditor.collectAsStateWithLifecycle()
    val entryTypeCode by viewModel.entryType.collectAsStateWithLifecycle()
    val entryType = ExchangeEntryType.valueOf(entryTypeCode)
    val isExpenseEntry = entryType == ExchangeEntryType.EXPENSE
    val selectedCode by viewModel.currency.collectAsStateWithLifecycle()
    val collectionInterval by viewModel.collectionIntervalMinutes.collectAsStateWithLifecycle()
    val recollectionStartDate by viewModel.recollectionStartDate.collectAsStateWithLifecycle()
    val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    val validRecollectionStart = recollectionStartDate?.let { !it.isAfter(today) } == true
    var automaticCollection by rememberSaveable(collectionInterval) { mutableStateOf(collectionInterval > 0L) }
    var intervalInput by rememberSaveable(collectionInterval) {
        mutableStateOf(if (collectionInterval > 0L) collectionInterval.toString() else "")
    }
    val inputMinutes = intervalInput.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
    val validInterval = inputMinutes != null && inputMinutes > 0L && ExchangeCollectionSettings.isValidInterval(inputMinutes)
    val collectionHasChanges = automaticCollection != (collectionInterval > 0L) ||
        (automaticCollection && intervalInput.trim() != collectionInterval.toString())
    val discardCollectionChanges = {
        automaticCollection = collectionInterval > 0L
        intervalInput = if (collectionInterval > 0L) collectionInterval.toString() else ""
        viewModel.clearRecollectionStartDate()
    }
    val selected = ExchangeCurrency.valueOf(selectedCode)
    val guard = LocalAppNavigationGuard.current
    var deleteTarget by remember { mutableStateOf<ExchangeRecord?>(null) }
    var deleteExpenseTarget by remember { mutableStateOf<ExchangeExpense?>(null) }
    var showExpenseDatePicker by rememberSaveable { mutableStateOf(false) }
    var showExchangeDatePicker by rememberSaveable { mutableStateOf(false) }
    var showRecollectionDatePicker by rememberSaveable { mutableStateOf(false) }
    var confirmRecollection by rememberSaveable { mutableStateOf(false) }
    SideEffect {
        guard.hasUnsavedChanges = when (page) {
            ExchangePage.RECORDS -> editor.hasChanges || expenseEditor.hasChanges
            ExchangePage.COLLECTION_SETTINGS -> collectionHasChanges || (recollectionStartDate != null && !state.isRecollecting)
            ExchangePage.RATES -> false
        }
    }
    DisposableEffect(guard) { onDispose { guard.hasUnsavedChanges = false } }
    AppActionNotice(state.message, viewModel::clearMessage)
    val hasSelectedData = state.currency == selected
    val quotes = if (hasSelectedData) state.quotes else emptyList()
    val latest = quotes.lastOrNull()
    val amount = positiveDecimal(editor.wonAmount)
    val rate = positiveDecimal(editor.appliedRate)
    val expenseAmount = positiveDecimal(expenseEditor.foreignAmount)
    if (isCollectionSettingsPage && showRecollectionDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = recollectionStartDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showRecollectionDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            viewModel.updateRecollectionStartDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        showRecollectionDatePicker = false
                    },
                    enabled = datePickerState.selectedDateMillis != null && !state.isRecollecting,
                ) { Text("선택") }
            },
            dismissButton = { TextButton(onClick = { showRecollectionDatePicker = false }) { Text("취소") } },
        ) { DatePicker(state = datePickerState) }
    }
    if (isCollectionSettingsPage && confirmRecollection && recollectionStartDate != null) {
        AppConfirmDialog(
            title = "환율 이력을 다시 수집할까요?",
            message = "$recollectionStartDate ~ 오늘($today)의 달러·엔화 고시를 가져와 기존 환율 이력을 교체합니다. 네이버가 제공하지 않는 구간은 비어 있을 수 있습니다. 환전·지출 기록과 위젯 설정은 유지합니다.",
            confirmText = "다시 수집",
            onConfirm = { confirmRecollection = false; viewModel.recollectRates() },
            onDismiss = { confirmRecollection = false },
        )
    }
    if (isRecordsPage && ((isExpenseEntry && showExpenseDatePicker) || (!isExpenseEntry && showExchangeDatePicker))) {
        val inputDate = if (isExpenseEntry) expenseEditor.expenseDate else editor.exchangeDate
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = inputDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showExpenseDatePicker = false; showExchangeDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            if (isExpenseEntry) viewModel.updateExpenseDate(date) else viewModel.updateExchangeDate(date)
                        }
                        showExpenseDatePicker = false
                        showExchangeDatePicker = false
                    },
                    enabled = datePickerState.selectedDateMillis != null && !state.isSaving,
                ) { Text("선택") }
            },
            dismissButton = {
                TextButton(onClick = { showExpenseDatePicker = false; showExchangeDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
    deleteTarget?.let { record ->
        AppConfirmDialog(
            title = "환전 기록 삭제",
            message = "${record.wonAmount.money()}원 환전 기록을 삭제할까요? 삭제한 기록은 되돌릴 수 없습니다.",
            confirmText = "삭제",
            onConfirm = { viewModel.delete(record.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    deleteExpenseTarget?.let { expense ->
        AppConfirmDialog(
            title = "지출 기록 삭제",
            message = "${expense.description} · ${expense.foreignAmount.money()} ${selected.amountUnitLabel} 지출을 삭제할까요? " +
                if (expense.usesForeignBalance) "삭제하면 외화 잔액에 다시 반영됩니다." else "기존 별도 지출 집계에서 제외됩니다.",
            confirmText = "삭제",
            onConfirm = { viewModel.deleteExpense(expense.id); deleteExpenseTarget = null },
            onDismiss = { deleteExpenseTarget = null },
        )
    }
    AppScreen(bottomBar = if (isRecordsPage) { {
        AppSaveButton(
            onClick = if (isExpenseEntry) viewModel::saveExpense else viewModel::save,
            text = if (isExpenseEntry) "지출 저장" else "환전 저장",
            enabled = !state.isSaving && (if (isExpenseEntry) expenseAmount != null && expenseEditor.description.isNotBlank() &&
                expenseEditor.hasValidTime && (positiveDecimal(expenseEditor.cardWonAmount) != null || (expenseEditor.id != null && expenseEditor.cardWonAmount.isBlank()))
                else amount != null && rate != null && editor.hasValidTime),
            modifier = Modifier.fillMaxWidth(),
        )
    } } else null) {
        item {
            AppHeroCard(
                title = when (page) {
                    ExchangePage.RATES -> "환율"
                    ExchangePage.RECORDS -> "환전·지출"
                    ExchangePage.COLLECTION_SETTINGS -> "환율 수집 설정"
                },
                description = when (page) {
                    ExchangePage.RATES -> "신한은행 매매기준율과 수집 이력을 확인합니다."
                    ExchangePage.RECORDS -> "보유 외화의 원가와 평가차액, 결제수단별 지출 비용을 확인합니다."
                    ExchangePage.COLLECTION_SETTINGS -> "자동 수집 주기는 달러·엔화에 공통으로 적용됩니다."
                },
            )
        }
        if (!isCollectionSettingsPage) item {
            com.habittracker.ui.components.AppNavigationRow(
                title = if (isRecordsPage) "현재 환율" else "환전·지출 기록",
                onClick = {
                    guard.navigate {
                        if (isRecordsPage) {
                            viewModel.clearInputs()
                            onOpenRates()
                        } else {
                            discardCollectionChanges()
                            onOpenRecords()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving && !state.isUpdatingCollection,
            )
        }
        if (isRatesPage) item {
            com.habittracker.ui.components.AppNavigationRow(
                title = "환율 수집 설정",
                onClick = { guard.navigate { onOpenCollectionSettings() } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isUpdatingCollection,
            )
        }
        if (!isCollectionSettingsPage) item {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                ExchangeCurrency.entries.forEach { currency ->
                    AppSelectableChip(currency.label, selected == currency, onClick = {
                        if (selected != currency && !state.isSaving && !state.isUpdatingCollection) guard.navigate {
                            discardCollectionChanges()
                            viewModel.selectCurrency(currency.name)
                        }
                    })
                }
            }
        }
        if (isRecordsPage) item {
            AppSectionHeader("보유 금액")
            when {
                state.loadError != null -> Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                !state.isLoaded || !hasSelectedData -> AppLoadingCard("${selected.label} 금액을 불러오고 있습니다.")
                else -> Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    state.balances.filter { it.currency == selected }.forEach { balance ->
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            AppSupportText(balance.currency.amountUnitLabel)
                            ExchangeSummaryTable(
                                headers = listOf("환전 합계", "지출 합계", "남은 외화"),
                                values = listOf(balance.exchangedAmount.money(), balance.spentAmount.money(), balance.remainingAmount.money()),
                            )
                            if (balance.cardSpentAmount.signum() > 0) AppSupportText("기존 별도 지출 ${balance.cardSpentAmount.money()} ${balance.currency.amountUnitLabel} · 잔액 차감 없음")
                            if (balance.remainingAmount.signum() < 0) Text("외화 지출이 환전 합계보다 많습니다. 누락된 기록을 확인해 주세요.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    state.analytics?.let { ExchangePositionAnalysis(it, selected, latest, state.expenses.size) }
                }
            }
        }
        if (isRatesPage) {
        item {
            AppSectionHeader(title = "현재 고시환율", subtitle = selected.rateLabel)
            if (latest != null) {
                Text("${latest.rate.money()}원", style = MaterialTheme.typography.displaySmall)
                AppSupportText("고시 ${latest.quotedAt.format(quoteTimeFormat)} · ${latest.round}회차")
            }
            if (state.isRefreshing) AppSupportText("신한은행 환율을 조회하고 있습니다.")
            if (state.refreshError != null) {
                Text(state.refreshError.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (latest != null) AppSupportText("갱신에 실패하여 마지막으로 저장된 고시환율을 표시합니다.")
            } else if (state.loadError != null) {
                Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
            } else if (!state.isRefreshing && latest == null && state.isLoaded && hasSelectedData) {
                AppSupportText("아직 수집된 환율이 없습니다.")
            }
            AppSecondaryButton("새로고침", viewModel::refresh, enabled = !state.isRefreshing && !state.isBackfilling && !state.isRecollecting)
        }
        item {
            val chart by viewModel.chartState.collectAsStateWithLifecycle()
            val periodCode by viewModel.chartPeriod.collectAsStateWithLifecycle()
            val period = ExchangeChartPeriod.valueOf(periodCode)
            AppSectionHeader("환율 추이")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                ExchangeChartPeriod.entries.forEach { option ->
                    AppSelectableChip(option.label, period == option, onClick = { viewModel.selectChartPeriod(option) })
                }
            }
            AppSupportText(when (period) {
                ExchangeChartPeriod.ALL -> "저장된 모든 고시환율을 표시합니다."
                ExchangeChartPeriod.TODAY -> "${chart.today} · 한국 시간 기준 오늘 저장된 고시환율입니다."
                ExchangeChartPeriod.DAILY -> "각 날짜의 마지막 저장 고시환율을 표시합니다."
                ExchangeChartPeriod.MONTHLY -> "각 월의 마지막 저장 고시환율을 표시합니다."
            })
            when {
                state.loadError != null -> Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                !hasSelectedData || !chart.isLoaded || chart.currency != selected || chart.period != period -> AppLoadingCard("수집 환율을 불러오고 있습니다.")
                chart.quotes.isEmpty() -> AppEmptyCard(if (period == ExchangeChartPeriod.TODAY) "오늘 저장된 고시환율이 없습니다." else "수집한 환율이 쌓이면 변화를 볼 수 있습니다.")
                else -> ExchangeRateChart(chart.quotes, selected, period)
            }
        }
        }
        if (isCollectionSettingsPage) {
        item {
            AppSectionCard {
                AppSectionHeader("환율 수집 주기")
                AppSupportText(if (collectionInterval == 0L) "현재 설정: 자동 수집 꺼짐" else "현재 설정: ${collectionInterval}분 간격")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("자동 수집", modifier = Modifier.weight(1f))
                    Switch(checked = automaticCollection, onCheckedChange = { automaticCollection = it }, enabled = !state.isUpdatingCollection)
                }
                if (automaticCollection) {
                    AppTextField(
                        value = intervalInput, onValueChange = { intervalInput = it },
                        label = "수집 간격(분)", singleLine = true, enabled = !state.isUpdatingCollection,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = intervalInput.isNotBlank() && !validInterval,
                        supportingText = if (intervalInput.isNotBlank() && !validInterval) "최소 ${ExchangeCollectionSettings.minimumIntervalMinutes}분 이상의 정수를 입력해 주세요." else "예: 60분 = 1시간, 1440분 = 하루",
                    )
                }
                AppSecondaryButton(
                    text = if (state.isUpdatingCollection) "수집 설정 적용 중" else "수집 설정 적용",
                    onClick = { viewModel.saveCollectionInterval(if (automaticCollection) checkNotNull(inputMinutes) else 0L) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isUpdatingCollection && (!automaticCollection || validInterval),
                )
                AppSupportText("평일에 달러·엔화를 함께 수집합니다. 절전·네트워크 상태에 따라 수집이 늦어질 수 있습니다.")
            }
        }
        item {
            AppSectionHeader("환율 이력 다시 수집", "네이버 · 신한은행")
            AppSecondaryButton(
                text = recollectionStartDate?.let { "시작 날짜: $it · 달력" } ?: "시작 날짜 선택 · 달력",
                onClick = { showRecollectionDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRecollecting && !state.isBackfilling && !state.isRefreshing,
            )
            AppSupportText("종료 날짜: 오늘($today) · 한국 시간 기준")
            if (recollectionStartDate != null && !validRecollectionStart) {
                Text("시작 날짜는 오늘 이전 또는 오늘로 선택해 주세요.", color = MaterialTheme.colorScheme.error)
            }
            AppSupportText("선택한 시작일부터 오늘까지 제공되는 고시로 환율 이력을 교체합니다. 환전·지출 기록과 위젯 설정은 유지합니다.")
            AppSecondaryButton(
                text = if (state.isRecollecting) "환율 이력 다시 수집 중" else "이력 교체하고 다시 수집",
                onClick = { confirmRecollection = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = validRecollectionStart && !state.isRecollecting && !state.isBackfilling && !state.isRefreshing,
            )
            if (state.isRecollecting) AppSupportText("선택 구간의 고시를 조회하고 있습니다. 조회가 끝난 뒤 기존 이력을 교체합니다.")
            if (state.recollectionError != null) Text(state.recollectionError.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        item {
            AppSectionHeader("누락 환율 보충", "네이버 · 신한은행")
            AppSupportText("달러·엔화 각각 처음과 마지막으로 저장한 고시 사이에서 빠진 고시만 가져옵니다. 기존 기록은 유지하며 네이버가 제공하지 않는 구간은 그대로 남습니다.")
            AppSecondaryButton(
                text = if (state.isBackfilling) "누락 고시 보충 중" else "빠진 고시 가져오기",
                onClick = viewModel::backfillMissingRates,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBackfilling && !state.isRefreshing && !state.isRecollecting,
            )
            if (state.isBackfilling) AppSupportText("저장 구간의 과거 고시를 조회하고 있습니다.")
            if (state.backfillError != null) Text(state.backfillError.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        }
        if (isRecordsPage) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                ExchangeEntryType.entries.forEach { type ->
                    AppSelectableChip(if (type == ExchangeEntryType.EXCHANGE) "환전 입력" else "지출 입력", entryType == type, onClick = {
                        if (entryType != type && !state.isSaving) guard.navigate { viewModel.selectEntryType(type) }
                    })
                }
            }
        }
        if (isExpenseEntry) {
        item {
            AppSectionCard {
                if (expenseEditor.id != null) AppSupportText("지출 기록 수정 중")
                if (expenseEditor.id == null) AppSupportText("신한 트래블카드")
                AppSecondaryButton(
                    text = expenseEditor.expenseDate?.let { "지출 날짜: $it · 달력" } ?: "지출 날짜 선택 · 달력",
                    onClick = { showExpenseDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                )
                AppTextField(
                    value = expenseEditor.foreignAmount, onValueChange = viewModel::updateExpenseAmount,
                    label = "${selected.label} 결제액(${selected.amountUnitLabel})", singleLine = true,
                    enabled = !state.isSaving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = expenseEditor.foreignAmount.isNotBlank() && expenseAmount == null,
                    supportingText = if (expenseEditor.foreignAmount.isNotBlank() && expenseAmount == null) "0보다 큰 외화 금액을 입력해 주세요." else null,
                )
                AppTextField(
                    value = expenseEditor.cardWonAmount, onValueChange = viewModel::updateCardWonAmount,
                    label = "카드 표시 원화 환산액(원)", singleLine = true, enabled = !state.isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = expenseEditor.cardWonAmount.isNotBlank() && positiveDecimal(expenseEditor.cardWonAmount) == null,
                    supportingText = if (expenseEditor.cardWonAmount.isNotBlank() && positiveDecimal(expenseEditor.cardWonAmount) == null)
                        "0보다 큰 원화 금액을 입력해 주세요." else null,
                )
                if (expenseEditor.expenseTime.isNotBlank() || state.analytics?.basisIssue?.contains("순서") == true) {
                    AppTextField(value = expenseEditor.expenseTime, onValueChange = viewModel::updateExpenseTime,
                        label = "거래 시각(HH:mm)", singleLine = true, enabled = !state.isSaving,
                        isError = !expenseEditor.hasValidTime, supportingText = "같은 날 환전·지출의 순서를 확인할 때 입력하세요.")
                }
                CardExpenseComparison(expenseAmount, positiveDecimal(expenseEditor.cardWonAmount),
                    state.analytics?.takeIf { hasSelectedData }?.cumulativeAverageRate, selected)
                AppTextField(
                    value = expenseEditor.description, onValueChange = viewModel::updateExpenseDescription,
                    label = "지출 내용(필수)", enabled = !state.isSaving,
                    isError = expenseEditor.description.isNotEmpty() && expenseEditor.description.isBlank(),
                    supportingText = if (expenseEditor.description.isNotEmpty() && expenseEditor.description.isBlank()) "지출 내용을 입력해 주세요." else null,
                )
                if (expenseEditor.id != null) AppSecondaryButton("수정 취소", {
                    guard.navigate { viewModel.clearExpenseEditor() }
                }, enabled = !state.isSaving)
                if (state.isSaving) AppSupportText("지출 기록을 저장하고 있습니다.")
            }
        }
        } else {
        item {
            AppSectionCard {
                if (editor.id != null) AppSupportText("환전 기록 수정 중")
                AppSecondaryButton(
                    text = editor.exchangeDate?.let { "환전 날짜: $it · 달력" } ?: "환전 날짜 선택 · 달력",
                    onClick = { showExchangeDatePicker = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.isSaving,
                )
                AppTextField(
                    value = editor.wonAmount, onValueChange = viewModel::updateAmount,
                    label = "환전에 쓴 원화 금액(원)", singleLine = true, enabled = !state.isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = editor.wonAmount.isNotBlank() && amount == null,
                    supportingText = if (editor.wonAmount.isNotBlank() && amount == null) "0보다 큰 숫자를 입력해 주세요." else null,
                )
                AppTextField(
                    value = editor.appliedRate, onValueChange = viewModel::updateRate,
                    label = "당시 실제 적용 환율(${selected.rateLabel})", singleLine = true, enabled = !state.isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = editor.appliedRate.isNotBlank() && rate == null,
                    supportingText = if (editor.appliedRate.isNotBlank() && rate == null) "0보다 큰 숫자를 입력해 주세요." else null,
                )
                if (amount != null && rate != null) {
                    Comparison(amount, rate, latest?.rate, selected)
                }
                if (editor.exchangeTime.isNotBlank() || state.analytics?.basisIssue?.contains("순서") == true) {
                    AppTextField(value = editor.exchangeTime, onValueChange = viewModel::updateExchangeTime,
                        label = "거래 시각(HH:mm)", singleLine = true, enabled = !state.isSaving,
                        isError = !editor.hasValidTime, supportingText = "같은 날 환전·지출의 순서를 확인할 때 입력하세요.")
                }
                if (editor.id != null) AppSecondaryButton("수정 취소", {
                    guard.navigate { discardCollectionChanges(); viewModel.clearEditor() }
                }, enabled = !state.isSaving)
                if (state.isSaving) AppSupportText("환전 기록을 저장하고 있습니다.")
            }
        }
        }
        if (!isExpenseEntry) {
        item { AppSectionHeader("환전 기록") }
        when {
            !hasSelectedData || (!state.isLoaded && state.loadError == null) -> item { AppLoadingCard("환전 기록을 불러오고 있습니다.") }
            state.loadError != null -> item { Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error) }
            state.records.isEmpty() -> item { AppEmptyCard("저장된 환전 기록이 없습니다.") }
            else -> items(state.records, key = { it.id }) { record ->
                AppSectionCard {
                    Text("${record.wonAmount.money()}원 환전", style = MaterialTheme.typography.titleMedium)
                    AppSupportText(record.exchangeDate?.toString() ?: "날짜 미등록")
                    AppSupportText("당시 ${record.appliedRate.money()}원 · ${selected.rateLabel}")
                    AppSupportText("원래 환전분 전체 비교 · 사용한 외화 포함")
                    Comparison(record.wonAmount, record.appliedRate, latest?.rate, selected)
                    AppButtonRow(
                        primaryText = "수정",
                        onPrimaryClick = {
                            guard.navigate { discardCollectionChanges(); viewModel.edit(record) }
                        },
                        secondaryText = "삭제",
                        onSecondaryClick = { deleteTarget = record },
                        primaryEnabled = !state.isSaving,
                        secondaryEnabled = !state.isSaving,
                    )
                }
            }
        }
        }
        if (isExpenseEntry) {
        item { AppSectionHeader("지출 내역") }
        when {
            !hasSelectedData || (!state.isLoaded && state.loadError == null) -> item { AppLoadingCard("지출 내역을 불러오고 있습니다.") }
            state.loadError != null -> item { Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error) }
            state.expenses.isEmpty() -> item { AppEmptyCard("저장된 지출 내역이 없습니다.") }
            else -> items(state.expenses, key = { "expense:${it.id}" }) { expense ->
                AppSectionCard {
                    Text(expense.description, style = MaterialTheme.typography.titleMedium)
                    AppSupportText(expense.expenseDate?.toString() ?: "날짜 미등록")
                    if (!expense.usesForeignBalance) AppSupportText("기존 기록 · 외화 잔액 차감 없음")
                    Text("${expense.foreignAmount.money()} ${selected.amountUnitLabel}")
                    if (expense.cardWonAmount != null) CardExpenseComparison(expense.foreignAmount, expense.cardWonAmount,
                        state.analytics?.cumulativeAverageRate, selected)
                    else AppSupportText("카드 표시 원화 환산액 미등록 · 수정에서 입력해 주세요.")
                    AppButtonRow(
                        primaryText = "수정", onPrimaryClick = { guard.navigate { viewModel.editExpense(expense) } },
                        secondaryText = "삭제", onSecondaryClick = { deleteExpenseTarget = expense },
                        primaryEnabled = !state.isSaving, secondaryEnabled = !state.isSaving,
                    )
                }
            }
        }
        }
        }
    }
}

@Composable
private fun ExchangePositionAnalysis(analysis: ExchangeAnalytics, currency: ExchangeCurrency, latest: ExchangeQuote?, expenseCount: Int) {
    var showDetails by rememberSaveable(currency) { mutableStateOf(false) }
    val rateDifference = analysis.cumulativeAverageRate?.let { average -> latest?.rate?.subtract(average) }
    AppSectionHeader("누적 환전 환율", currency.rateLabel)
    ExchangeSummaryTable(
        headers = listOf("누적 평균", "현재 환율", "차이"),
        values = listOf(analysis.cumulativeAverageRate?.let { "${it.money()}원" } ?: "기록 없음",
            latest?.rate?.let { "${it.money()}원" } ?: "고시 없음", rateDifference.signedWon()),
        difference = rateDifference,
    )
    AppSectionHeader("남은 외화 평가", "보유 평균 환율 ${analysis.remainingAverageRate?.let { "${it.money()}원 · ${currency.rateLabel}" } ?: "확인 필요"}")
    ExchangeSummaryTable(
        headers = listOf("매입 원가", "현재 평가액", "평가차액"),
        values = listOf(analysis.remainingCostWon?.let { "${it.money()}원" } ?: "확인 필요",
            analysis.currentValueWon?.let { "${it.money()}원" } ?: "고시 없음", analysis.valuationDifferenceWon.signedWon()),
        difference = analysis.valuationDifferenceWon,
    )
    latest?.let { AppSupportText("고시 ${it.quotedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}") }
    analysis.basisIssue?.let { AppSupportText(if (it.contains("시각") || it.contains("순서"))
        "거래 순서를 확인할 수 없어 보유 원가는 미확인입니다. 지출 비교는 현재 누적 평균으로 표시합니다." else it) }
    AppSecondaryButton(if (showDetails) "원가·지출 요약 접기" else "원가·지출 요약 보기", { showDetails = !showDetails })
    if (showDetails) {
        Text("확인된 원화 지출 비용 ${analysis.knownExpenseWon.money()}원 · ${expenseCount - analysis.unknownExpenseCostCount}/${expenseCount}건")
        if (analysis.unknownExpenseCostCount > 0) AppSupportText("원가 미확인 ${analysis.unknownExpenseCostCount}건은 합계에서 제외")
        AppSupportText("환율 차이는 현재−누적 평균, 평가차액은 현재 평가액−매입 원가입니다.")
        AppSupportText("평균 원가는 환전·지출 순서에 따른 이동 가중평균입니다. 최신 고시 평가액은 실제 재환전 수령액과 다릅니다.")
    }
}

private fun BigDecimal?.signedWon(): String = this?.let {
    "${if (it.signum() > 0) "+" else ""}${it.money()}원"
} ?: "확인 필요"

@Composable
private fun ExchangeSummaryTable(headers: List<String>, values: List<String>, difference: BigDecimal? = null) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tableWidth = maxOf(maxWidth, 324.dp * fontScale)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(tableWidth)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    headers.forEach { header ->
                        Text(header, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    values.forEachIndexed { index, value ->
                        Text(value, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End,
                            fontWeight = if (index == values.lastIndex) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                index != values.lastIndex || difference == null -> MaterialTheme.colorScheme.onSurface
                                difference.signum() < 0 -> MaterialTheme.colorScheme.error
                                difference.signum() > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            })
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun CardExpenseComparison(foreignAmount: BigDecimal?, cardWon: BigDecimal?, averageRate: BigDecimal?, currency: ExchangeCurrency) {
    val comparison = remember(currency, foreignAmount, cardWon, averageRate) {
        compareCardExpense(currency, foreignAmount, cardWon, averageRate)
    } ?: return
    AppSectionHeader("환율·환산액 비교", "현재 누적 환전 평균 기준 · ${currency.rateLabel}")
    ExchangeSummaryTable(
        headers = listOf("카드 표시 환율", "내 평균 환율", "환율 차이"),
        values = listOf("${comparison.cardRate.money()}원", averageRate?.let { "${it.money()}원" } ?: "평균 미확인",
            comparison.rateDifference.signedWon()),
    )
    ExchangeSummaryTable(
        headers = listOf("카드 표시 환산액", "내 평균 환산액", "원화 차액"),
        values = listOf("${requireNotNull(cardWon).money()}원", comparison.averageWonAmount?.let { "${it.money()}원" } ?: "평균 미확인",
            comparison.wonDifference.signedWon()),
    )
    AppSupportText("차이 = 카드 표시−내 평균 · +는 더 높음, −는 더 낮음")
}

@Composable
private fun Comparison(amount: BigDecimal, appliedRate: BigDecimal, currentRate: BigDecimal?, currency: ExchangeCurrency) {
    val foreignAmount = remember(amount, appliedRate, currency) {
        amount.multiply(currency.unit.toBigDecimal()).divide(appliedRate, 8, RoundingMode.HALF_UP)
    }
    AppSupportText("추정 외화 수량 ${foreignAmount.money()} ${currency.amountUnitLabel}")
    if (currentRate == null) {
        AppSupportText("환율 수집 후 현재 평가금액을 비교할 수 있습니다.")
        return
    }
    val valuation = remember(amount, appliedRate, currentRate) {
        amount.multiply(currentRate).divide(appliedRate, 8, RoundingMode.HALF_UP)
    }
    val difference = valuation.subtract(amount)
    Text("현재 기준 평가금액 ${valuation.money()}원")
    Text("차액 ${if (difference.signum() > 0) "+" else ""}${difference.money()}원", style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ExchangeRateChart(quotes: List<ExchangeQuote>, currency: ExchangeCurrency, period: ExchangeChartPeriod) {
    var selectedIndex by rememberSaveable(currency.name, period.name) { mutableIntStateOf(-1) }
    val selected = quotes[if (selectedIndex < 0) quotes.lastIndex else selectedIndex.coerceAtMost(quotes.lastIndex)]
    val axisFormat = remember(period) { DateTimeFormatter.ofPattern(when (period) {
        ExchangeChartPeriod.ALL -> "yyyy.MM.dd HH:mm:ss"
        ExchangeChartPeriod.TODAY -> "HH:mm:ss"
        ExchangeChartPeriod.DAILY -> "yyyy.MM.dd"
        ExchangeChartPeriod.MONTHLY -> "yyyy.MM"
    }) }
    val pointLabel = when (period) {
        ExchangeChartPeriod.ALL, ExchangeChartPeriod.TODAY -> "고시"
        ExchangeChartPeriod.DAILY -> "일"
        ExchangeChartPeriod.MONTHLY -> "월"
    }
    val points = remember(quotes, period) {
        val low = quotes.minOf { it.rate.toDouble() }
        val high = quotes.maxOf { it.rate.toDouble() }
        val padding = ((high - low) * 0.1).coerceAtLeast(0.01)
        fun distance(quote: ExchangeQuote, index: Int): Long = when (period) {
            ExchangeChartPeriod.ALL, ExchangeChartPeriod.TODAY -> index.toLong()
            ExchangeChartPeriod.DAILY -> ChronoUnit.DAYS.between(quotes.first().quotedAt.toLocalDate(), quote.quotedAt.toLocalDate())
            ExchangeChartPeriod.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(quotes.first().quotedAt), YearMonth.from(quote.quotedAt))
        }
        val span = distance(quotes.last(), quotes.lastIndex).coerceAtLeast(1)
        quotes.mapIndexed { index, quote ->
            Offset(
                distance(quote, index).toFloat() / span,
                (1 - (quote.rate.toDouble() - low + padding) / (high - low + 2 * padding)).toFloat(),
            )
        }
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val chartHeight = (180 * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text("${selected.rate.money()}원", style = MaterialTheme.typography.headlineSmall)
        AppSupportText("$pointLabel ${selected.quotedAt.format(axisFormat)}")
        AppSupportText("최저 ${quotes.minOf { it.rate }.money()}원 · 최고 ${quotes.maxOf { it.rate }.money()}원")
        Canvas(Modifier.fillMaxWidth().height(chartHeight).padding(4.dp).semantics {
            contentDescription = "${currency.label} ${period.label} 환율 그래프. ${quotes.size}개 지점. 아래 슬라이더로 환율을 확인할 수 있습니다."
        }) {
            listOf(0f, 0.5f, 1f).forEach { ratio ->
                drawLine(gridColor, Offset(0f, size.height * ratio), Offset(size.width, size.height * ratio))
            }
            fun position(point: Offset) = Offset(point.x * size.width, point.y * size.height)
            for (index in 0 until points.lastIndex) {
                drawLine(lineColor, position(points[index]), position(points[index + 1]), 2.dp.toPx())
            }
            val index = if (selectedIndex < 0) points.lastIndex else selectedIndex.coerceAtMost(points.lastIndex)
            val selectedPoint = position(points[index])
            drawLine(gridColor, Offset(selectedPoint.x, 0f), Offset(selectedPoint.x, size.height), 1.dp.toPx())
            drawCircle(lineColor, 4.dp.toPx(), position(points[index]))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(quotes.first().quotedAt.format(axisFormat), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(quotes.last().quotedAt.format(axisFormat), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        if (quotes.size > 1) Slider(
            value = (if (selectedIndex < 0) quotes.lastIndex else selectedIndex.coerceAtMost(quotes.lastIndex)).toFloat(),
            onValueChange = { selectedIndex = it.roundToInt() }, valueRange = 0f..quotes.lastIndex.toFloat(),
            modifier = Modifier.semantics { contentDescription = "${period.label} 환율 지점 선택" },
        )
        if (selectedIndex >= 0) {
            AppSupportText("고시 ${selected.quotedAt.format(quoteTimeFormat)} · ${selected.round}회차 · 출처: ${selected.source.label}")
        }
        val hasNaverQuotes = remember(quotes) { quotes.any { it.source == ExchangeRateSource.NAVER_SHINHAN } }
        if (hasNaverQuotes) AppSupportText("네이버에서 수집한 신한은행 고시가 포함되어 있습니다. 슬라이더로 선택 지점의 출처를 확인할 수 있습니다.")
        AppSupportText(if (quotes.size == 1) "표시할 지점이 1개입니다. 점으로 환율을 표시합니다." else "저장된 환율만 연결한 추이입니다. 저장 이력이 없는 구간의 실제 변동은 포함하지 않습니다.")
    }
}

private val quoteTimeFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
private fun BigDecimal.money(): String = NumberFormat.getNumberInstance(Locale.KOREA).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 2
}.format(this.setScale(2, RoundingMode.HALF_UP))
