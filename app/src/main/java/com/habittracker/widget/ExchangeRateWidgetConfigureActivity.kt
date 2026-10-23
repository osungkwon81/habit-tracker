package com.habittracker.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.lifecycleScope
import com.habittracker.data.exchange.ExchangeCurrency
import com.habittracker.ui.exchange.ExchangeChartPeriod
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppNavigationGuard
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.LocalAppNavigationGuard
import com.habittracker.ui.theme.HabitTrackerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ExchangeRateWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this, ExchangeRateWidgetProvider::class.java)) {
            finish()
            return
        }
        lifecycleScope.launch {
            val initialCurrency = withContext(Dispatchers.IO) {
                ExchangeRateWidgetProvider.selectedCurrency(applicationContext, widgetId)
            }
            val initialPeriod = withContext(Dispatchers.IO) {
                ExchangeRateWidgetProvider.selectedPeriod(applicationContext, widgetId)
            }
            val initialDays = withContext(Dispatchers.IO) {
                ExchangeRateWidgetProvider.selectedDays(applicationContext, widgetId)
            }
            setContent {
                HabitTrackerTheme {
                    var selectedCode by rememberSaveable { mutableStateOf(initialCurrency?.name) }
                    var selectedPeriod by rememberSaveable { mutableStateOf(initialPeriod.name) }
                    var daysInput by rememberSaveable { mutableStateOf(initialDays.toString()) }
                    var customDays by rememberSaveable { mutableStateOf(initialDays !in listOf(1, 3, 7)) }
                    val isRecent = selectedPeriod == ExchangeChartPeriod.TODAY.name
                    val days = daysInput.toIntOrNull()?.takeIf { it > 0 }
                    var isSaving by remember { mutableStateOf(false) }
                    var errorMessage by remember { mutableStateOf<String?>(null) }
                    val guard = remember { AppNavigationGuard() }
                    SideEffect { guard.hasUnsavedChanges = selectedCode != initialCurrency?.name || selectedPeriod != initialPeriod.name || (isRecent && days != initialDays) }
                    BackHandler {
                        if (!isSaving) guard.navigate { finish() }
                    }
                    CompositionLocalProvider(LocalAppNavigationGuard provides guard) {
                        guard.pendingAction?.let { action ->
                            AppConfirmDialog(
                                title = "위젯 설정을 취소할까요?",
                                message = "저장하지 않은 통화와 그래프 보기 선택은 사라집니다.",
                                confirmText = "변경 버리기",
                                onConfirm = {
                                    guard.pendingAction = null
                                    guard.hasUnsavedChanges = false
                                    action()
                                },
                                onDismiss = { guard.pendingAction = null },
                            )
                        }
                        AppScreen {
                            item { AppHeroCard(title = "환율 위젯", description = "2×2 위젯의 통화와 그래프 보기를 선택하세요.") }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                                    ExchangeCurrency.entries.forEach { currency ->
                                        AppSelectableChip(currency.label, selectedCode == currency.name, onClick = {
                                            if (!isSaving) { selectedCode = currency.name; errorMessage = null }
                                        })
                                    }
                                }
                            }
                            item {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                                    ExchangeChartPeriod.entries.forEach { period ->
                                        AppSelectableChip(if (period == ExchangeChartPeriod.TODAY) "최근 기간" else period.label, selectedPeriod == period.name, onClick = {
                                            if (!isSaving) { selectedPeriod = period.name; errorMessage = null }
                                        })
                                    }
                                }
                            }
                            if (isRecent) {
                                item {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                                        listOf(1, 3, 7).forEach { count ->
                                            AppSelectableChip(if (count == 1) "오늘" else "최근 ${count}일", !customDays && days == count, onClick = {
                                                if (!isSaving) { daysInput = count.toString(); customDays = false; errorMessage = null }
                                            })
                                        }
                                        AppSelectableChip("직접 입력", customDays, onClick = { if (!isSaving) customDays = true })
                                    }
                                }
                                if (customDays) item {
                                    AppTextField(
                                        value = daysInput, onValueChange = { daysInput = it }, label = "조회 일수(오늘 포함)",
                                        singleLine = true, enabled = !isSaving,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = days == null,
                                        supportingText = if (days == null) "1 이상의 정수를 입력해 주세요." else null,
                                    )
                                }
                                if (days != null) item {
                                    val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
                                    val format = DateTimeFormatter.ofPattern("yyyy.MM.dd")
                                    AppSupportText("${today.minusDays(days.toLong() - 1).format(format)} ~ ${today.format(format)} · 오늘 포함 ${days}일")
                                }
                            }
                            item { AppSupportText("달러는 1달러, 엔화는 100엔 기준 원화 금액과 저장된 그래프를 표시합니다.") }
                            item { AppSupportText("최근 기간은 한국 시간 기준 오늘을 포함하며 주말도 일수에 포함합니다. 일별·월별은 해당 기간의 마지막 저장 고시환율을 표시합니다.") }
                            item { AppSupportText("수집한 고시가 저장되면 위젯도 갱신됩니다. 조회 기간은 위젯 갱신 시점의 오늘을 기준으로 이동합니다.") }
                            if (errorMessage != null) item { Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error) }
                            item {
                            AppSaveButton(
                                text = if (isSaving) "위젯 설정 적용 중" else "위젯 설정 적용",
                                enabled = selectedCode != null && !isSaving && (!isRecent || days != null),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val selected = ExchangeCurrency.entries.firstOrNull { it.name == selectedCode }
                                    if (selected != null && !isSaving && (!isRecent || days != null)) {
                                        val savedDays = if (isRecent) checkNotNull(days) else 1
                                        isSaving = true
                                        errorMessage = null
                                        lifecycleScope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    ExchangeRateWidgetProvider.saveSettings(applicationContext, widgetId, selected, ExchangeChartPeriod.valueOf(selectedPeriod), savedDays)
                                                    ExchangeRateWidgetProvider.enqueueUpdate(applicationContext, widgetId)
                                                }
                                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                                finish()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                Log.e("ExchangeWidget", "위젯 설정 실패: widgetId=$widgetId currency=${selected.name} period=$selectedPeriod days=$savedDays", error)
                                                errorMessage = "위젯 설정을 적용하지 못했습니다. 다시 시도해 주세요."
                                            } finally {
                                                isSaving = false
                                            }
                                        }
                                    }
                                },
                            )
                            }
                        }
                    }
                }
            }
        }
    }
}
