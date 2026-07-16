package com.habittracker.ui.stock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.habittracker.data.stock.StockAutomationService
import com.habittracker.data.stock.StockExitRuleType
import com.habittracker.data.stock.StockRuleAction
import com.habittracker.data.stock.isCrashGuardOrderBlock
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

@Composable
fun StockAutomationScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    fun startMonitoring() {
        viewModel.setMonitoringEnabled(true) { success ->
            if (success) StockAutomationService.start(context)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionDenied = !granted
        if (granted) startMonitoring()
    }

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) viewModel.loadOwnedStocks(force = true)
    }

    AppScreen {
        item {
            StockHeroCard(
                icon = "🛡",
                eyebrow = "STOCK · AUTOMATION",
                title = "자동 매도·알림",
                description = "정규장과 NXT 애프터마켓에서 켜진 규칙을 감시하며, 전역 스위치가 켜져야 자동 매도합니다.",
            )
        }
        item {
            AppSectionCard {
                StockSectionTitle("긴급 정지")
                if (uiState.safetyConfig.globalOrderBlocked) {
                    AppStatusText(uiState.safetyConfig.blockReason ?: "전체 주문 차단 중")
                    if (uiState.safetyConfig.isCrashGuardOrderBlock()) {
                        AppSupportText("급락 안전장치가 발동해 일반 주문은 차단 중이며, 사용자가 확인한 긴급 전체 시장가 매도만 허용됩니다.")
                    }
                    AppPrimaryButton(
                        text = "KIS 상태 확인 후 차단 해제",
                        onClick = { viewModel.setGlobalOrderBlock(false) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    AppSupportText("누르면 직접 주문·자동 매도·리밸런싱 주문을 즉시 차단합니다.")
                    AppPrimaryButton(
                        text = "전체 주문 긴급 차단",
                        onClick = { viewModel.setGlobalOrderBlock(true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("백그라운드 모니터링")
                AppStatusText(
                    if (uiState.safetyConfig.monitoringEnabled) "모니터링 사용 중" else "모니터링 중지됨",
                )
                AppSupportText("Android 상시 알림을 유지하고, KIS 개장일 정규장 09:00~15:30과 NXT 애프터마켓 15:40~20:00에 설정된 주기로 규칙과 급락 안전장치를 확인합니다.")
                AppSupportText("조건 충족 알림에는 종목·시장 구분·현재가·평균가·수익률·보유수량·발동 조건·주문 결과가 표시됩니다.")
                if (notificationPermissionDenied) {
                    Text(
                        "알림 권한이 거부되어 모니터링을 시작하지 않았습니다.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    AppPrimaryButton(
                        text = if (uiState.safetyConfig.monitoringEnabled) "다시 시작" else "시작",
                        onClick = {
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startMonitoring()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.isConfigSaved,
                    )
                    AppSecondaryButton(
                        text = "중지",
                        onClick = {
                            viewModel.setMonitoringEnabled(false) { success ->
                                if (success) StockAutomationService.stop(context)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.safetyConfig.monitoringEnabled,
                    )
                }
                AppSecondaryButton(
                    text = if (uiState.isRunningAutomation) "확인 중" else "지금 한 번 확인",
                    onClick = viewModel::runAutomationOnce,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.safetyConfig.monitoringEnabled && !uiState.isRunningAutomation,
                )
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("매도·알림 규칙 추가")
                StockProductDropdown(
                    label = "보유 종목",
                    selectedCode = uiState.ruleProductCode,
                    options = uiState.ownedProductOptions(),
                    enabled = !uiState.isLoadingOwnedStocks,
                    onSelect = { viewModel.selectRuleProduct(it.code, it.name) },
                )
                Text("규칙 유형", style = MaterialTheme.typography.labelLarge)
                StockExitRuleType.values().forEach { type ->
                    AppSelectableChip(
                        label = type.label,
                        selected = uiState.ruleType == type,
                        onClick = { viewModel.selectRuleType(type) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "기능 요약",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                AppSupportText(uiState.ruleType.featureSummary())
                AppTextField(
                    value = uiState.ruleTriggerValue,
                    onValueChange = viewModel::updateRuleTriggerValue,
                    label = "발동 기준 (${uiState.ruleType.unit})",
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    StockRuleAction.values().forEach { action ->
                        AppSelectableChip(
                            label = action.label,
                            selected = uiState.ruleAction == action,
                            onClick = { viewModel.selectRuleAction(action) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (uiState.ruleAction == StockRuleAction.AUTO_SELL) {
                    AppTextField(
                        value = uiState.ruleSellPercent,
                        onValueChange = viewModel::updateRuleSellPercent,
                        label = "매도 비율 (%)",
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    ) {
                        AppSelectableChip(
                            label = "현재가 지정가",
                            selected = uiState.ruleOrderDivisionCode == "00",
                            onClick = { viewModel.selectRuleOrderDivision("00") },
                            modifier = Modifier.weight(1f),
                        )
                        AppSelectableChip(
                            label = "시장가",
                            selected = uiState.ruleOrderDivisionCode == "01",
                            onClick = { viewModel.selectRuleOrderDivision("01") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (uiState.ruleOrderDivisionCode == "01") {
                        Text(
                            "시장가 자동 매도는 급격한 가격 변동 시 예상보다 낮은 가격에 체결될 수 있습니다.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                AppPrimaryButton(
                    text = "규칙 저장",
                    onClick = viewModel::saveExitRule,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.ruleProductCode.isNotBlank() &&
                        uiState.ruleTriggerValue.toDoubleOrNull()?.let { it > 0.0 } == true &&
                        (uiState.ruleAction == StockRuleAction.NOTIFY_ONLY ||
                            uiState.ruleSellPercent.toDoubleOrNull()?.let { it > 0.0 } == true),
                )
                AppSupportText("익절 규칙을 여러 개 추가하면 분할 매도 단계로 사용할 수 있습니다. 자동 주문은 한 종목당 한 단계씩 처리합니다.")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                StockSectionTitle("저장된 규칙")
                AppSecondaryButton(
                    text = if (uiState.isLoadingOwnedStocks) "현재가 갱신 중" else "현재가 갱신",
                    onClick = { viewModel.loadOwnedStocks(force = true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConfigSaved && !uiState.isLoadingOwnedStocks,
                )
            }
        }
        if (uiState.exitRules.isEmpty()) {
            item { AppSectionCard { AppSupportText("저장된 자동 매도 규칙이 없습니다.") } }
        }
        items(uiState.exitRules.size) { index ->
            val rule = uiState.exitRules[index]
            val type = StockExitRuleType.values().firstOrNull { it.name == rule.ruleType }
            val action = StockRuleAction.values().firstOrNull { it.name == rule.actionMode }
            val balance = uiState.ownedStocks.firstOrNull { it.productCode == rule.productCode }
            val currentPrice = balance?.currentPrice?.toDoubleOrNull()?.roundToLong()
            val averagePrice = balance?.averagePrice?.toDoubleOrNull()
            val triggerPrice = when (type) {
                StockExitRuleType.STOP_LOSS ->
                    averagePrice?.times(1.0 - rule.triggerValue / 100.0)?.let { floor(it).toLong() }
                StockExitRuleType.TAKE_PROFIT ->
                    averagePrice?.times(1.0 + rule.triggerValue / 100.0)?.let { ceil(it).toLong() }
                StockExitRuleType.TRAILING_STOP ->
                    (rule.referenceHighPrice ?: currentPrice)
                        ?.times(1.0 - rule.triggerValue / 100.0)
                        ?.let { floor(it).toLong() }
                StockExitRuleType.TIME_EXIT, null -> null
            }
            val holdingQuantity = balance?.quantity?.toLongOrNull()
            val currentValuationAmount = if (holdingQuantity != null && currentPrice != null) {
                runCatching { Math.multiplyExact(holdingQuantity, currentPrice) }.getOrNull()
            } else {
                null
            }
            val estimatedSellQuantity = if (
                action == StockRuleAction.AUTO_SELL && holdingQuantity != null && holdingQuantity > 0L
            ) {
                floor(holdingQuantity * rule.sellQuantityPercent / 100.0)
                    .toLong()
                    .coerceAtLeast(1L)
                    .coerceAtMost(holdingQuantity)
            } else {
                null
            }
            val estimatedSellUnitPrice = triggerPrice ?: currentPrice
            val estimatedSellAmount = if (estimatedSellQuantity != null && estimatedSellUnitPrice != null) {
                runCatching { Math.multiplyExact(estimatedSellQuantity, estimatedSellUnitPrice) }.getOrNull()
            } else {
                null
            }
            AppSectionCard {
                Text("${rule.productName} (${rule.productCode})", style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("${type?.label ?: rule.ruleType} ${rule.triggerValue}${type?.unit.orEmpty()}")
                        if (action == StockRuleAction.AUTO_SELL) append(" · ${rule.sellQuantityPercent}% 매도")
                        append(" · ${action?.label ?: rule.actionMode}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "현재가 ${currentPrice.toWon()} · 보유평가액 ${currentValuationAmount.toWon()}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                when (type) {
                    StockExitRuleType.STOP_LOSS,
                    StockExitRuleType.TAKE_PROFIT -> Text(
                        "발동 예상가 ${triggerPrice.toWon()} · 평균단가 ${balance?.averagePrice?.toWon() ?: "-"}",
                    )
                    StockExitRuleType.TRAILING_STOP -> Text(
                        "발동 예상가 ${triggerPrice.toWon()} · 기준 고점 ${(rule.referenceHighPrice ?: currentPrice).toWon()}",
                    )
                    StockExitRuleType.TIME_EXIT -> AppSupportText("금액 기준 없이 ${rule.triggerValue.toLong()}일 보유 시 발동합니다.")
                    null -> Unit
                }
                if (type == StockExitRuleType.TRAILING_STOP && rule.referenceHighPrice == null) {
                    AppSupportText("추적된 고점이 없어 현재가를 초기 기준으로 계산했습니다.")
                }
                if (action == StockRuleAction.AUTO_SELL) {
                    Text(
                        "예상 매도금액 ${estimatedSellAmount.toWon()}" +
                            (estimatedSellQuantity?.let { " (${it}주 기준)" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (type == StockExitRuleType.TIME_EXIT) {
                        AppSupportText("예상 매도금액은 현재가 기준이며 실제 체결가와 다를 수 있습니다.")
                    }
                }
                AppStatusText(
                    when {
                        !rule.enabled -> "규칙 중지됨"
                        !uiState.safetyConfig.monitoringEnabled -> "활성화됨 · 모니터링 시작 필요"
                        else -> "감시 중"
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    AppSecondaryButton(
                        text = if (rule.enabled) "중지" else "다시 사용",
                        onClick = { viewModel.toggleExitRule(rule) },
                        modifier = Modifier.weight(1f),
                    )
                    AppSecondaryButton(
                        text = "삭제",
                        onClick = { viewModel.deleteExitRule(rule.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun StockExitRuleType.featureSummary(): String = when (this) {
    StockExitRuleType.STOP_LOSS ->
        "매수 평균가 대비 수익률이 입력한 손실률 이하로 내려가면 발동합니다. 예: 5 입력 → -5% 이하."
    StockExitRuleType.TAKE_PROFIT ->
        "매수 평균가 대비 수익률이 입력한 수익률 이상으로 올라가면 발동합니다. 예: 5 입력 → +5% 이상."
    StockExitRuleType.TRAILING_STOP ->
        "감시 중 기록한 최고가를 따라가다가 고점 대비 입력한 비율만큼 하락하면 발동합니다. 예: 고점 10만 원·3 입력 → 9만 7천 원 이하."
    StockExitRuleType.TIME_EXIT ->
        "남아 있는 매수분 중 가장 오래된 매수일부터 입력한 일수가 지나면 발동합니다."
}
