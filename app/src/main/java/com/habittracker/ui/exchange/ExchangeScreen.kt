package com.habittracker.ui.exchange

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.exchange.ExchangeCurrency
import com.habittracker.data.exchange.ExchangeCollectionSettings
import com.habittracker.data.exchange.ExchangeQuote
import com.habittracker.data.exchange.ExchangeRecord
import com.habittracker.data.exchange.ExchangeExpense
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
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class ExchangePage { RATES, RECORDS }

@Composable
fun ExchangeScreen(
    viewModel: ExchangeViewModel,
    page: ExchangePage = ExchangePage.RATES,
    onOpenRates: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
) {
    val isRecordsPage = page == ExchangePage.RECORDS
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val expenseEditor by viewModel.expenseEditor.collectAsStateWithLifecycle()
    val entryTypeCode by viewModel.entryType.collectAsStateWithLifecycle()
    val entryType = ExchangeEntryType.valueOf(entryTypeCode)
    val isExpenseEntry = entryType == ExchangeEntryType.EXPENSE
    val selectedCode by viewModel.currency.collectAsStateWithLifecycle()
    val collectionInterval by viewModel.collectionIntervalMinutes.collectAsStateWithLifecycle()
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
    }
    val selected = ExchangeCurrency.valueOf(selectedCode)
    val guard = LocalAppNavigationGuard.current
    var deleteTarget by remember { mutableStateOf<ExchangeRecord?>(null) }
    var deleteExpenseTarget by remember { mutableStateOf<ExchangeExpense?>(null) }
    SideEffect { guard.hasUnsavedChanges = if (isRecordsPage) editor.hasChanges || expenseEditor.hasChanges else collectionHasChanges }
    DisposableEffect(guard) { onDispose { guard.hasUnsavedChanges = false } }
    LaunchedEffect(viewModel, page) { if (!isRecordsPage) viewModel.refresh() }
    AppActionNotice(state.message, viewModel::clearMessage)
    val hasSelectedData = state.currency == selected
    val quotes = if (hasSelectedData) state.quotes else emptyList()
    val latest = quotes.lastOrNull()
    val amount = positiveDecimal(editor.wonAmount)
    val rate = positiveDecimal(editor.appliedRate)
    val expenseAmount = positiveDecimal(expenseEditor.foreignAmount)
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
            message = "${expense.description} · ${expense.foreignAmount.money()} ${selected.amountUnitLabel} 지출을 삭제할까요? 삭제하면 잔액에 다시 반영됩니다.",
            confirmText = "삭제",
            onConfirm = { viewModel.deleteExpense(expense.id); deleteExpenseTarget = null },
            onDismiss = { deleteExpenseTarget = null },
        )
    }
    AppScreen(bottomBar = if (isRecordsPage) { {
        AppSaveButton(
            onClick = if (isExpenseEntry) viewModel::saveExpense else viewModel::save,
            text = if (isExpenseEntry) "지출 저장" else "환전 저장",
            enabled = !state.isSaving && (if (isExpenseEntry) expenseAmount != null && expenseEditor.description.isNotBlank() else amount != null && rate != null),
            modifier = Modifier.fillMaxWidth(),
        )
    } } else null) {
        item {
            AppHeroCard(
                title = if (isRecordsPage) "환전·지출 입력" else "현재 환율·그래프",
                description = if (isRecordsPage) "환전 기록과 외화 지출을 입력하고 남은 금액을 확인합니다." else "신한은행 매매기준율과 수집 이력을 확인합니다.",
            )
        }
        item {
            AppSecondaryButton(
                text = if (isRecordsPage) "현재 환율·그래프 보기" else "환전·지출 입력·기록",
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
        item {
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
                !state.isLoaded -> AppLoadingCard("${selected.label} 금액을 불러오고 있습니다.")
                else -> Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    state.balances.filter { it.currency == selected }.forEach { balance ->
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            AppSupportText("환전 합계 ${balance.exchangedAmount.money()} ${balance.currency.amountUnitLabel}")
                            AppSupportText("지출 합계 ${balance.spentAmount.money()} ${balance.currency.amountUnitLabel}")
                            Text("남은 금액 ${balance.remainingAmount.money()} ${balance.currency.amountUnitLabel}", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    AppSupportText("환전 합계는 당시 적용 환율로 계산한 추정 외화 수량입니다.")
                }
            }
        }
        if (!isRecordsPage) {
        item {
            AppSectionHeader(title = "현재 고시환율", subtitle = selected.rateLabel)
            if (latest != null) {
                Text("${latest.rate.money()}원", style = MaterialTheme.typography.headlineMedium)
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
            AppSecondaryButton("환율 새로고침", viewModel::refresh, Modifier.fillMaxWidth(), enabled = !state.isRefreshing)
        }
        item {
            AppSectionHeader("환율 추이")
            when {
                !hasSelectedData || (!state.isLoaded && state.loadError == null) -> AppLoadingCard("수집 환율을 불러오고 있습니다.")
                state.loadError != null -> Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                quotes.isEmpty() -> AppEmptyCard("수집한 환율이 쌓이면 변화를 볼 수 있습니다.")
                else -> ExchangeRateChart(quotes, selected)
            }
        }
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
                AppSupportText("앱을 닫아도 인터넷 연결 시 달러·엔화를 함께 수집합니다. 한국 시간 기준 토·일요일에는 자동 수집을 건너뜁니다. 신규 예약의 첫 자동 수집은 설정한 간격 이후이며 절전·네트워크 상황에 따라 늦어질 수 있습니다. 화면 진입·새로고침 수집은 주말에도 사용할 수 있습니다.")
            }
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
                AppTextField(
                    value = expenseEditor.foreignAmount, onValueChange = viewModel::updateExpenseAmount,
                    label = "지출 금액(${selected.amountUnitLabel})", singleLine = true,
                    enabled = !state.isSaving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = expenseEditor.foreignAmount.isNotBlank() && expenseAmount == null,
                    supportingText = if (expenseEditor.foreignAmount.isNotBlank() && expenseAmount == null) "0보다 큰 외화 금액을 입력해 주세요." else null,
                )
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
                if (editor.id != null) AppSecondaryButton("수정 취소", {
                    guard.navigate { discardCollectionChanges(); viewModel.clearEditor() }
                }, enabled = !state.isSaving)
                AppSupportText("당시 우대가 적용된 실제 환율을 입력해 주세요. 비교금액은 매매기준율 평가액이며 실제 재환전 수령액과 다를 수 있습니다.")
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
                    AppSupportText("당시 ${record.appliedRate.money()}원 · ${selected.rateLabel}")
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
                    Text("${expense.foreignAmount.money()} ${selected.amountUnitLabel}")
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
private fun ExchangeRateChart(quotes: List<ExchangeQuote>, currency: ExchangeCurrency) {
    var selectedIndex by rememberSaveable(currency.name) { mutableIntStateOf(-1) }
    val selected = quotes[if (selectedIndex < 0) quotes.lastIndex else selectedIndex.coerceAtMost(quotes.lastIndex)]
    val points = remember(quotes) {
        val low = quotes.minOf { it.rate.toDouble() }
        val high = quotes.maxOf { it.rate.toDouble() }
        val padding = ((high - low) * 0.1).coerceAtLeast(0.01)
        val seconds = Duration.between(quotes.first().quotedAt, quotes.last().quotedAt).seconds.coerceAtLeast(1)
        quotes.map {
            Offset(
                Duration.between(quotes.first().quotedAt, it.quotedAt).seconds.toFloat() / seconds,
                (1 - (it.rate.toDouble() - low + padding) / (high - low + 2 * padding)).toFloat(),
            )
        }
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        AppSupportText("최저 ${quotes.minOf { it.rate }.money()}원 · 최고 ${quotes.maxOf { it.rate }.money()}원")
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(4.dp).semantics {
            contentDescription = "${currency.label} 수집 환율 그래프. ${quotes.size}개 고시. 아래 슬라이더로 고시별 환율을 확인할 수 있습니다."
        }) {
            listOf(0f, 0.5f, 1f).forEach { ratio ->
                drawLine(gridColor, Offset(0f, size.height * ratio), Offset(size.width, size.height * ratio))
            }
            fun position(point: Offset) = Offset(point.x * size.width, point.y * size.height)
            for (index in 0 until points.lastIndex) {
                drawLine(lineColor, position(points[index]), position(points[index + 1]), 2.dp.toPx())
            }
            val index = if (selectedIndex < 0) points.lastIndex else selectedIndex.coerceAtMost(points.lastIndex)
            drawCircle(lineColor, 4.dp.toPx(), position(points[index]))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(quotes.first().quotedAt.format(quoteTimeFormat), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(quotes.last().quotedAt.format(quoteTimeFormat), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        }
        if (quotes.size > 1) Slider(
            value = (if (selectedIndex < 0) quotes.lastIndex else selectedIndex.coerceAtMost(quotes.lastIndex)).toFloat(),
            onValueChange = { selectedIndex = it.roundToInt() }, valueRange = 0f..quotes.lastIndex.toFloat(),
            modifier = Modifier.semantics { contentDescription = "수집한 고시 선택" },
        )
        if (selectedIndex >= 0) {
            Text("선택한 고시 ${selected.quotedAt.format(quoteTimeFormat)} · ${selected.round}회차 · ${selected.rate.money()}원")
        }
        AppSupportText(if (quotes.size == 1) "고시가 1개 수집되었습니다. 다음 고시 수집 후 추이를 표시합니다." else "수집한 고시만 연결한 추이입니다. 미수집 구간의 실제 변동은 포함하지 않습니다.")
    }
}

private val quoteTimeFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
private fun BigDecimal.money(): String = NumberFormat.getNumberInstance(Locale.KOREA).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 2
}.format(this.setScale(2, RoundingMode.HALF_UP))
