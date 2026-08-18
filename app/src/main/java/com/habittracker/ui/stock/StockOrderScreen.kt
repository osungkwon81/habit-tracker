package com.habittracker.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.stock.KisOrderSide
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField

@Composable
fun StockOrderScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfirmation by remember { mutableStateOf(false) }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) viewModel.loadReferenceStocks()
    }

    if (showConfirmation) {
        AppConfirmDialog(
            title = "실전 ${uiState.orderSide.label} 주문 확인",
            message = "${uiState.productName} (${uiState.productCode})\n" +
                "${uiState.orderQuantity}주 · ${if (uiState.orderDivisionCode == "01") "시장가" else "${uiState.orderUnitPrice.toLongOrNull().toWon()} 지정가"}\n\n" +
                "조회된 ${uiState.orderSide.label} 가능 수량 ${uiState.orderAvailability?.availableQuantity ?: 0L}주\n" +
                "실제 계좌에 주문이 전송됩니다. 계속하시겠습니까?",
            confirmText = "실전 주문 전송",
            onConfirm = {
                showConfirmation = false
                viewModel.submitCashOrder()
            },
            onDismiss = { showConfirmation = false },
        )
    }

    AppScreen {
        item {
            StockHeroCard(
                icon = "↕",
                eyebrow = "STOCK · ORDER",
                title = "매수·매도",
                description = "매수·매도 가능 수량과 안전 한도를 확인한 뒤 실전 주문합니다.",
            )
        }
        if (!uiState.isConfigSaved) {
            item {
                AppSectionCard {
                    StockSectionTitle("KIS 설정 필요")
                    AppSupportText("주식 홈의 KIS·안전 설정에서 실전 계좌 정보를 먼저 저장해 주세요.")
                }
            }
        }
        if (uiState.safetyConfig.globalOrderBlocked) {
            item {
                AppSectionCard {
                    StockSectionTitle("전체 주문 차단 중")
                    AppStatusText(uiState.safetyConfig.blockReason ?: "사용자 긴급 정지")
                }
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("주문 입력")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    KisOrderSide.values().forEach { side ->
                        AppSelectableChip(
                            label = side.label,
                            selected = uiState.orderSide == side,
                            onClick = { viewModel.selectOrderSide(side) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                val options = if (uiState.orderSide == KisOrderSide.SELL) {
                    uiState.ownedProductOptions()
                } else {
                    uiState.buyProductOptions()
                }
                StockProductDropdown(
                    label = if (uiState.orderSide == KisOrderSide.SELL) "보유 종목" else "매수 종목",
                    selectedCode = uiState.productCode,
                    options = options,
                    enabled = !uiState.isLoadingOwnedStocks && !uiState.isLoadingMarketCapStocks,
                    onSelect = { viewModel.selectOrderProduct(it.code, it.name) },
                )
                if (options.isEmpty()) {
                    AppSupportText(
                        if (uiState.orderSide == KisOrderSide.SELL) "매도 가능한 보유 종목이 없습니다." else "매수 종목 목록을 불러오는 중이거나 조회 결과가 없습니다.",
                    )
                }
                AppTextField(
                    value = uiState.productCode,
                    onValueChange = viewModel::updateOrderProductCode,
                    label = "종목코드 직접 입력 (6자리, ETN 7자리)",
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    AppSelectableChip(
                        label = "지정가",
                        selected = uiState.orderDivisionCode == "00",
                        onClick = { viewModel.updateOrderDivisionCode("00") },
                        modifier = Modifier.weight(1f),
                    )
                    AppSelectableChip(
                        label = "시장가",
                        selected = uiState.orderDivisionCode == "01",
                        onClick = { viewModel.updateOrderDivisionCode("01") },
                        modifier = Modifier.weight(1f),
                    )
                }
                AppTextField(
                    value = uiState.orderUnitPrice,
                    onValueChange = viewModel::updateOrderUnitPrice,
                    label = when {
                        uiState.orderDivisionCode == "01" -> "시장가 주문단가"
                        uiState.isLoadingOrderPrice -> "지정가 (현재가 조회 중)"
                        else -> "지정가"
                    },
                    enabled = uiState.orderDivisionCode != "01",
                    singleLine = true,
                )
                if (uiState.orderDivisionCode == "00") {
                    AppSecondaryButton(
                        text = if (uiState.isLoadingOrderPrice) "현재가 조회 중" else "현재가로 다시 입력",
                        onClick = viewModel::loadCurrentOrderPrice,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.isConfigSaved &&
                            !uiState.isLoadingOrderPrice &&
                            uiState.productCode.length in 6..7,
                    )
                } else {
                    Text(
                        "시장가는 주문단가 0원이 자동 적용됩니다. 수량 계산은 현재가 기준 추정치입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val holdingQuantity = uiState.orderAvailability?.holdingQuantity
                    ?: uiState.ownedStocks
                        .firstOrNull { it.productCode == uiState.productCode }
                        ?.quantity
                        ?.toLongOrNull()
                    ?: 0L
                AppSupportText("현재 보유 ${holdingQuantity}주")
                AppSecondaryButton(
                    text = when {
                        uiState.isLoadingOrderPrice -> "현재가 조회 중"
                        uiState.isLoadingOrderAvailability -> "가능 수량 조회 중"
                        uiState.orderAvailability != null -> "${uiState.orderSide.label} 가능 수량 다시 조회"
                        else -> "${uiState.orderSide.label} 가능 수량 조회"
                    },
                    onClick = viewModel::loadOrderAvailability,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConfigSaved &&
                        !uiState.isLoadingOrderPrice &&
                        !uiState.isLoadingOrderAvailability &&
                        uiState.productCode.length in 6..7 &&
                        (uiState.orderDivisionCode == "01" ||
                            uiState.orderUnitPrice.toLongOrNull()?.let { it > 0L } == true),
                )
                uiState.orderAvailability?.let { availability ->
                    Text(
                        "${uiState.orderSide.label} 가능 ${availability.availableQuantity}주 · " +
                            "가능 금액 ${availability.availableAmount.toWon()} · " +
                            "현재가 ${availability.currentPrice.toWon()}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    StockOrderQuantityCalculator(
                        uiState = uiState,
                        onAmountChange = viewModel::updateOrderCalculationAmount,
                        onApplyPercent = viewModel::applyOrderQuantityPercent,
                    )
                }
                AppTextField(
                    value = uiState.orderQuantity,
                    onValueChange = viewModel::updateOrderQuantity,
                    label = "${uiState.orderSide.label} 수량",
                    singleLine = true,
                )
                val requestedQuantity = uiState.orderQuantity.toLongOrNull()
                val availableQuantity = uiState.orderAvailability?.availableQuantity
                if (requestedQuantity != null && availableQuantity != null && requestedQuantity > availableQuantity) {
                    Text(
                        "입력한 수량이 ${uiState.orderSide.label} 가능 수량을 초과합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                AppPrimaryButton(
                    text = if (uiState.isSubmittingOrder) "주문 전송 중" else "실전 ${uiState.orderSide.label} 주문 확인",
                    onClick = { showConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConfigSaved &&
                        !uiState.safetyConfig.globalOrderBlocked &&
                        !uiState.isSubmittingOrder &&
                        uiState.productCode.length in 6..7 &&
                        uiState.orderQuantity.toLongOrNull()?.let { quantity ->
                            quantity > 0L && quantity <= (uiState.orderAvailability?.availableQuantity ?: 0L)
                        } == true &&
                        uiState.orderAvailability?.side == uiState.orderSide &&
                        (if (uiState.orderDivisionCode == "01") {
                            uiState.orderUnitPrice == "0"
                        } else {
                            uiState.orderUnitPrice.toLongOrNull()?.let { it > 0L } == true
                        }),
                )
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("체결 상태")
                AppSecondaryButton(
                    text = if (uiState.isSyncingOrders) "확인 중" else "미완료 주문 체결 확인",
                    onClick = viewModel::syncOrders,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConfigSaved && !uiState.isSyncingOrders,
                )
                uiState.lastOrderReconciliation?.let { result ->
                    AppSupportText(
                        "최근 대조: KIS 체결 ${result.matchedExecutionCount}건 · " +
                            "외부 체결 ${result.importedExternalOrderCount}건 · " +
                            "확인 필요 ${result.unresolvedOrderCount}건",
                    )
                }
                uiState.orders.take(5).forEach { order ->
                    Text(
                        "${order.productName} · ${if (order.side == KisOrderSide.BUY.name) "매수" else "매도"} " +
                            "${order.requestedQuantity}주 · ${order.status}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (uiState.orders.isEmpty()) AppSupportText("저장된 주문·체결 기록이 없습니다.")
            }
        }
    }
}

@Composable
private fun StockOrderQuantityCalculator(
    uiState: StockUiState,
    onAmountChange: (String) -> Unit,
    onApplyPercent: (Int) -> Unit,
) {
    val availability = uiState.orderAvailability ?: return
    val calculationUnitPrice = if (uiState.orderDivisionCode == "01") {
        availability.currentPrice
    } else {
        uiState.orderUnitPrice.toLongOrNull() ?: availability.currentPrice
    }
    val calculatedQuantity = uiState.orderQuantity.toLongOrNull() ?: 0L
    val estimatedOrderAmount = runCatching {
        Math.multiplyExact(calculatedQuantity, calculationUnitPrice)
    }.getOrNull()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text(
                text = "수량 계산기",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "주문가능 ${availability.availableQuantity}주 · 계산단가 ${calculationUnitPrice.toWon()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextField(
                value = uiState.orderCalculationAmount,
                onValueChange = onAmountChange,
                label = "주문 금액으로 수량 계산",
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                listOf(10 to "10%", 25 to "25%", 50 to "50%", 100 to "최대").forEach { (percent, label) ->
                    AppSelectableChip(
                        label = label,
                        selected = uiState.orderQuantityPercent == percent,
                        onClick = { onApplyPercent(percent) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = if (calculatedQuantity > 0L) {
                    "적용 수량 ${calculatedQuantity}주 · 예상 주문금액 ${estimatedOrderAmount.toWon()}"
                } else {
                    "금액을 입력하거나 비율을 선택하면 주문 수량에 자동 적용됩니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (calculatedQuantity > 0L) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (uiState.orderDivisionCode == "01") {
                Text(
                    text = "시장가 예상 주문금액은 실제 체결금액과 다를 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
